package com.hkg.crawler.robots;

import com.hkg.crawler.common.Host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Parsed robots.txt rules for one host.
 *
 * <p>Per RFC 9309:
 * <ul>
 *   <li>Cache TTL should not exceed 24 hours ({@link #DEFAULT_CACHE_TTL}).</li>
 *   <li>If no group matches a specific user-agent token, the {@code *}
 *       group applies as a fallback.</li>
 *   <li>{@code Sitemap:} directives are non-normative "other records"
 *       extracted alongside the rule groups.</li>
 * </ul>
 */
public record RobotsRules(
    Host host,
    Instant fetchedAt,
    Instant expiresAt,
    Map<String, UserAgentGroup> groupsByUserAgent,
    Optional<UserAgentGroup> wildcardGroup,
    List<String> sitemapUrls,
    Source source
) {

    public static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(24);

    public RobotsRules {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(groupsByUserAgent, "groupsByUserAgent");
        Objects.requireNonNull(wildcardGroup, "wildcardGroup");
        Objects.requireNonNull(sitemapUrls, "sitemapUrls");
        Objects.requireNonNull(source, "source");
        groupsByUserAgent = Map.copyOf(groupsByUserAgent);
        sitemapUrls = List.copyOf(sitemapUrls);
    }

    /**
     * Provenance of these rules — important because the RFC 9309
     * 4xx/5xx asymmetry means our handling of the URL depends on
     * whether we got real rules or are inferring from a status code.
     */
    public enum Source {
        /** Fetched from origin and parsed normally. */
        FETCHED,
        /** Origin returned 4xx — robots is "unavailable", crawler may access freely. */
        SYNTHETIC_ALLOW_ALL_4XX,
        /** Origin returned 5xx or network failure — full disallow per RFC. */
        SYNTHETIC_DISALLOW_ALL_5XX,
        /** Cached entry served past TTL during transient origin failure. */
        STALE_SERVE
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /**
     * Return the group matching {@code userAgent}, falling back to the
     * wildcard group if no exact match. Per RFC 9309 §2.2.1, user-agent
     * matching is case-insensitive.
     */
    public Optional<UserAgentGroup> groupFor(String userAgent) {
        String token = userAgent.toLowerCase(java.util.Locale.ROOT);
        UserAgentGroup exact = groupsByUserAgent.get(token);
        if (exact != null) return Optional.of(exact);
        return wildcardGroup;
    }

    /**
     * Decide whether {@code userAgent} may fetch {@code path}.
     *
     * <p>Algorithm (RFC 9309 §2.2.2):
     * <ol>
     *   <li>Find the matching user-agent group (exact case-insensitive,
     *       fallback to {@code *}).</li>
     *   <li>Of all matching {@code Allow:} and {@code Disallow:} rules
     *       in the group, the **most specific (longest match)** wins.</li>
     *   <li>If no rule matches, the URL is allowed.</li>
     * </ol>
     */
    public Verdict isAllowed(String userAgent, String path) {
        // Synthetic 4xx → allow everything regardless of group.
        if (source == Source.SYNTHETIC_ALLOW_ALL_4XX) return Verdict.ALLOWED;
        // Synthetic 5xx → disallow everything.
        if (source == Source.SYNTHETIC_DISALLOW_ALL_5XX) return Verdict.DISALLOWED;

        Optional<UserAgentGroup> grp = groupFor(userAgent);
        if (grp.isEmpty()) return Verdict.ALLOWED;
        UserAgentGroup group = grp.get();

        RobotsRule longest = null;
        for (RobotsRule rule : group.allows()) {
            if (rule.matches(path) && (longest == null || rule.matchLength() > longest.matchLength())) {
                longest = rule;
            }
        }
        for (RobotsRule rule : group.disallows()) {
            if (rule.matches(path) && (longest == null || rule.matchLength() > longest.matchLength())) {
                longest = rule;
            }
        }
        if (longest == null) return Verdict.ALLOWED;
        return longest.type() == RobotsRule.RuleType.ALLOW
            ? Verdict.ALLOWED
            : Verdict.DISALLOWED;
    }

    public enum Verdict { ALLOWED, DISALLOWED }
}
