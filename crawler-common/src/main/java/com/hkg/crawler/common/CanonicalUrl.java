package com.hkg.crawler.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * A URL after RFC-3986-safe canonicalization. Used as the primary key in
 * the URL Frontier, the URL dedup store, and downstream indexes.
 *
 * <p>Canonicalization rules applied at construction (RFC 3986 §6):
 * <ul>
 *   <li>Lowercase scheme</li>
 *   <li>Lowercase host</li>
 *   <li>Strip default port (HTTP 80, HTTPS 443)</li>
 *   <li>Empty path → "/"</li>
 *   <li>Remove dot segments from path ("/a/./b/../c" → "/a/c")</li>
 *   <li>Uppercase percent-encoding hex digits</li>
 *   <li>Drop fragment ("#section") — fragments are client-side only,
 *       never sent to servers, so they're irrelevant to identity</li>
 * </ul>
 *
 * <p>Transformations <em>NOT</em> applied (require site-learned evidence):
 * <ul>
 *   <li>Trailing-slash collapse ("/page" vs "/page/")</li>
 *   <li>Query parameter sorting</li>
 *   <li>Session-ID parameter stripping (PHPSESSID, jsessionid)</li>
 *   <li>Removing index.html / default.aspx</li>
 * </ul>
 *
 * <p>Site-learned canonicalization is the responsibility of a separate
 * pass that observes redirect chains and {@code <link rel="canonical">}
 * tags before applying.
 */
public final class CanonicalUrl {

    private final String value;
    private final Host host;
    private final String scheme;
    private final String path;
    private final String query;       // null if absent
    private final int port;           // -1 if default

    private CanonicalUrl(String value, Host host, String scheme, String path,
                         String query, int port) {
        this.value = value;
        this.host = host;
        this.scheme = scheme;
        this.path = path;
        this.query = query;
        this.port = port;
    }

    /**
     * Canonicalize a URL string. The input must be an absolute URL
     * (with scheme and authority) or this method throws.
     *
     * @throws IllegalArgumentException if the URL is malformed, relative,
     *         or uses a scheme other than http/https
     */
    public static CanonicalUrl of(String raw) {
        Objects.requireNonNull(raw, "url must not be null");
        URI uri;
        try {
            uri = new URI(raw.trim()).normalize();   // RFC 3986 normalize
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("malformed URL: " + raw, e);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("URL must be absolute: " + raw);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                "only http(s) supported, got: " + scheme);
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("URL missing host: " + raw);
        }
        Host host = Host.of(uri.getHost());

        int port = uri.getPort();
        if (port == defaultPort(scheme)) port = -1;

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";

        String query = uri.getRawQuery();   // null if absent
        // fragment is intentionally dropped

        String canonical = rebuild(scheme, host, port, path, query);
        return new CanonicalUrl(canonical, host, scheme, path, query, port);
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http"  -> 80;
            case "https" -> 443;
            default      -> -1;
        };
    }

    private static String rebuild(String scheme, Host host, int port,
                                   String path, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host.value());
        if (port != -1) sb.append(':').append(port);
        sb.append(path);
        if (query != null) sb.append('?').append(query);
        return sb.toString();
    }

    public String value()  { return value; }
    public Host host()     { return host; }
    public String scheme() { return scheme; }
    public String path()   { return path; }
    public String query()  { return query; }
    public int port()      { return port; }

    @Override
    public boolean equals(Object o) {
        return o instanceof CanonicalUrl c && value.equals(c.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
