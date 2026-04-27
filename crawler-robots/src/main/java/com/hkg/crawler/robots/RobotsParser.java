package com.hkg.crawler.robots;

import com.hkg.crawler.common.Host;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * RFC 9309 robots.txt parser.
 *
 * <p>Implements the normative parts of the spec:
 * <ul>
 *   <li>Group matching by user-agent token (case-insensitive).</li>
 *   <li>Repeated groups for the same UA combined into one group.</li>
 *   <li>{@code Allow:} / {@code Disallow:} rules collected per group.</li>
 *   <li>Empty {@code Disallow:} treated as "allow everything" (no-op).</li>
 *   <li>{@code Sitemap:} extraction as non-normative records.</li>
 *   <li>{@code Crawl-delay:} extraction as a non-normative advisory.</li>
 *   <li>Comments ({@code #}) stripped.</li>
 *   <li>Body cap of 500 KiB (RFC 9309 minimum parser size); excess bytes
 *       are silently truncated by the caller before invoking us.</li>
 * </ul>
 *
 * <p>The 4xx/5xx asymmetry is enforced by the surrounding cache, not the
 * parser — see {@code RobotsCache.parseStatusResponse()}.
 */
public final class RobotsParser {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /** Parse a fetched robots.txt body into structured rules. */
    public RobotsRules parse(Host host, String body, Instant fetchedAt) {
        return parse(host, body, fetchedAt, DEFAULT_TTL);
    }

    public RobotsRules parse(Host host, String body, Instant fetchedAt, Duration cacheTtl) {
        Instant expiresAt = fetchedAt.plus(cacheTtl);
        List<String> sitemaps = new ArrayList<>();

        // First pass: split into logical groups. A group starts at one or
        // more contiguous user-agent lines and ends at the next user-agent
        // line preceded by a non-user-agent line.
        List<String[]> lines = preprocess(body);
        List<RawGroup> rawGroups = splitIntoGroups(lines, sitemaps);

        // Second pass: merge groups that share user-agent tokens.
        Map<String, MergedGroup> merged = new LinkedHashMap<>();
        Optional<UserAgentGroup> wildcard = Optional.empty();

        for (RawGroup g : rawGroups) {
            for (String agent : g.userAgents) {
                String token = agent.toLowerCase(Locale.ROOT);
                MergedGroup target = merged.computeIfAbsent(token, MergedGroup::new);
                target.allows.addAll(g.allows);
                target.disallows.addAll(g.disallows);
                if (g.crawlDelay.isPresent() && target.crawlDelay.isEmpty()) {
                    target.crawlDelay = g.crawlDelay;
                }
            }
        }

        // Materialize.
        Map<String, UserAgentGroup> groupsByUa = new HashMap<>();
        MergedGroup wc = merged.remove("*");
        if (wc != null) {
            wildcard = Optional.of(wc.toUserAgentGroup());
        }
        for (Map.Entry<String, MergedGroup> e : merged.entrySet()) {
            groupsByUa.put(e.getKey(), e.getValue().toUserAgentGroup());
        }

        return new RobotsRules(host, fetchedAt, expiresAt,
            groupsByUa, wildcard, sitemaps, RobotsRules.Source.FETCHED);
    }

    // ------ helpers -----------------------------------------------------

    /** Strip comments + trim; return [directive_lower, value]. */
    private List<String[]> preprocess(String body) {
        List<String[]> out = new ArrayList<>();
        for (String raw : body.split("\\r?\\n", -1)) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                out.add(null);   // blank line marker
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;   // malformed; skip per RFC tolerance
            String directive = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            out.add(new String[] { directive, value });
        }
        return out;
    }

    private String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private List<RawGroup> splitIntoGroups(List<String[]> lines, List<String> sitemapsOut) {
        List<RawGroup> groups = new ArrayList<>();
        RawGroup current = null;
        boolean expectingNewUaSet = true;

        for (String[] line : lines) {
            if (line == null) continue;   // blank line — does not terminate group
            String d = line[0];
            String v = line[1];

            if (d.equals("sitemap")) {
                sitemapsOut.add(v);
                continue;
            }

            if (d.equals("user-agent")) {
                if (expectingNewUaSet) {
                    current = new RawGroup();
                    groups.add(current);
                    expectingNewUaSet = false;
                }
                current.userAgents.add(v);
                continue;
            }

            // Non-UA directive: ends the user-agent block; subsequent
            // user-agent lines start a new group.
            expectingNewUaSet = true;
            if (current == null) continue;   // directive before any UA — skip

            switch (d) {
                case "allow" -> {
                    if (!v.isEmpty()) current.allows.add(new RobotsRule(v, RobotsRule.RuleType.ALLOW));
                }
                case "disallow" -> {
                    // Empty disallow == allow everything; record nothing.
                    if (!v.isEmpty()) {
                        current.disallows.add(new RobotsRule(v, RobotsRule.RuleType.DISALLOW));
                    }
                }
                case "crawl-delay" -> {
                    try {
                        current.crawlDelay = Optional.of(Duration.ofSeconds(Long.parseLong(v)));
                    } catch (NumberFormatException ignored) {
                        // ignore malformed crawl-delay
                    }
                }
                default -> { /* ignore unknown directives per RFC tolerance */ }
            }
        }
        return groups;
    }

    private static final class RawGroup {
        final List<String> userAgents = new ArrayList<>();
        final List<RobotsRule> allows = new ArrayList<>();
        final List<RobotsRule> disallows = new ArrayList<>();
        Optional<Duration> crawlDelay = Optional.empty();
    }

    private static final class MergedGroup {
        final String token;
        final List<RobotsRule> allows = new ArrayList<>();
        final List<RobotsRule> disallows = new ArrayList<>();
        Optional<Duration> crawlDelay = Optional.empty();

        MergedGroup(String token) { this.token = token; }

        UserAgentGroup toUserAgentGroup() {
            return new UserAgentGroup(Set.of(token), allows, disallows, crawlDelay);
        }
    }
}
