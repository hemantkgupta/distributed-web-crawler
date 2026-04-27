package com.hkg.crawler.robots;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One user-agent group from a parsed robots.txt: the set of UA tokens
 * the group applies to (case-insensitive) plus its allow/disallow rules
 * and optional non-normative {@code Crawl-delay:}.
 *
 * <p>Per RFC 9309 §2.2.1, repeated groups for the same UA token must be
 * combined; the parser handles that and surfaces a single group here.
 */
public record UserAgentGroup(
    Set<String> userAgentTokens,
    List<RobotsRule> allows,
    List<RobotsRule> disallows,
    Optional<Duration> crawlDelay
) {
    public UserAgentGroup {
        userAgentTokens = Set.copyOf(userAgentTokens);
        allows = List.copyOf(allows);
        disallows = List.copyOf(disallows);
    }
}
