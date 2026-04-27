package com.hkg.crawler.coordinator.hothost;

import com.hkg.crawler.common.Host;
import com.hkg.crawler.coordinator.AgentId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Operator-configured spillover policy for one mega-host whose URL
 * volume dominates a single agent. Per blog §3 Problem 4
 * (Recommendation): the chosen host's URL space is spilled to a Redis
 * pool; idle sibling agents claim work from the pool but the **owning
 * agent's politeness clock remains the single source of truth** so
 * neither side accidentally violates per-host crawl-delay.
 *
 * <p>Configuration is durable in the registry (etcd in production); the
 * decision to flag a host hot is operator-driven (per blog §2 Problem 2:
 * automatic detection is Phase 2 refinement; manual labeling is the
 * baseline because there are only ~50–200 such hosts globally).
 */
public record HotHostPolicy(
    Host host,
    AgentId owningAgent,
    List<AgentId> spilloverAgents,
    int splitFactor,
    Instant configuredAt,
    String configuredBy,
    String reason
) {
    public HotHostPolicy {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(owningAgent, "owningAgent");
        Objects.requireNonNull(spilloverAgents, "spilloverAgents");
        Objects.requireNonNull(configuredAt, "configuredAt");
        Objects.requireNonNull(configuredBy, "configuredBy");
        Objects.requireNonNull(reason, "reason");
        spilloverAgents = List.copyOf(spilloverAgents);
        if (splitFactor < 2) {
            throw new IllegalArgumentException("splitFactor must be ≥ 2");
        }
        if (spilloverAgents.size() != splitFactor - 1) {
            throw new IllegalArgumentException(
                "spilloverAgents must contain splitFactor-1 = " + (splitFactor - 1)
                + " agents (got " + spilloverAgents.size() + ")");
        }
        if (spilloverAgents.contains(owningAgent)) {
            throw new IllegalArgumentException(
                "owningAgent must not appear in spilloverAgents");
        }
    }

    /** All agents (owner first) participating in this host's URL space. */
    public List<AgentId> allParticipants() {
        java.util.List<AgentId> all = new java.util.ArrayList<>();
        all.add(owningAgent);
        all.addAll(spilloverAgents);
        return java.util.Collections.unmodifiableList(all);
    }

    /**
     * Pick the executing agent for {@code url} via URL-hash within the
     * participant set. Same URL always lands on the same participant,
     * but the politeness clock is enforced by {@link #owningAgent}.
     */
    public AgentId executorFor(String url) {
        long hash = com.hkg.crawler.coordinator.ring.ConsistentHashRing.hash(url);
        int index = (int) ((hash & 0x7fffffffffffffffL) % splitFactor);
        return allParticipants().get(index);
    }
}
