package com.hkg.crawler.robots;

import com.hkg.crawler.common.Host;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsParserTest {

    private final RobotsParser parser = new RobotsParser();
    private final Host host = Host.of("example.com");
    private final Instant t0 = Instant.parse("2026-04-27T12:00:00Z");

    private RobotsRules parse(String body) {
        return parser.parse(host, body, t0);
    }

    @Test
    void simple_disallow_for_wildcard() {
        RobotsRules r = parse("""
            User-agent: *
            Disallow: /private/
            """);
        assertThat(r.isAllowed("MyBot", "/private/secret")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
        assertThat(r.isAllowed("MyBot", "/public/page")).isEqualTo(RobotsRules.Verdict.ALLOWED);
    }

    @Test
    void empty_disallow_means_allow_everything() {
        RobotsRules r = parse("""
            User-agent: *
            Disallow:
            """);
        assertThat(r.isAllowed("MyBot", "/anywhere")).isEqualTo(RobotsRules.Verdict.ALLOWED);
    }

    @Test
    void no_robots_means_allow_everything() {
        RobotsRules r = parse("");
        assertThat(r.isAllowed("MyBot", "/anywhere")).isEqualTo(RobotsRules.Verdict.ALLOWED);
    }

    @Test
    void user_agent_match_is_case_insensitive() {
        RobotsRules r = parse("""
            User-agent: MyBot
            Disallow: /admin/
            """);
        // "MYBOT" should match the "MyBot" group.
        assertThat(r.isAllowed("MYBOT", "/admin/users")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
        // "mybot" too.
        assertThat(r.isAllowed("mybot", "/admin/users")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void path_match_is_case_sensitive() {
        RobotsRules r = parse("""
            User-agent: *
            Disallow: /Private/
            """);
        // The path "/Private/" matches; "/private/" does NOT (case-sensitive).
        assertThat(r.isAllowed("MyBot", "/Private/data")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
        assertThat(r.isAllowed("MyBot", "/private/data")).isEqualTo(RobotsRules.Verdict.ALLOWED);
    }

    @Test
    void longest_match_wins() {
        // Most specific (longest) rule should win — RFC 9309 §2.2.2.
        RobotsRules r = parse("""
            User-agent: *
            Disallow: /a/
            Allow: /a/b/
            """);
        // /a/b/page → matches both rules; "/a/b/" is longer than "/a/" → ALLOW
        assertThat(r.isAllowed("MyBot", "/a/b/page")).isEqualTo(RobotsRules.Verdict.ALLOWED);
        // /a/c/page → only /a/ matches → DISALLOW
        assertThat(r.isAllowed("MyBot", "/a/c/page")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void exact_user_agent_overrides_wildcard() {
        RobotsRules r = parse("""
            User-agent: *
            Disallow: /

            User-agent: MyBot
            Disallow: /private/
            """);
        // Wildcard says no fetch; MyBot's specific group says only /private/ disallowed.
        assertThat(r.isAllowed("MyBot", "/anything")).isEqualTo(RobotsRules.Verdict.ALLOWED);
        assertThat(r.isAllowed("OtherBot", "/anything")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void repeated_groups_for_same_ua_combined() {
        RobotsRules r = parse("""
            User-agent: MyBot
            Disallow: /a/

            User-agent: MyBot
            Disallow: /b/
            """);
        assertThat(r.isAllowed("MyBot", "/a/x")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
        assertThat(r.isAllowed("MyBot", "/b/x")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
        assertThat(r.isAllowed("MyBot", "/c/x")).isEqualTo(RobotsRules.Verdict.ALLOWED);
    }

    @Test
    void multiple_uas_in_one_group() {
        RobotsRules r = parse("""
            User-agent: BotA
            User-agent: BotB
            Disallow: /private/
            """);
        assertThat(r.isAllowed("BotA", "/private/x")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
        assertThat(r.isAllowed("BotB", "/private/x")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
        assertThat(r.isAllowed("BotC", "/private/x")).isEqualTo(RobotsRules.Verdict.ALLOWED); // no group
    }

    @Test
    void sitemap_directive_extracted_outside_groups() {
        RobotsRules r = parse("""
            User-agent: *
            Disallow: /private/
            Sitemap: https://example.com/sitemap.xml
            Sitemap: https://example.com/sitemap-news.xml
            """);
        assertThat(r.sitemapUrls()).containsExactly(
            "https://example.com/sitemap.xml",
            "https://example.com/sitemap-news.xml");
    }

    @Test
    void crawl_delay_extracted_as_advisory() {
        RobotsRules r = parse("""
            User-agent: MyBot
            Crawl-delay: 10
            Disallow: /private/
            """);
        assertThat(r.groupFor("MyBot")).isPresent();
        assertThat(r.groupFor("MyBot").orElseThrow().crawlDelay())
            .hasValue(java.time.Duration.ofSeconds(10));
    }

    @Test
    void comments_stripped() {
        RobotsRules r = parse("""
            # This is the example.com robots.txt
            User-agent: *  # apply to everyone
            Disallow: /private/  # private area
            """);
        assertThat(r.isAllowed("MyBot", "/private/x")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void unknown_directives_ignored() {
        RobotsRules r = parse("""
            User-agent: *
            Disallow: /private/
            Frobnicate: yes
            Bizarre-thing: 42
            """);
        assertThat(r.isAllowed("MyBot", "/private/x")).isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void source_is_FETCHED_for_normal_parse() {
        RobotsRules r = parse("User-agent: *\nDisallow: /\n");
        assertThat(r.source()).isEqualTo(RobotsRules.Source.FETCHED);
    }
}
