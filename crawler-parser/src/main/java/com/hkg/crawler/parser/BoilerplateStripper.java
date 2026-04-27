package com.hkg.crawler.parser;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Readability-style boilerplate removal: extract the page's main content
 * by preferring semantic content blocks ({@code <article>}, {@code <main>})
 * and stripping headers / footers / navigation / comment regions.
 *
 * <p>This is a deliberately small implementation — sufficient for stable
 * Simhash fingerprinting. A production crawler may want a richer
 * algorithm (Mozilla Readability, trafilatura) but the failure mode this
 * class exists to prevent — templated boilerplate dominating the
 * fingerprint and producing false near-dup merges — is solved by even a
 * naïve heuristic.
 */
public final class BoilerplateStripper {

    /**
     * Return the main text content of {@code doc} with header / footer /
     * sidebar / nav / comments removed.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Find the most-specific main-content container by trying
     *       {@code <article>}, {@code <main>}, {@code [role=main]}, and
     *       finally {@code <body>} — in that priority order.</li>
     *   <li>From that container, remove known boilerplate elements
     *       (header, footer, nav, aside, scripts, styles).</li>
     *   <li>Return the remaining text content, normalized.</li>
     * </ol>
     */
    public String stripBoilerplate(Document doc) {
        Element root = pickRoot(doc);
        // Defensive: clone to avoid mutating the caller's DOM.
        Element working = root.clone();

        // Remove obvious non-content elements.
        for (String selector : new String[] {
            "header", "footer", "nav", "aside",
            "script", "style", "noscript", "template",
            "[role=navigation]", "[role=banner]", "[role=complementary]",
            ".ad", ".ads", ".advertisement", ".comments", "#comments"
        }) {
            Elements matches = working.select(selector);
            for (Element e : matches) e.remove();
        }

        return normalizeText(working.text());
    }

    private Element pickRoot(Document doc) {
        Element article = doc.selectFirst("article");
        if (article != null) return article;
        Element main = doc.selectFirst("main");
        if (main != null) return main;
        Element roleMain = doc.selectFirst("[role=main]");
        if (roleMain != null) return roleMain;
        return doc.body() != null ? doc.body() : doc;
    }

    private String normalizeText(String text) {
        // jsoup's .text() already collapses whitespace; one more pass to be safe.
        return text.replaceAll("\\s+", " ").trim();
    }
}
