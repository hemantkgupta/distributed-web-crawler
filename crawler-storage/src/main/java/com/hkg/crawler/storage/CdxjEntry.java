package com.hkg.crawler.storage;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.Host;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a CDXJ index — Common Crawl's lookup format.
 *
 * <p>Wire format:
 *
 * <pre>
 *   &lt;surt&gt; &lt;timestamp&gt; { ...JSON metadata... }
 * </pre>
 *
 * Sorted by SURT, the file is range-scannable for "what was at host X
 * during date range Y." See blog §11.
 */
public record CdxjEntry(
    String surtKey,
    Instant timestamp,
    String url,
    String mimeType,
    int httpStatus,
    String payloadDigest,
    long payloadLength,
    String warcFilename,
    long warcOffset
) {

    private static final DateTimeFormatter TS = DateTimeFormatter
        .ofPattern("yyyyMMddHHmmss")
        .withZone(ZoneOffset.UTC);

    public static CdxjEntry of(CanonicalUrl url, Instant fetchedAt, String mimeType,
                                int httpStatus, String payloadDigest, long payloadLength,
                                String warcFilename, long warcOffset) {
        Host host = url.host();
        // SURT key: comma-reversed host + URL path + query
        StringBuilder sb = new StringBuilder(host.surt()).append(')');
        sb.append(url.path());
        if (url.query() != null) sb.append('?').append(url.query());
        return new CdxjEntry(sb.toString(), fetchedAt, url.value(), mimeType,
            httpStatus, payloadDigest, payloadLength, warcFilename, warcOffset);
    }

    /** Render this entry as a CDXJ line (without trailing newline). */
    public String toCdxjLine() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("url", url);
        metadata.put("mime", mimeType);
        metadata.put("status", String.valueOf(httpStatus));
        metadata.put("digest", payloadDigest);
        metadata.put("length", String.valueOf(payloadLength));
        metadata.put("filename", warcFilename);
        metadata.put("offset", String.valueOf(warcOffset));
        return surtKey + " " + TS.format(timestamp) + " " + simpleJson(metadata);
    }

    /**
     * Minimal JSON serializer for the metadata block — sufficient for
     * the small set of string-valued fields we emit. Avoids pulling in
     * Jackson/Gson for one use site.
     */
    private static String simpleJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\": ");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
