package com.hkg.crawler.indexer;

import com.hkg.crawler.common.CanonicalUrl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * High-level façade over the three indexer streams. The Parser and
 * Fetcher call into this; it serializes events and routes them to the
 * configured {@link MessagePublisher}.
 *
 * <p>Per blog §12: continuous incremental indexing — every parsed
 * document, every extracted link, every fetch event flows through here
 * to the downstream consumers. The Caffeine lesson: freshness only
 * helps if the index can absorb it.
 *
 * <p>Wire format: simple line-oriented {@code key=value} pairs encoded
 * as UTF-8 bytes. Production deployments would use Protobuf for stable
 * binary contracts; tests can decode the simple format directly.
 */
public final class IndexerPipeline {

    private final MessagePublisher publisher;

    public IndexerPipeline(MessagePublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Emit a parsed-document event. Called by the Parser after dedup
     * verdict + boilerplate strip.
     */
    public void emitDocument(CanonicalUrl url, String title, String mainText,
                              long simhash64, Instant fetchedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", "document");
        fields.put("url", url.value());
        fields.put("host", url.host().value());
        fields.put("title", truncate(title, 256));
        fields.put("text_length", String.valueOf(mainText == null ? 0 : mainText.length()));
        fields.put("simhash64", Long.toUnsignedString(simhash64, 16));
        fields.put("fetched_at", fetchedAt.toString());
        publisher.publish(IndexerStream.DOCUMENTS, encode(fields));
    }

    /**
     * Emit one extracted-link edge. Called by the Parser per outlink.
     */
    public void emitLink(CanonicalUrl source, CanonicalUrl target,
                          String anchorText, String rel, String domSection) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", "link");
        fields.put("source_url", source.value());
        fields.put("target_url", target.value());
        fields.put("source_host", source.host().value());
        fields.put("target_host", target.host().value());
        fields.put("anchor", truncate(anchorText, 200));
        fields.put("rel", rel == null ? "" : rel);
        fields.put("dom_section", domSection == null ? "UNKNOWN" : domSection);
        publisher.publish(IndexerStream.LINKS, encode(fields));
    }

    /**
     * Emit a per-fetch operational event. Called by the Fetcher after
     * each verdict for observability and analytics (ClickHouse).
     */
    public void emitOperational(CanonicalUrl url, int httpStatus, long fetchDurationMs,
                                 long bodySizeBytes, String contentType,
                                 boolean conditionalUsed, boolean conditionalMatched,
                                 Instant fetchedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", "fetch");
        fields.put("url", url.value());
        fields.put("host", url.host().value());
        fields.put("http_status", String.valueOf(httpStatus));
        fields.put("fetch_duration_ms", String.valueOf(fetchDurationMs));
        fields.put("body_size_bytes", String.valueOf(bodySizeBytes));
        fields.put("content_type", contentType == null ? "" : contentType);
        fields.put("conditional_used", String.valueOf(conditionalUsed));
        fields.put("conditional_matched", String.valueOf(conditionalMatched));
        fields.put("fetched_at", fetchedAt.toString());
        publisher.publish(IndexerStream.OPERATIONAL, encode(fields));
    }

    public void flush() { publisher.flush(); }

    public MessagePublisher publisher() { return publisher; }

    // ---- internals -----------------------------------------------------

    private static byte[] encode(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) sb.append(' ');
            first = false;
            sb.append(e.getKey()).append('=').append(escape(e.getValue()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        // Replace literal newlines + double-quotes; wrap in quotes if needed.
        if (s.indexOf(' ') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
        }
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
