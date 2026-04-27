package com.hkg.crawler.coordinator.hothost;

import com.hkg.crawler.common.Host;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of hot-host spillover policies. Production deployments
 * persist this in etcd's {@code crawler/hot_hosts/<host>} keyspace; the
 * in-process implementation suffices for tests and the single-process
 * simulator.
 *
 * <p>The Coordinator consults the registry **before** consistent-hash
 * routing: if a host has a hot policy, the executing agent is decided
 * by URL-hash within the participant set, not by the ring's owner.
 *
 * <p>Operator workflow (Phase 4 Control Plane API):
 * {@code POST /v1/coordinator/hot-hosts} configures a policy;
 * {@code DELETE /v1/coordinator/hot-hosts/{host}} removes it.
 */
public final class HotHostRegistry {

    private final ConcurrentHashMap<Host, HotHostPolicy> policies = new ConcurrentHashMap<>();

    /** Add or replace a hot-host policy. */
    public void put(HotHostPolicy policy) {
        policies.put(policy.host(), policy);
    }

    public Optional<HotHostPolicy> lookup(Host host) {
        return Optional.ofNullable(policies.get(host));
    }

    /** Is this host configured for spillover? */
    public boolean isHot(Host host) {
        return policies.containsKey(host);
    }

    public void remove(Host host) {
        policies.remove(host);
    }

    public Collection<HotHostPolicy> all() {
        return List.copyOf(policies.values());
    }

    public int size() { return policies.size(); }
}
