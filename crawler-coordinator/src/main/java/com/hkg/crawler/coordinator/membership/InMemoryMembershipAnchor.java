package com.hkg.crawler.coordinator.membership;

import com.hkg.crawler.coordinator.AgentId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link MembershipAnchor} for tests and the single-process
 * setup. Production deployments use an etcd-backed implementation; the
 * SPI is unchanged.
 */
public final class InMemoryMembershipAnchor implements MembershipAnchor {

    private final ConcurrentHashMap<AgentId, AgentSnapshot> registry = new ConcurrentHashMap<>();

    @Override
    public void upsert(AgentSnapshot snapshot) {
        registry.merge(snapshot.agentId(), snapshot,
            (existing, incoming) -> incoming.supersedes(existing) ? incoming : existing);
    }

    @Override
    public Optional<AgentSnapshot> get(AgentId agentId) {
        return Optional.ofNullable(registry.get(agentId));
    }

    @Override
    public Collection<AgentSnapshot> snapshot() {
        return List.copyOf(registry.values());
    }

    @Override
    public void remove(AgentId agentId) {
        registry.remove(agentId);
    }

    @Override
    public void close() { /* no resources */ }
}
