package com.hkg.crawler.parser;

import com.hkg.crawler.common.CanonicalUrl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * jsoup-based {@link HtmlParser}. Implements the §8 Parser pipeline
 * from the architectural blueprint:
 *
 * <ol>
 *   <li>Lenient HTML parse via jsoup.</li>
 *   <li>Meta-robots inspection — sets {@code isIndexable} /
 *       {@code isFollowable} flags.</li>
 *   <li>{@code <link rel="canonical">} extraction.</li>
 *   <li>Boilerplate stripping ({@link BoilerplateStripper}) to produce
 *       {@code mainText}.</li>
 *   <li>Outlink extraction with anchor + rel + DOM-section attribution.</li>
 *   <li>Heading hierarchy (h1–h6) capture.</li>
 *   <li>64-bit Simhash pre-compute over the main text
 *       ({@link Simhash64}).</li>
 * </ol>
 */
public final class JsoupHtmlParser implements HtmlParser {

    private final BoilerplateStripper stripper;
    private final Simhash64 simhash;

    public JsoupHtmlParser() {
        this(new BoilerplateStripper(), new Simhash64());
    }

    public JsoupHtmlParser(BoilerplateStripper stripper, Simhash64 simhash) {
        this.stripper = stripper;
        this.simhash  = simhash;
    }

    @Override
    public ParsedDocument parse(CanonicalUrl sourceUrl, String html) {
        Document doc = Jsoup.parse(html, sourceUrl.value());

        // 1. Meta robots flags.
        boolean isIndexable  = true;
        boolean isFollowable = true;
        for (Element meta : doc.select("meta[name=robots]")) {
            String content = meta.attr("content").toLowerCase(Locale.ROOT);
            if (content.contains("noindex"))  isIndexable  = false;
            if (content.contains("nofollow")) isFollowable = false;
        }

        // 2. Canonical link.
        Optional<CanonicalUrl> canonical = Optional.empty();
        Element canonicalEl = doc.selectFirst("link[rel=canonical]");
        if (canonicalEl != null) {
            String absHref = canonicalEl.absUrl("href");
            if (!absHref.isEmpty()) {
                try {
                    canonical = Optional.of(CanonicalUrl.of(absHref));
                } catch (IllegalArgumentException ignored) {
                    // Malformed canonical URL — silently skip.
                }
            }
        }

        // 3. Title + description.
        String title = doc.title();
        String description = "";
        Element descEl = doc.selectFirst("meta[name=description]");
        if (descEl != null) description = descEl.attr("content");

        // 4. Headings.
        List<ParsedDocument.Heading> headings = extractHeadings(doc);

        // 5. Outlinks (only if followable).
        List<ExtractedLink> outlinks = isFollowable
            ? extractOutlinks(doc, sourceUrl)
            : List.of();

        // 6. Main text + Simhash.
        String mainText = stripper.stripBoilerplate(doc);
        long simhash64 = simhash.compute(mainText);

        return new ParsedDocument(
            sourceUrl,
            canonical,
            title,
            mainText,
            description,
            headings,
            outlinks,
            simhash64,
            html.length(),
            isIndexable,
            isFollowable
        );
    }

    private List<ParsedDocument.Heading> extractHeadings(Document doc) {
        List<ParsedDocument.Heading> headings = new ArrayList<>();
        Elements all = doc.select("h1, h2, h3, h4, h5, h6");
        int ordinal = 0;
        for (Element h : all) {
            int level = Integer.parseInt(h.tagName().substring(1));
            String text = h.text();
            if (text.isEmpty()) continue;
            headings.add(new ParsedDocument.Heading(level, text, ordinal++));
        }
        return headings;
    }

    private List<ExtractedLink> extractOutlinks(Document doc, CanonicalUrl source) {
        List<ExtractedLink> links = new ArrayList<>();
        Elements anchors = doc.select("a[href]");
        for (Element a : anchors) {
            String absHref = a.absUrl("href");
            if (absHref.isEmpty()) continue;

            // Filter to http(s) schemes; CanonicalUrl will throw otherwise.
            CanonicalUrl target;
            try {
                target = CanonicalUrl.of(absHref);
            } catch (IllegalArgumentException e) {
                continue;
            }

            String anchor = a.text();
            String rel = a.attr("rel");
            ExtractedLink.DomSection section = pickDomSection(a);
            links.add(new ExtractedLink(source, target, anchor, rel, section));
        }
        return links;
    }

    /**
     * Walk up the DOM looking for known semantic ancestors. The first
     * match wins; otherwise UNKNOWN.
     */
    private ExtractedLink.DomSection pickDomSection(Element el) {
        Element cursor = el;
        while (cursor != null) {
            String tag = cursor.tagName().toLowerCase(Locale.ROOT);
            String role = cursor.attr("role").toLowerCase(Locale.ROOT);
            switch (tag) {
                case "header" -> { return ExtractedLink.DomSection.HEADER; }
                case "footer" -> { return ExtractedLink.DomSection.FOOTER; }
                case "nav"    -> { return ExtractedLink.DomSection.NAV; }
                case "aside"  -> { return ExtractedLink.DomSection.SIDEBAR; }
                case "main", "article" -> { return ExtractedLink.DomSection.MAIN; }
            }
            switch (role) {
                case "banner"        -> { return ExtractedLink.DomSection.HEADER; }
                case "navigation"    -> { return ExtractedLink.DomSection.NAV; }
                case "complementary" -> { return ExtractedLink.DomSection.SIDEBAR; }
                case "contentinfo"   -> { return ExtractedLink.DomSection.FOOTER; }
                case "main"          -> { return ExtractedLink.DomSection.MAIN; }
            }
            cursor = cursor.parent();
        }
        return ExtractedLink.DomSection.UNKNOWN;
    }
}
