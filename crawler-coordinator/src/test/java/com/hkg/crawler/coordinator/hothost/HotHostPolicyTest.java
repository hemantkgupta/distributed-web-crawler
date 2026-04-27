package com.hkg.crawler.coordinator.hothost;

import com.hkg.crawler.common.Host;
import com.hkg.crawler.coordinator.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HotHostPolicyTest {

    private static final Host HOT = Host.of("youtube.com");
    private static final AgentId OWNER = AgentId.of("agent-3");
    private static final AgentId SPILL_A = AgentId.of("agent-7");
    private static final AgentId SPILL_B = AgentId.of("agent-19");

    private HotHostPolicy makePolicy() {
        return new HotHostPolicy(
            HOT, OWNER, List.of(SPILL_A, SPILL_B), 3,
            Instant.parse("2026-04-27T12:00:00Z"),
            "operator@example.com",
            "youtube.com — 30% of one shard's URL discovery");
    }

    @Test
    void all_participants_starts_with_owner() {
        HotHostPolicy p = makePolicy();
        assertThat(p.allParticipants()).containsExactly(OWNER, SPILL_A, SPILL_B);
    }

    @Test
    void executor_is_deterministic_per_url() {
        HotHostPolicy p = makePolicy();
        AgentId first  = p.executorFor("http://youtube.com/watch?v=abc");
        AgentId second = p.executorFor("http://youtube.com/watch?v=abc");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void executor_distributes_across_participants() {
        HotHostPolicy p = makePolicy();
        Map<AgentId, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            AgentId agent = p.executorFor("http://youtube.com/watch?v=" + i);
            counts.merge(agent, 1, Integer::sum);
        }
        assertThat(counts).hasSize(3);
        // All three participants should get a fair share (~333 each, allow margin).
        for (int count : counts.values()) {
            assertThat(count).isBetween(200, 500);
        }
    }

    @Test
    void rejects_split_factor_below_2() {
        assertThatThrownBy(() -> new HotHostPolicy(HOT, OWNER, List.of(), 1,
            Instant.now(), "x", "y"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_mismatched_spillover_count() {
        assertThatThrownBy(() -> new HotHostPolicy(HOT, OWNER, List.of(SPILL_A), 3,
            Instant.now(), "x", "y"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_owner_in_spillover() {
        assertThatThrownBy(() -> new HotHostPolicy(HOT, OWNER, List.of(OWNER, SPILL_B), 3,
            Instant.now(), "x", "y"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registry_round_trip() {
        HotHostRegistry registry = new HotHostRegistry();
        HotHostPolicy p = makePolicy();
        registry.put(p);

        assertThat(registry.isHot(HOT)).isTrue();
        assertThat(registry.lookup(HOT)).hasValue(p);
        assertThat(registry.size()).isEqualTo(1);

        registry.remove(HOT);
        assertThat(registry.isHot(HOT)).isFalse();
        assertThat(registry.lookup(HOT)).isEmpty();
    }

    // ---- SharedPolitenessClock tests -----------------------------

    @Test
    void shared_clock_grants_first_claim_immediately() {
        InMemorySharedPolitenessClock clock = new InMemorySharedPolitenessClock();
        Instant t0 = Instant.parse("2026-04-27T12:00:00Z");
        SharedPolitenessClock.ClaimResult r =
            clock.tryClaim(HOT, Duration.ofSeconds(1), t0);
        assertThat(r).isInstanceOf(SharedPolitenessClock.ClaimResult.Granted.class);
    }

    @Test
    void shared_clock_denies_within_delay_window() {
        InMemorySharedPolitenessClock clock = new InMemorySharedPolitenessClock();
        Instant t0 = Instant.parse("2026-04-27T12:00:00Z");
        clock.tryClaim(HOT, Duration.ofSeconds(2), t0);

        // 1 second later → still within the 2s delay
        SharedPolitenessClock.ClaimResult r =
            clock.tryClaim(HOT, Duration.ofSeconds(2), t0.plusSeconds(1));
        assertThat(r).isInstanceOf(SharedPolitenessClock.ClaimResult.Denied.class);
        Duration remaining = ((SharedPolitenessClock.ClaimResult.Denied) r).timeUntilEligible();
        assertThat(remaining).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void shared_clock_grants_after_delay_elapses() {
        InMemorySharedPolitenessClock clock = new InMemorySharedPolitenessClock();
        Instant t0 = Instant.parse("2026-04-27T12:00:00Z");
        clock.tryClaim(HOT, Duration.ofSeconds(2), t0);

        Instant t3 = t0.plusSeconds(3);   // past the 2s delay
        SharedPolitenessClock.ClaimResult r = clock.tryClaim(HOT, Duration.ofSeconds(2), t3);
        assertThat(r).isInstanceOf(SharedPolitenessClock.ClaimResult.Granted.class);
    }

    @Test
    void shared_clock_serializes_concurrent_claims() throws InterruptedException {
        InMemorySharedPolitenessClock clock = new InMemorySharedPolitenessClock();
        Instant t0 = Instant.parse("2026-04-27T12:00:00Z");
        Set<Object> grantedAt = java.util.Collections.synchronizedSet(new HashSet<>());
        int N = 50;
        Thread[] threads = new Thread[N];
        for (int i = 0; i < N; i++) {
            threads[i] = new Thread(() -> {
                SharedPolitenessClock.ClaimResult r =
                    clock.tryClaim(HOT, Duration.ofSeconds(1), t0);
                if (r instanceof SharedPolitenessClock.ClaimResult.Granted g) {
                    grantedAt.add(g.newNextFetchTime());
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        // Exactly one thread should have been granted (single-rate guarantee).
        assertThat(grantedAt).hasSize(1);
    }

    @Test
    void shared_clock_per_host_independence() {
        InMemorySharedPolitenessClock clock = new InMemorySharedPolitenessClock();
        Host other = Host.of("otherhost.com");
        Instant t0 = Instant.parse("2026-04-27T12:00:00Z");

        // Granted on both — distinct hosts have independent clocks.
        SharedPolitenessClock.ClaimResult r1 = clock.tryClaim(HOT, Duration.ofSeconds(2), t0);
        SharedPolitenessClock.ClaimResult r2 = clock.tryClaim(other, Duration.ofSeconds(2), t0);
        assertThat(r1).isInstanceOf(SharedPolitenessClock.ClaimResult.Granted.class);
        assertThat(r2).isInstanceOf(SharedPolitenessClock.ClaimResult.Granted.class);
    }
}
