package com.hkg.crawler.parser;

import com.hkg.crawler.common.CanonicalUrl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Output of the HTML parser — the structured representation of one
 * fetched page. Feeds three downstream streams (per blog §8): the
 * document stream (search index), the link stream (graph + frontier
 * recursion), and the dedup stream (Simhash for content near-dup).
 */
public record ParsedDocument(
    CanonicalUrl url,
    Optional<CanonicalUrl> canonicalUrl,    // from <link rel="canonical">
    String title,
    String mainText,                         // boilerplate-stripped
    String description,                      // <meta name="description">
    List<Heading> headings,
    List<ExtractedLink> outlinks,
    long simhash64,                          // 64-bit content fingerprint over mainText
    int  domSizeBytes,
    boolean isIndexable,                     // false if <meta robots noindex>
    boolean isFollowable                     // false if <meta robots nofollow>
) {
    public ParsedDocument {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(mainText, "mainText");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(headings, "headings");
        Objects.requireNonNull(outlinks, "outlinks");
        headings = List.copyOf(headings);
        outlinks = List.copyOf(outlinks);
    }

    public record Heading(int level, String text, int ordinal) {
        public Heading {
            if (level < 1 || level > 6) {
                throw new IllegalArgumentException("heading level must be 1-6");
            }
            Objects.requireNonNull(text, "text");
        }
    }
}
