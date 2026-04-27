package com.hkg.crawler.parser;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.Host;

import java.util.Objects;

/**
 * One outlink extracted from a parsed page. Captures enough context to
 * (1) feed the Frontier with a canonicalized target URL, (2) populate
 * the link graph with anchor + rel + DOM-section attribution, and
 * (3) drive OPIC importance via the source → target edge.
 */
public record ExtractedLink(
    CanonicalUrl sourceUrl,
    CanonicalUrl targetUrl,
    String anchorText,
    String rel,
    DomSection domSection
) {
    public ExtractedLink {
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(anchorText, "anchorText");
        Objects.requireNonNull(rel, "rel");
        Objects.requireNonNull(domSection, "domSection");
    }

    public Host targetHost() { return targetUrl.host(); }

    /**
     * Where in the parsed DOM the link appeared. Used by the link-graph
     * builder and importance scorer to weight content links above
     * navigation/footer links.
     */
    public enum DomSection {
        UNKNOWN,
        HEADER,
        MAIN,
        FOOTER,
        SIDEBAR,
        NAV
    }

    /**
     * Convenience: is this link {@code rel="nofollow"} or similar?
     * Frontier still admits the URL but the link-graph signal is suppressed.
     */
    public boolean isNoFollow() {
        if (rel.isEmpty()) return false;
        for (String token : rel.toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
            if (token.equals("nofollow") || token.equals("sponsored")) return true;
        }
        return false;
    }
}
