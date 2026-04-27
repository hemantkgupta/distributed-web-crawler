package com.hkg.crawler.robots;

import com.hkg.crawler.common.Host;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process cache of parsed robots.txt rules with the **load-bearing**
 * RFC 9309 4xx/5xx asymmetry handling.
 *
 * <p>On origin response:
 * <ul>
 *   <li><b>2xx</b> → parse normally; cache for 24 hours.</li>
 *   <li><b>3xx</b> → followed by the {@link RobotsFetcher}; if
 *       redirect-limit hit, treated as unreachable (5xx).</li>
 *   <li><b>4xx</b> → robots is "unavailable"; cache a synthetic
 *       allow-everything ruleset for 24 hours.</li>
 *   <li><b>5xx or network failure</b> → robots is "unreachable"; cache a
 *       synthetic disallow-everything ruleset for 1 hour (shorter so we
 *       retry sooner if the host recovers).</li>
 * </ul>
 *
 * <p>The asymmetry is the most often-misread part of RFC 9309. Reversing
 * it — treating 5xx as "go ahead" — is the bug that gets crawlers
 * blocked across an entire AS.
 *
 * <p>Stale-serve: if a refresh attempt fails on a host whose cached
 * entry is past 24 hours but less than 7 days old, serve the stale
 * entry rather than fall back to disallow-all. Better than full
 * blackout for sites whose robots.txt is briefly flapping.
 *
 * <p>Thread-safe; stateless aside from the cache map.
 */
public final class RobotsCache {

    private static final Duration CACHE_TTL_SUCCESS = Duration.ofHours(24);
    private static final Duration CACHE_TTL_5XX     = Duration.ofHours(1);
    private static final Duration STALE_SERVE_LIMIT = Duration.ofDays(7);

    private final RobotsFetcher  fetcher;
    private final RobotsParser   parser;
    private final Clock          clock;
    private final ConcurrentHashMap<Host, RobotsRules> cache = new ConcurrentHashMap<>();

    public RobotsCache(RobotsFetcher fetcher) {
        this(fetcher, new RobotsParser(), Clock.systemUTC());
    }

    public RobotsCache(RobotsFetcher fetcher, RobotsParser parser, Clock clock) {
        this.fetcher = fetcher;
        this.parser  = parser;
        this.clock   = clock;
    }

    /**
     * Get parsed rules for {@code host}, fetching from origin if not
     * cached or expired. The returned future never throws — it always
     * completes with a {@link RobotsRules} (synthetic if origin fetch
     * fails).
     */
    public CompletableFuture<RobotsRules> rulesFor(Host host) {
        Instant now = clock.instant();
        RobotsRules cached = cache.get(host);
        if (cached != null && !cached.isExpired(now)) {
            return CompletableFuture.completedFuture(cached);
        }
        return refresh(host, cached, now);
    }

    /**
     * Decide whether {@code userAgent} may fetch {@code path} on {@code host}.
     * Convenience wrapper that ties cache lookup + verdict together.
     */
    public CompletableFuture<RobotsRules.Verdict> isAllowed(Host host, String userAgent, String path) {
        return rulesFor(host).thenApply(rules -> rules.isAllowed(userAgent, path));
    }

    public Optional<RobotsRules> peek(Host host) {
        return Optional.ofNullable(cache.get(host));
    }

    public void invalidate(Host host) {
        cache.remove(host);
    }

    public Map<Host, RobotsRules> snapshot() {
        return Map.copyOf(cache);
    }

    // ---- internals -----------------------------------------------------

    private CompletableFuture<RobotsRules> refresh(Host host, RobotsRules previousCached, Instant now) {
        return fetcher.fetch(host).thenApply(resp -> {
            RobotsRules result;
            if (resp.isSuccess()) {
                result = parser.parse(host, resp.body(), now, CACHE_TTL_SUCCESS);
            } else if (resp.is4xx()) {
                // RFC 9309: 4xx → robots is unavailable → fetch is allowed.
                result = synthetic(host, now, CACHE_TTL_SUCCESS,
                    RobotsRules.Source.SYNTHETIC_ALLOW_ALL_4XX);
            } else if (resp.is5xx() || resp.isNetworkError()) {
                // RFC 9309: 5xx → robots is unreachable → full disallow.
                // Stale-serve fallback: if we have a cached entry less than
                // 7 days old, serve it with the stale-serve marker.
                if (previousCached != null
                        && previousCached.source() == RobotsRules.Source.FETCHED
                        && Duration.between(previousCached.fetchedAt(), now).compareTo(STALE_SERVE_LIMIT) < 0) {
                    result = new RobotsRules(
                        previousCached.host(),
                        previousCached.fetchedAt(),
                        now.plus(CACHE_TTL_5XX),
                        previousCached.groupsByUserAgent(),
                        previousCached.wildcardGroup(),
                        previousCached.sitemapUrls(),
                        RobotsRules.Source.STALE_SERVE
                    );
                } else {
                    result = synthetic(host, now, CACHE_TTL_5XX,
                        RobotsRules.Source.SYNTHETIC_DISALLOW_ALL_5XX);
                }
            } else {
                // 1xx, unexpected codes — treat as 5xx for safety.
                result = synthetic(host, now, CACHE_TTL_5XX,
                    RobotsRules.Source.SYNTHETIC_DISALLOW_ALL_5XX);
            }
            cache.put(host, result);
            return result;
        });
    }

    private RobotsRules synthetic(Host host, Instant now, Duration ttl, RobotsRules.Source source) {
        return new RobotsRules(
            host,
            now,
            now.plus(ttl),
            Map.of(),
            Optional.empty(),
            java.util.List.of(),
            source
        );
    }
}
