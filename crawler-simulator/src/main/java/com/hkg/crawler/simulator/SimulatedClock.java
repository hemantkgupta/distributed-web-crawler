package com.hkg.crawler.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Deterministic clock that advances only when {@link #advance} is
 * called. The simulator drives all time-dependent code through this
 * clock so race conditions and timing-dependent bugs are reproducible.
 *
 * <p>Inspired by the FoundationDB deterministic-simulation pattern: the
 * production system uses {@link Clock#systemUTC()}; the simulator uses
 * this. Same code, different clock injection.
 */
public final class SimulatedClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public SimulatedClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    public SimulatedClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public synchronized void advance(Duration d) {
        if (d.isNegative()) {
            throw new IllegalArgumentException("clock cannot move backward");
        }
        this.now = now.plus(d);
    }

    public synchronized void setTo(Instant target) {
        if (target.isBefore(now)) {
            throw new IllegalArgumentException("clock cannot move backward");
        }
        this.now = target;
    }

    @Override public synchronized Instant instant() { return now; }
    @Override public ZoneId getZone()               { return zone; }
    @Override public Clock withZone(ZoneId z)       { return new SimulatedClock(now, z); }
}
