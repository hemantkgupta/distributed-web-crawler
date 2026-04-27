package com.hkg.crawler.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Top-level deterministic-simulation harness. Drives a {@link
 * SimulatedClock} and an arbitrary number of registered "step
 * functions" through a fixed number of ticks.
 *
 * <p>Inspiration: FoundationDB's simulation testing. By driving every
 * randomness source from a single seed and replacing the clock and
 * network with deterministic substitutes, the simulator produces
 * bit-for-bit reproducible runs — invaluable for race-condition
 * debugging.
 *
 * <p>Single-threaded by design; concurrency in the simulated system
 * is interleaved manually via the step functions, not via real threads.
 */
public final class SimulatorHarness {

    private final SimulatedClock clock;
    private final long seed;
    private final Random random;
    private final List<Consumer<SimulatorTick>> steps = new ArrayList<>();
    private final List<SimulatedNetwork<?>> networks = new ArrayList<>();
    private long tickCount = 0;

    public SimulatorHarness(Instant startTime, long seed) {
        this.clock = new SimulatedClock(startTime);
        this.seed = seed;
        this.random = new Random(seed);
    }

    public SimulatedClock clock() { return clock; }
    public Random         random() { return random; }
    public long           seed()   { return seed; }

    /**
     * Register a step function that runs on every tick. Step ordering
     * is deterministic — they execute in registration order.
     */
    public void registerStep(Consumer<SimulatorTick> step) {
        steps.add(step);
    }

    /** Register a {@link SimulatedNetwork} so its tick is driven automatically. */
    public void registerNetwork(SimulatedNetwork<?> network) {
        networks.add(network);
    }

    /**
     * Run for {@code ticks} iterations, each advancing the clock by
     * {@code tickDuration}. Step functions execute every tick after
     * the clock is advanced.
     */
    public void run(int ticks, Duration tickDuration) {
        for (int i = 0; i < ticks; i++) {
            clock.advance(tickDuration);
            tickCount++;
            long currentMs = clock.instant().toEpochMilli();
            // Steps run first — they see the new clock time and may emit
            // network messages tagged with this instant.
            SimulatorTick tick = new SimulatorTick(tickCount, clock.instant());
            for (Consumer<SimulatorTick> step : steps) {
                step.accept(tick);
            }
            // Networks tick after — same-tick zero-delay messages are
            // delivered before the next iteration.
            for (SimulatedNetwork<?> network : networks) {
                network.tick(currentMs);
            }
        }
    }

    public long tickCount() { return tickCount; }

    /** Snapshot passed to each step function on each tick. */
    public record SimulatorTick(long tickIndex, Instant now) {}
}
