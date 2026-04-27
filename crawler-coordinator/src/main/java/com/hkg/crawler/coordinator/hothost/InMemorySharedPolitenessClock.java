package com.hkg.crawler.coordinator.hothost;

import com.hkg.crawler.common.Host;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SharedPolitenessClock} for tests and the single-
 * process simulator. Atomicity comes from
 * {@link ConcurrentHashMap#compute}: the per-host advance is a single
 * critical section, equivalent to the Redis WATCH/MULTI/EXEC semantics
 * in production.
 */
public final class InMemorySharedPolitenessClock implements SharedPolitenessClock {

    private final ConcurrentHashMap<Host, Instant> nextFetchTimes = new ConcurrentHashMap<>();

    @Override
    public ClaimResult tryClaim(Host host, Duration delay, Instant now) {
        // Atomically read-modify-write next_fetch_time[host].
        java.util.concurrent.atomic.AtomicReference<ClaimResult> resultRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        nextFetchTimes.compute(host, (k, current) -> {
            if (current == null || !current.isAfter(now)) {
                Instant newNext = now.plus(delay);
                resultRef.set(new ClaimResult.Granted(newNext));
                return newNext;
            } else {
                resultRef.set(new ClaimResult.Denied(Duration.between(now, current)));
                return current;
            }
        });
        return resultRef.get();
    }

    @Override
    public Optional<Instant> nextFetchTime(Host host) {
        return Optional.ofNullable(nextFetchTimes.get(host));
    }

    @Override
    public void close() { /* no resources */ }
}
