package com.hkg.crawler.coordinator.membership;

import com.hkg.crawler.coordinator.AgentId;

import java.time.Instant;
import java.util.Objects;

/**
 * One agent's view of another agent's state, propagated through gossip.
 *
 * <p>Status is the SWIM-style three-state model:
 * <ul>
 *   <li>{@link Status#ALIVE}    — recently observed healthy</li>
 *   <li>{@link Status#SUSPECT}  — missed N gossip rounds; under suspicion</li>
 *   <li>{@link Status#DEAD}     — permanently failed (etcd-anchored)</li>
 * </ul>
 *
 * <p>The {@code generation} field handles the case where a crashed
 * agent rejoins: its new snapshots have a higher generation and override
 * any lingering DEAD entries from the previous incarnation.
 */
public record AgentSnapshot(
    AgentId  agentId,
    String   region,
    String   host,
    int      port,
    int      generation,
    Status   status,
    Instant  lastHeartbeatAt,
    int      vnodeCount
) {
    public AgentSnapshot {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
    }

    public enum Status { ALIVE, SUSPECT, DEAD }

    /**
     * SWIM-style precedence: a higher-generation snapshot supersedes a
     * lower-generation one regardless of status; within the same
     * generation, DEAD &gt; SUSPECT &gt; ALIVE (a death claim wins).
     */
    public boolean supersedes(AgentSnapshot other) {
        if (!agentId.equals(other.agentId)) return false;
        if (generation > other.generation) return true;
        if (generation < other.generation) return false;
        return statusRank(this.status) > statusRank(other.status);
    }

    private static int statusRank(Status s) {
        return switch (s) {
            case ALIVE   -> 0;
            case SUSPECT -> 1;
            case DEAD    -> 2;
        };
    }

    public AgentSnapshot withStatus(Status newStatus, Instant now) {
        return new AgentSnapshot(agentId, region, host, port, generation,
            newStatus, now, vnodeCount);
    }

    public AgentSnapshot withHeartbeat(Instant now) {
        return new AgentSnapshot(agentId, region, host, port, generation,
            Status.ALIVE, now, vnodeCount);
    }
}
