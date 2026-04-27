package com.hkg.crawler.coordinator.hothost;

import com.hkg.crawler.common.Host;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Shared per-host politeness clock for hot hosts whose URL space is
 * split across multiple agents. The owning agent is the source of
 * truth; spillover agents call {@link #tryClaim} to atomically reserve
 * the next fetch slot.
 *
 * <p>Production: a {@code RedisSharedPolitenessClock} backed by Redis
 * with WATCH/MULTI/EXEC for atomic clock advancement (single round-trip
 * per claim). The SPI lets tests use an in-memory implementation.
 *
 * <p>Why this is necessary: per-host politeness must be enforced as a
 * single rate even when fetches are parallelized across agents (blog
 * §3 Problem 4 Recommendation). Without a shared clock, three sibling
 * agents would each independently send 1 req/sec to the host — three
 * times the polite rate.
 */
public interface SharedPolitenessClock extends AutoCloseable {

    /**
     * Atomically: if {@code now ≥ next_fetch_time(host)}, advance the
     * clock by {@code delay} and return success; otherwise return
     * failure with the time remaining until eligibility.
     *
     * <p>Single round-trip semantics in production (Redis Lua); single
     * lock acquisition in the in-memory implementation.
     */
    ClaimResult tryClaim(Host host, Duration delay, Instant now);

    /** Inspect the current next-fetch-time for {@code host}, if any. */
    Optional<Instant> nextFetchTime(Host host);

    @Override
    void close();

    /** Outcome of a claim attempt. */
    sealed interface ClaimResult {
        /** Successfully reserved the slot; {@code newNextFetchTime} is the post-claim clock. */
        record Granted(Instant newNextFetchTime) implements ClaimResult {}
        /** Not yet eligible; caller should wait at least this long before retrying. */
        record Denied(Duration timeUntilEligible) implements ClaimResult {}
    }
}
