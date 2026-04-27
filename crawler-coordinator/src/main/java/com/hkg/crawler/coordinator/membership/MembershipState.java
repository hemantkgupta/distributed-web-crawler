package com.hkg.crawler.coordinator.membership;

import com.hkg.crawler.coordinator.AgentId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One agent's local view of cluster membership — populated by gossip
 * exchanges and reconciled with the {@link MembershipAnchor}.
 *
 * <p>Each gossip round, the agent sends its current view to a small
 * fanout of peers; the receiver merges incoming snapshots using
 * {@link AgentSnapshot#supersedes} precedence. Agents that miss N
 * consecutive heartbeat windows are demoted ALIVE → SUSPECT → DEAD.
 */
public final class MembershipState {

    private final AgentId selfId;
    private final ConcurrentHashMap<AgentId, AgentSnapshot> view = new ConcurrentHashMap<>();
    private final Duration suspectAfter;
    private final Duration deadAfter;

    public MembershipState(AgentId selfId, Duration suspectAfter, Duration deadAfter) {
        if (suspectAfter.compareTo(deadAfter) > 0) {
            throw new IllegalArgumentException("suspectAfter must be ≤ deadAfter");
        }
        this.selfId = selfId;
        this.suspectAfter = suspectAfter;
        this.deadAfter = deadAfter;
    }

    /**
     * Apply incoming snapshots from a gossip exchange. Each is merged
     * against the local view using SWIM precedence.
     */
    public void merge(Collection<AgentSnapshot> incoming) {
        for (AgentSnapshot s : incoming) {
            view.merge(s.agentId(), s,
                (existing, in) -> in.supersedes(existing) ? in : existing);
        }
    }

    /**
     * Promote any ALIVE agent that hasn't been heard from in
     * {@code suspectAfter} to SUSPECT, and any SUSPECT to DEAD after
     * {@code deadAfter}.
     */
    public void evaluateLiveness(Instant now) {
        for (AgentSnapshot s : view.values()) {
            if (s.agentId().equals(selfId)) continue;
            Duration age = Duration.between(s.lastHeartbeatAt(), now);
            if (s.status() == AgentSnapshot.Status.ALIVE
                    && age.compareTo(suspectAfter) > 0) {
                view.put(s.agentId(), s.withStatus(AgentSnapshot.Status.SUSPECT, now));
            } else if (s.status() == AgentSnapshot.Status.SUSPECT
                    && age.compareTo(deadAfter) > 0) {
                view.put(s.agentId(), s.withStatus(AgentSnapshot.Status.DEAD, now));
            }
        }
    }

    /** Record self's heartbeat for outbound gossip. */
    public void heartbeat(AgentSnapshot self, Instant now) {
        view.put(selfId, self.withHeartbeat(now));
    }

    public Optional<AgentSnapshot> view(AgentId id) {
        return Optional.ofNullable(view.get(id));
    }

    /** Snapshot of all known agents (used as the gossip payload). */
    public List<AgentSnapshot> snapshot() {
        return new ArrayList<>(view.values());
    }

    /** Agents currently in the given status. */
    public List<AgentSnapshot> withStatus(AgentSnapshot.Status status) {
        return view.values().stream()
            .filter(s -> s.status() == status)
            .toList();
    }

    public AgentId selfId() { return selfId; }
    public int    knownAgentCount() { return view.size(); }

    /**
     * Reconcile with the durable anchor: anchor wins on disagreement
     * (CP store breaks ties on split-brain).
     */
    public void reconcileWithAnchor(MembershipAnchor anchor) {
        for (AgentSnapshot anchored : anchor.snapshot()) {
            view.merge(anchored.agentId(), anchored,
                (gossiped, anchorSnap) -> anchorSnap.supersedes(gossiped) ? anchorSnap : gossiped);
        }
    }
}
