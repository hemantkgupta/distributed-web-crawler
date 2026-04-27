package com.hkg.crawler.coordinator.membership;

import com.hkg.crawler.coordinator.AgentId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipStateTest {

    private static final AgentId SELF = AgentId.of("self");
    private static final AgentId PEER_A = AgentId.of("peer-A");
    private static final AgentId PEER_B = AgentId.of("peer-B");

    private Instant t0;
    private MembershipState state;

    @BeforeEach
    void setUp() {
        t0 = Instant.parse("2026-04-27T12:00:00Z");
        state = new MembershipState(SELF, Duration.ofSeconds(5), Duration.ofSeconds(15));
    }

    private AgentSnapshot snap(AgentId id, AgentSnapshot.Status status, Instant heartbeat) {
        return new AgentSnapshot(id, "us-east", id.value() + ".local", 7000,
            1, status, heartbeat, 200);
    }

    @Test
    void merge_adds_unknown_peers() {
        state.merge(List.of(snap(PEER_A, AgentSnapshot.Status.ALIVE, t0)));
        assertThat(state.knownAgentCount()).isEqualTo(1);
        assertThat(state.view(PEER_A)).isPresent();
    }

    @Test
    void supersedes_higher_generation_wins() {
        state.merge(List.of(snap(PEER_A, AgentSnapshot.Status.ALIVE, t0)));
        AgentSnapshot newer = new AgentSnapshot(PEER_A, "us-east", "host", 7000,
            2, AgentSnapshot.Status.ALIVE, t0, 200);
        state.merge(List.of(newer));
        assertThat(state.view(PEER_A).orElseThrow().generation()).isEqualTo(2);
    }

    @Test
    void supersedes_same_generation_DEAD_beats_ALIVE() {
        state.merge(List.of(snap(PEER_A, AgentSnapshot.Status.ALIVE, t0)));
        state.merge(List.of(snap(PEER_A, AgentSnapshot.Status.DEAD, t0)));
        assertThat(state.view(PEER_A).orElseThrow().status())
            .isEqualTo(AgentSnapshot.Status.DEAD);
    }

    @Test
    void supersedes_lower_generation_loses() {
        AgentSnapshot newer = new AgentSnapshot(PEER_A, "us-east", "host", 7000,
            5, AgentSnapshot.Status.ALIVE, t0, 200);
        state.merge(List.of(newer));
        AgentSnapshot older = new AgentSnapshot(PEER_A, "us-east", "host", 7000,
            3, AgentSnapshot.Status.DEAD, t0.plusSeconds(1), 200);
        state.merge(List.of(older));
        // The older-generation DEAD claim should be ignored.
        assertThat(state.view(PEER_A).orElseThrow().status())
            .isEqualTo(AgentSnapshot.Status.ALIVE);
        assertThat(state.view(PEER_A).orElseThrow().generation()).isEqualTo(5);
    }

    @Test
    void evaluateLiveness_promotes_silent_ALIVE_to_SUSPECT() {
        state.merge(List.of(snap(PEER_A, AgentSnapshot.Status.ALIVE, t0)));
        // 6 seconds later — past suspectAfter (5s).
        Instant later = t0.plusSeconds(6);
        state.evaluateLiveness(later);
        assertThat(state.view(PEER_A).orElseThrow().status())
            .isEqualTo(AgentSnapshot.Status.SUSPECT);
    }

    @Test
    void evaluateLiveness_promotes_silent_SUSPECT_to_DEAD() {
        state.merge(List.of(snap(PEER_A, AgentSnapshot.Status.SUSPECT, t0)));
        Instant later = t0.plusSeconds(20);   // past deadAfter (15s)
        state.evaluateLiveness(later);
        assertThat(state.view(PEER_A).orElseThrow().status())
            .isEqualTo(AgentSnapshot.Status.DEAD);
    }

    @Test
    void evaluateLiveness_does_not_demote_self() {
        AgentSnapshot self = snap(SELF, AgentSnapshot.Status.ALIVE, t0.minusSeconds(60));
        state.merge(List.of(self));
        state.evaluateLiveness(t0);
        // Self is excluded from liveness eviction.
        assertThat(state.view(SELF).orElseThrow().status())
            .isEqualTo(AgentSnapshot.Status.ALIVE);
    }

    @Test
    void heartbeat_refreshes_self_state() {
        AgentSnapshot self = snap(SELF, AgentSnapshot.Status.ALIVE, t0);
        state.heartbeat(self, t0.plusSeconds(10));
        assertThat(state.view(SELF).orElseThrow().lastHeartbeatAt())
            .isEqualTo(t0.plusSeconds(10));
    }

    @Test
    void reconcile_with_anchor_lets_anchor_override_gossip() {
        try (InMemoryMembershipAnchor anchor = new InMemoryMembershipAnchor()) {
            // Gossip says PEER_A is ALIVE.
            state.merge(List.of(snap(PEER_A, AgentSnapshot.Status.ALIVE, t0)));
            // Anchor (etcd) authoritatively says PEER_A is DEAD with higher generation.
            anchor.upsert(new AgentSnapshot(PEER_A, "us-east", "host", 7000,
                10, AgentSnapshot.Status.DEAD, t0, 200));

            state.reconcileWithAnchor(anchor);
            assertThat(state.view(PEER_A).orElseThrow().status())
                .isEqualTo(AgentSnapshot.Status.DEAD);
        }
    }

    @Test
    void withStatus_returns_only_matching() {
        state.merge(List.of(
            snap(PEER_A, AgentSnapshot.Status.ALIVE, t0),
            snap(PEER_B, AgentSnapshot.Status.SUSPECT, t0)
        ));
        assertThat(state.withStatus(AgentSnapshot.Status.ALIVE)).hasSize(1);
        assertThat(state.withStatus(AgentSnapshot.Status.SUSPECT)).hasSize(1);
        assertThat(state.withStatus(AgentSnapshot.Status.DEAD)).isEmpty();
    }

    @Test
    void anchor_get_and_remove_round_trip() {
        try (InMemoryMembershipAnchor anchor = new InMemoryMembershipAnchor()) {
            anchor.upsert(snap(PEER_A, AgentSnapshot.Status.ALIVE, t0));
            assertThat(anchor.get(PEER_A)).isPresent();
            anchor.remove(PEER_A);
            assertThat(anchor.get(PEER_A)).isEmpty();
        }
    }
}
