package com.hkg.crawler.parser;

import com.hkg.crawler.common.CanonicalUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JsoupHtmlParserTest {

    private JsoupHtmlParser parser;
    private CanonicalUrl src;

    @BeforeEach
    void setUp() {
        parser = new JsoupHtmlParser();
        src = CanonicalUrl.of("http://example.com/page");
    }

    @Test
    void extracts_title_and_description() {
        ParsedDocument doc = parser.parse(src, """
            <html><head>
              <title>The Title</title>
              <meta name="description" content="A summary.">
            </head><body><p>Body text.</p></body></html>
            """);
        assertThat(doc.title()).isEqualTo("The Title");
        assertThat(doc.description()).isEqualTo("A summary.");
    }

    @Test
    void extracts_canonical_link() {
        ParsedDocument doc = parser.parse(src, """
            <html><head>
              <link rel="canonical" href="https://example.com/canonical">
            </head><body></body></html>
            """);
        assertThat(doc.canonicalUrl()).isPresent();
        assertThat(doc.canonicalUrl().orElseThrow().value())
            .isEqualTo("https://example.com/canonical");
    }

    @Test
    void no_canonical_link_yields_empty_optional() {
        ParsedDocument doc = parser.parse(src,
            "<html><head><title>x</title></head><body></body></html>");
        assertThat(doc.canonicalUrl()).isEmpty();
    }

    @Test
    void noindex_meta_robots_flips_indexable() {
        ParsedDocument doc = parser.parse(src, """
            <html><head>
              <meta name="robots" content="noindex,nofollow">
            </head><body><a href="http://elsewhere.com/x">link</a></body></html>
            """);
        assertThat(doc.isIndexable()).isFalse();
        assertThat(doc.isFollowable()).isFalse();
        // nofollow → outlinks not extracted.
        assertThat(doc.outlinks()).isEmpty();
    }

    @Test
    void noindex_alone_keeps_followable() {
        ParsedDocument doc = parser.parse(src, """
            <html><head>
              <meta name="robots" content="noindex">
            </head><body><a href="http://elsewhere.com/x">link</a></body></html>
            """);
        assertThat(doc.isIndexable()).isFalse();
        assertThat(doc.isFollowable()).isTrue();
        assertThat(doc.outlinks()).hasSize(1);
    }

    @Test
    void resolves_relative_links_against_source_url() {
        ParsedDocument doc = parser.parse(src, """
            <html><body>
              <a href="/about">About</a>
              <a href="../sibling">Sibling</a>
              <a href="https://other.com/page">Other</a>
            </body></html>
            """);
        List<String> urls = doc.outlinks().stream()
            .map(l -> l.targetUrl().value())
            .collect(Collectors.toList());
        assertThat(urls).contains("http://example.com/about");
        assertThat(urls).contains("https://other.com/page");
    }

    @Test
    void filters_non_http_schemes() {
        ParsedDocument doc = parser.parse(src, """
            <html><body>
              <a href="mailto:foo@example.com">Email</a>
              <a href="javascript:void(0)">JS</a>
              <a href="tel:+15555555555">Phone</a>
              <a href="http://valid.com/page">Valid</a>
            </body></html>
            """);
        assertThat(doc.outlinks()).hasSize(1);
        assertThat(doc.outlinks().get(0).targetUrl().value())
            .isEqualTo("http://valid.com/page");
    }

    @Test
    void extracts_anchor_and_rel() {
        ParsedDocument doc = parser.parse(src, """
            <html><body>
              <a href="http://other.com/page" rel="nofollow sponsored">Buy now!</a>
            </body></html>
            """);
        assertThat(doc.outlinks()).hasSize(1);
        ExtractedLink link = doc.outlinks().get(0);
        assertThat(link.anchorText()).isEqualTo("Buy now!");
        assertThat(link.rel()).isEqualTo("nofollow sponsored");
        assertThat(link.isNoFollow()).isTrue();
    }

    @Test
    void attributes_dom_section_correctly() {
        ParsedDocument doc = parser.parse(src, """
            <html><body>
              <header><a href="http://example.com/h">H</a></header>
              <nav><a href="http://example.com/n">N</a></nav>
              <main><a href="http://example.com/m">M</a></main>
              <aside><a href="http://example.com/a">A</a></aside>
              <footer><a href="http://example.com/f">F</a></footer>
            </body></html>
            """);
        var sections = doc.outlinks().stream()
            .collect(Collectors.toMap(
                l -> l.targetUrl().value(),
                ExtractedLink::domSection));
        assertThat(sections.get("http://example.com/h")).isEqualTo(ExtractedLink.DomSection.HEADER);
        assertThat(sections.get("http://example.com/n")).isEqualTo(ExtractedLink.DomSection.NAV);
        assertThat(sections.get("http://example.com/m")).isEqualTo(ExtractedLink.DomSection.MAIN);
        assertThat(sections.get("http://example.com/a")).isEqualTo(ExtractedLink.DomSection.SIDEBAR);
        assertThat(sections.get("http://example.com/f")).isEqualTo(ExtractedLink.DomSection.FOOTER);
    }

    @Test
    void extracts_headings_with_levels_and_ordinals() {
        ParsedDocument doc = parser.parse(src, """
            <html><body>
              <h1>Top</h1>
              <h2>Sub</h2>
              <h2>Sub2</h2>
              <h3>Subsub</h3>
            </body></html>
            """);
        assertThat(doc.headings()).hasSize(4);
        assertThat(doc.headings().get(0).level()).isEqualTo(1);
        assertThat(doc.headings().get(0).text()).isEqualTo("Top");
        assertThat(doc.headings().get(0).ordinal()).isEqualTo(0);
        assertThat(doc.headings().get(2).level()).isEqualTo(2);
        assertThat(doc.headings().get(2).ordinal()).isEqualTo(2);
    }

    @Test
    void boilerplate_stripper_removes_header_and_footer_from_main_text() {
        ParsedDocument doc = parser.parse(src, """
            <html><body>
              <header>SITE NAVIGATION</header>
              <main>
                <p>This is the actual article content readers want.</p>
              </main>
              <footer>COPYRIGHT 2026</footer>
            </body></html>
            """);
        assertThat(doc.mainText()).contains("actual article content");
        assertThat(doc.mainText()).doesNotContain("SITE NAVIGATION");
        assertThat(doc.mainText()).doesNotContain("COPYRIGHT");
    }

    @Test
    void simhash_is_deterministic_and_consistent() {
        String html = "<html><body><p>Hello world this is content.</p></body></html>";
        ParsedDocument doc1 = parser.parse(src, html);
        ParsedDocument doc2 = parser.parse(src, html);
        assertThat(doc1.simhash64()).isEqualTo(doc2.simhash64());
    }

    @Test
    void simhash_differs_substantially_for_different_content() {
        String html1 = "<html><body><p>" + "alpha ".repeat(100) + "</p></body></html>";
        String html2 = "<html><body><p>" + "beta ".repeat(100)  + "</p></body></html>";
        long s1 = parser.parse(src, html1).simhash64();
        long s2 = parser.parse(src, html2).simhash64();
        // Distance must be substantial (≥ 8) for clearly different content.
        assertThat(Simhash64.hammingDistance(s1, s2)).isGreaterThan(8);
    }

    @Test
    void simhash_near_dup_for_similar_content() {
        // Two pages that share most content but differ slightly.
        String common = "The quick brown fox jumps over the lazy dog. ".repeat(20);
        String html1 = "<html><body><p>" + common + "Variant one.</p></body></html>";
        String html2 = "<html><body><p>" + common + "Variant two.</p></body></html>";
        long s1 = parser.parse(src, html1).simhash64();
        long s2 = parser.parse(src, html2).simhash64();
        // Small change in long shared prefix → small Hamming distance.
        assertThat(Simhash64.hammingDistance(s1, s2)).isLessThanOrEqualTo(10);
    }

    @Test
    void empty_body_does_not_throw() {
        ParsedDocument doc = parser.parse(src, "<html><head></head><body></body></html>");
        assertThat(doc.title()).isEmpty();
        assertThat(doc.outlinks()).isEmpty();
        assertThat(doc.mainText()).isEmpty();
    }
}
