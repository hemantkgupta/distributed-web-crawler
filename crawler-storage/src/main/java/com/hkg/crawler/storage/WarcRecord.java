package com.hkg.crawler.storage;

import com.hkg.crawler.common.CanonicalUrl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One WARC 1.1 record (ISO 28500:2017). Captures a single HTTP fetch
 * exchange — request, response, or revisit (for 304 Not Modified).
 *
 * <p>Construction immutable; serialization to bytes is delegated to
 * {@link WarcWriter}. We don't model every WARC record type; only the
 * ones the crawler emits in normal operation:
 * <ul>
 *   <li>{@link Type#REQUEST}  — outbound HTTP request</li>
 *   <li>{@link Type#RESPONSE} — origin's HTTP response (status+headers+body)</li>
 *   <li>{@link Type#REVISIT}  — for 304 Not Modified responses</li>
 *   <li>{@link Type#METADATA} — operator-injected annotation</li>
 * </ul>
 */
public record WarcRecord(
    Type type,
    UUID recordId,
    CanonicalUrl targetUri,
    Instant warcDate,
    String contentType,
    byte[] payload,
    String payloadDigestSha1,
    String blockDigestSha1,
    Map<String, String> extraHeaders   // optional: WARC-IP-Address, WARC-Concurrent-To, etc.
) {

    public WarcRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(targetUri, "targetUri");
        Objects.requireNonNull(warcDate, "warcDate");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(payload, "payload");
        extraHeaders = Map.copyOf(extraHeaders == null ? Map.of() : extraHeaders);
    }

    public enum Type {
        REQUEST("request"),
        RESPONSE("response"),
        REVISIT("revisit"),
        METADATA("metadata");

        private final String wireForm;
        Type(String wireForm) { this.wireForm = wireForm; }
        public String wireForm() { return wireForm; }
    }

    /**
     * Construct a {@link Type#RESPONSE} record from raw HTTP response
     * bytes (status line + headers + body in HTTP/1.1 wire format).
     * The Fetcher provides these bytes directly.
     */
    public static WarcRecord response(CanonicalUrl url, Instant fetchedAt,
                                       byte[] httpResponseBytes, String responseIp) {
        Map<String, String> extras = new LinkedHashMap<>();
        if (responseIp != null && !responseIp.isEmpty()) {
            extras.put("WARC-IP-Address", responseIp);
        }
        return new WarcRecord(
            Type.RESPONSE,
            UUID.randomUUID(),
            url,
            fetchedAt,
            "application/http; msgtype=response",
            httpResponseBytes,
            sha1(httpResponseBytes),
            sha1(httpResponseBytes),
            extras
        );
    }

    /** Construct a {@link Type#REQUEST} record paired with a response by UUID. */
    public static WarcRecord request(CanonicalUrl url, Instant requestedAt,
                                      byte[] httpRequestBytes, UUID concurrentToResponse) {
        Map<String, String> extras = new LinkedHashMap<>();
        if (concurrentToResponse != null) {
            extras.put("WARC-Concurrent-To", "<urn:uuid:" + concurrentToResponse + ">");
        }
        return new WarcRecord(
            Type.REQUEST,
            UUID.randomUUID(),
            url,
            requestedAt,
            "application/http; msgtype=request",
            httpRequestBytes,
            sha1(httpRequestBytes),
            sha1(httpRequestBytes),
            extras
        );
    }

    static String sha1(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(bytes);
            StringBuilder hex = new StringBuilder("sha1:");
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
