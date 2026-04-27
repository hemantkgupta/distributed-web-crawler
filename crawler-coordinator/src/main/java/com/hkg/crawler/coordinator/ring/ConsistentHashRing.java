package com.hkg.crawler.coordinator.ring;

import com.hkg.crawler.common.Host;
import com.hkg.crawler.coordinator.AgentId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * UbiCrawler-style consistent hash ring with virtual nodes — the
 * routing layer for the distributed crawler.
 *
 * <p>Agents register on the ring with a configurable number of virtual
 * nodes (default 200). Each vnode is an independent point on the
 * 64-bit ring at position {@code hash(agent_id || vnode_index)}.
 * Hosts route to agents by computing {@code hash(host_authority)} and
 * walking clockwise to the next vnode; that vnode's owning agent is the
 * host's owner.
 *
 * <p>Why the host (not URL) is hashed: same-host URLs concentrate on
 * one agent so the per-host politeness clock stays local. URL-hash
 * sharding spreads same-host URLs across agents and forces a distributed
 * lock on the politeness state — exactly what UbiCrawler avoids.
 *
 * <p>Why virtual nodes: host-popularity is power-law; a single point
 * per agent produces operationally-hot shards. ~200 vnodes per agent
 * smooth the distribution.
 *
 * <p>Membership changes move only {@code O(K/N)} hosts (vs naïve
 * modulo's {@code O(K)}); rebalancing is incremental.
 *
 * <p>Thread-safe (read-write lock around the {@link TreeMap}).
 */
public final class ConsistentHashRing {

    public static final int DEFAULT_VNODES_PER_AGENT = 200;

    /** Sorted ring: position → owning agent. */
    private final TreeMap<Long, AgentId> positions = new TreeMap<>();

    /** Per-agent vnode positions, for {@link #removeAgent} and inspection. */
    private final Map<AgentId, long[]> agentPositions = new java.util.HashMap<>();

    private final int vnodesPerAgent;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ConsistentHashRing() {
        this(DEFAULT_VNODES_PER_AGENT);
    }

    public ConsistentHashRing(int vnodesPerAgent) {
        if (vnodesPerAgent < 1) {
            throw new IllegalArgumentException("vnodesPerAgent must be ≥ 1");
        }
        this.vnodesPerAgent = vnodesPerAgent;
    }

    /**
     * Register an agent. Idempotent — re-adding an existing agent is a
     * no-op (its vnodes are deterministic).
     *
     * @return the number of vnode positions added (0 if already registered)
     */
    public int addAgent(AgentId agent) {
        lock.writeLock().lock();
        try {
            if (agentPositions.containsKey(agent)) return 0;
            long[] vnodes = new long[vnodesPerAgent];
            for (int i = 0; i < vnodesPerAgent; i++) {
                long pos = hash(agent.value() + ":" + i);
                vnodes[i] = pos;
                positions.put(pos, agent);
            }
            agentPositions.put(agent, vnodes);
            return vnodesPerAgent;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Deregister an agent. Removes all of its vnode positions.
     *
     * @return the number of vnode positions removed (0 if not registered)
     */
    public int removeAgent(AgentId agent) {
        lock.writeLock().lock();
        try {
            long[] vnodes = agentPositions.remove(agent);
            if (vnodes == null) return 0;
            for (long pos : vnodes) {
                // Tolerate the case where the position is owned by a tie
                // in another agent's hash; remove only if it points to us.
                AgentId current = positions.get(pos);
                if (agent.equals(current)) positions.remove(pos);
            }
            return vnodes.length;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Find the agent that owns {@code host}: walk clockwise from
     * {@code hash(host)} to the next vnode position. Returns empty if
     * the ring is empty.
     */
    public Optional<AgentId> ownerOf(Host host) {
        lock.readLock().lock();
        try {
            if (positions.isEmpty()) return Optional.empty();
            long position = hash(host.value());
            Map.Entry<Long, AgentId> e = positions.ceilingEntry(position);
            if (e == null) e = positions.firstEntry();
            return Optional.of(e.getValue());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Find the next {@code n} distinct agents clockwise from
     * {@code hash(host)}. Used for replica placement and hot-host
     * spillover. {@code n} cannot exceed the agent count.
     */
    public List<AgentId> ownersOf(Host host, int n) {
        lock.readLock().lock();
        try {
            int agentCount = agentPositions.size();
            if (agentCount == 0) return List.of();
            int limit = Math.min(n, agentCount);
            List<AgentId> owners = new ArrayList<>(limit);
            long start = hash(host.value());
            // Walk the ring from `start` collecting distinct agents.
            Long cursor = start;
            while (owners.size() < limit) {
                Map.Entry<Long, AgentId> e = positions.ceilingEntry(cursor);
                if (e == null) e = positions.firstEntry();
                AgentId candidate = e.getValue();
                if (!owners.contains(candidate)) owners.add(candidate);
                // Advance cursor past this entry.
                Long next = positions.higherKey(e.getKey());
                cursor = (next == null) ? positions.firstKey() : next;
                // Safety: if we've made a full lap and still haven't
                // collected `limit` distinct agents, the ring is exhausted.
                if (cursor.equals(start)) break;
            }
            return Collections.unmodifiableList(owners);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int agentCount() {
        lock.readLock().lock();
        try { return agentPositions.size(); } finally { lock.readLock().unlock(); }
    }

    public int vnodeCount() {
        lock.readLock().lock();
        try { return positions.size(); } finally { lock.readLock().unlock(); }
    }

    public Collection<AgentId> agents() {
        lock.readLock().lock();
        try { return List.copyOf(agentPositions.keySet()); }
        finally { lock.readLock().unlock(); }
    }

    public int vnodesPerAgent() { return vnodesPerAgent; }

    // ---- internals -----------------------------------------------------

    /**
     * 64-bit FNV-1a + avalanche-mix hash. Same pattern used elsewhere in
     * the codebase for consistency. Returns an unsigned-comparable
     * non-negative value (we use the full long range; TreeMap orders
     * naturally).
     */
    static long hash(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
