package com.hkg.crawler.coordinator.ring;

import com.hkg.crawler.common.Host;
import com.hkg.crawler.coordinator.AgentId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistentHashRingTest {

    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing(50);   // small vnode count for tests
    }

    @Test
    void empty_ring_returns_no_owner() {
        assertThat(ring.ownerOf(Host.of("example.com"))).isEmpty();
        assertThat(ring.agentCount()).isZero();
    }

    @Test
    void single_agent_owns_all_hosts() {
        AgentId only = AgentId.of("agent-A");
        ring.addAgent(only);

        assertThat(ring.ownerOf(Host.of("a.com"))).hasValue(only);
        assertThat(ring.ownerOf(Host.of("b.com"))).hasValue(only);
        assertThat(ring.ownerOf(Host.of("c.com"))).hasValue(only);
    }

    @Test
    void same_host_always_routes_to_same_agent() {
        ring.addAgent(AgentId.of("A"));
        ring.addAgent(AgentId.of("B"));
        ring.addAgent(AgentId.of("C"));

        Host host = Host.of("example.com");
        Optional<AgentId> owner1 = ring.ownerOf(host);
        Optional<AgentId> owner2 = ring.ownerOf(host);
        Optional<AgentId> owner3 = ring.ownerOf(host);

        assertThat(owner1).isPresent();
        assertThat(owner1).isEqualTo(owner2);
        assertThat(owner2).isEqualTo(owner3);
    }

    @Test
    void load_distributes_across_agents() {
        for (int i = 0; i < 10; i++) ring.addAgent(AgentId.of("agent-" + i));

        Map<AgentId, Integer> counts = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            Host host = Host.of("host" + i + ".com");
            AgentId owner = ring.ownerOf(host).orElseThrow();
            counts.merge(owner, 1, Integer::sum);
        }
        assertThat(counts).hasSize(10);
        // Each agent should get ~1000 hosts; allow generous margin.
        for (Map.Entry<AgentId, Integer> e : counts.entrySet()) {
            assertThat(e.getValue()).as("agent %s", e.getKey()).isBetween(500, 1500);
        }
    }

    @Test
    void adding_agent_only_remaps_fraction_of_hosts() {
        for (int i = 0; i < 5; i++) ring.addAgent(AgentId.of("agent-" + i));

        Map<Host, AgentId> initialOwners = new HashMap<>();
        List<Host> hosts = java.util.stream.IntStream.range(0, 1000)
            .mapToObj(i -> Host.of("host" + i + ".com"))
            .toList();
        for (Host h : hosts) initialOwners.put(h, ring.ownerOf(h).orElseThrow());

        ring.addAgent(AgentId.of("new-agent"));

        // Count how many hosts moved to the new agent.
        int moved = 0;
        for (Host h : hosts) {
            AgentId before = initialOwners.get(h);
            AgentId after  = ring.ownerOf(h).orElseThrow();
            if (!before.equals(after)) moved++;
        }
        // Theoretically ~1000/6 ≈ 167 hosts should move; allow generous margin.
        assertThat(moved).as("hosts moved to new agent").isBetween(50, 350);
        // Critically, at least ~75% of hosts did NOT move.
        assertThat(moved).isLessThan(hosts.size() / 2);
    }

    @Test
    void removing_agent_redistributes_its_hosts() {
        for (int i = 0; i < 5; i++) ring.addAgent(AgentId.of("agent-" + i));
        AgentId target = AgentId.of("agent-3");
        Set<Host> hostsOwnedByTarget = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            Host h = Host.of("host" + i + ".com");
            if (ring.ownerOf(h).orElseThrow().equals(target)) {
                hostsOwnedByTarget.add(h);
            }
        }
        assertThat(hostsOwnedByTarget).isNotEmpty();

        ring.removeAgent(target);

        // None of those hosts should still resolve to the removed agent.
        for (Host h : hostsOwnedByTarget) {
            assertThat(ring.ownerOf(h).orElseThrow()).isNotEqualTo(target);
        }
    }

    @Test
    void removing_agent_returns_vnode_count() {
        AgentId a = AgentId.of("A");
        ring.addAgent(a);
        int removed = ring.removeAgent(a);
        assertThat(removed).isEqualTo(50);
    }

    @Test
    void removing_unknown_agent_is_noop() {
        assertThat(ring.removeAgent(AgentId.of("never-added"))).isZero();
    }

    @Test
    void adding_existing_agent_is_idempotent() {
        AgentId a = AgentId.of("A");
        assertThat(ring.addAgent(a)).isEqualTo(50);
        assertThat(ring.addAgent(a)).isZero();
        assertThat(ring.agentCount()).isEqualTo(1);
    }

    @Test
    void ownersOf_returns_n_distinct_agents() {
        for (int i = 0; i < 5; i++) ring.addAgent(AgentId.of("agent-" + i));

        List<AgentId> owners = ring.ownersOf(Host.of("example.com"), 3);
        assertThat(owners).hasSize(3);
        assertThat(new HashSet<>(owners)).hasSize(3);
    }

    @Test
    void ownersOf_capped_at_agent_count() {
        ring.addAgent(AgentId.of("only"));
        assertThat(ring.ownersOf(Host.of("x.com"), 5)).hasSize(1);
    }

    @Test
    void vnode_and_agent_counts_match() {
        ring.addAgent(AgentId.of("A"));
        ring.addAgent(AgentId.of("B"));
        assertThat(ring.agentCount()).isEqualTo(2);
        // 50 vnodes per agent; minor overlap-collisions are theoretically possible
        // but extremely unlikely with a 64-bit hash.
        assertThat(ring.vnodeCount()).isBetween(99, 100);
    }
}
