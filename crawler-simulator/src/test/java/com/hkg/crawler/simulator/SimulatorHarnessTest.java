package com.hkg.crawler.simulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatorHarnessTest {

    @Test
    void clock_advances_only_when_advance_called() {
        SimulatedClock clock = new SimulatedClock(Instant.parse("2026-04-27T12:00:00Z"));
        Instant before = clock.instant();
        clock.advance(Duration.ofSeconds(5));
        assertThat(clock.instant()).isAfter(before);
        assertThat(clock.instant()).isEqualTo(before.plusSeconds(5));
    }

    @Test
    void clock_rejects_backward_movement() {
        SimulatedClock clock = new SimulatedClock(Instant.parse("2026-04-27T12:00:00Z"));
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.setTo(Instant.parse("2026-04-27T11:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deterministic_run_is_bit_for_bit_reproducible() {
        List<Long> firstRun = recordRun(42L);
        List<Long> secondRun = recordRun(42L);
        assertThat(firstRun).isEqualTo(secondRun);
    }

    @Test
    void different_seeds_produce_different_runs() {
        List<Long> run42 = recordRun(42L);
        List<Long> run43 = recordRun(43L);
        assertThat(run42).isNotEqualTo(run43);
    }

    private List<Long> recordRun(long seed) {
        SimulatorHarness harness = new SimulatorHarness(
            Instant.parse("2026-04-27T12:00:00Z"), seed);
        List<Long> values = new ArrayList<>();
        harness.registerStep(tick -> values.add(harness.random().nextLong()));
        harness.run(20, Duration.ofMillis(10));
        return values;
    }

    @Test
    void step_functions_execute_in_registration_order() {
        SimulatorHarness harness = new SimulatorHarness(
            Instant.parse("2026-04-27T12:00:00Z"), 1);
        List<String> log = new ArrayList<>();
        harness.registerStep(t -> log.add("first"));
        harness.registerStep(t -> log.add("second"));
        harness.registerStep(t -> log.add("third"));
        harness.run(2, Duration.ofMillis(10));
        assertThat(log).containsExactly(
            "first", "second", "third",
            "first", "second", "third");
    }

    @Test
    void simulated_network_drops_messages_at_configured_probability() {
        SimulatedNetwork<String> network = new SimulatedNetwork<>(42L, 0.5, 0, 0);
        for (int i = 0; i < 1000; i++) network.send("msg" + i, 0);
        assertThat(network.droppedCount() + network.pendingCount()).isEqualTo(1000);
        // Roughly 50% should be dropped (allow generous margin).
        assertThat(network.droppedCount()).isBetween(400, 600);
    }

    @Test
    void simulated_network_delivers_after_delay() {
        SimulatedNetwork<String> network = new SimulatedNetwork<>(42L, 0, 50, 50);
        network.send("hello", 0);
        // At t=0 nothing delivered.
        assertThat(network.tick(0)).isEmpty();
        // At t=49 still nothing.
        assertThat(network.tick(49)).isEmpty();
        // At t=50 delivered.
        assertThat(network.tick(50)).containsExactly("hello");
    }

    @Test
    void harness_drives_registered_networks() {
        SimulatorHarness harness = new SimulatorHarness(
            Instant.parse("2026-04-27T12:00:00Z"), 1);
        SimulatedNetwork<String> network = new SimulatedNetwork<>(1L, 0.0, 0, 0);
        harness.registerNetwork(network);

        AtomicInteger seen = new AtomicInteger();
        harness.registerStep(t -> {
            // Send one message per tick; immediate delivery.
            network.send("tick-" + t.tickIndex(), t.now().toEpochMilli());
            seen.set(network.deliveredCount());
        });

        harness.run(5, Duration.ofMillis(10));
        assertThat(network.deliveredCount()).isEqualTo(5);
        // The step in tick N records deliveredCount BEFORE that tick's
        // network.tick() runs, so it sees N-1 deliveries.
        assertThat(seen.get()).isEqualTo(4);
    }

    @Test
    void tick_count_matches_run_iterations() {
        SimulatorHarness harness = new SimulatorHarness(
            Instant.parse("2026-04-27T12:00:00Z"), 1);
        harness.run(100, Duration.ofMillis(5));
        assertThat(harness.tickCount()).isEqualTo(100);
    }
}
