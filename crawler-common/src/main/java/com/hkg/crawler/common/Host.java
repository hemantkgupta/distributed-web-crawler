package com.hkg.crawler.common;

import java.util.Objects;

/**
 * Authority component of a URL — the host name itself, normalized.
 *
 * <p>Used as the primary sharding key in the consistent-hash routing layer
 * (see Coordinator) and as the politeness key in the Frontier's per-host
 * back queues. Two URLs with the same {@code Host} share politeness state
 * and are routed to the same agent.
 *
 * <p>Normalization rules applied at construction (per RFC 3986 §6.2.2):
 * <ul>
 *   <li>Lowercased ASCII (host names are case-insensitive)</li>
 *   <li>Trailing dot ("example.com.") removed</li>
 *   <li>IDN hostnames must be Punycode-encoded by the caller before
 *       construction; this class does not perform IDN-to-ASCII conversion.</li>
 * </ul>
 */
public final class Host {

    private final String value;

    private Host(String value) {
        this.value = value;
    }

    /**
     * Construct a {@code Host} from an authority string. Applies the
     * RFC-3986-safe normalizations described in the class javadoc.
     *
     * @throws IllegalArgumentException if the input is null, empty, or
     *         contains characters outside the host-name production.
     */
    public static Host of(String raw) {
        Objects.requireNonNull(raw, "host must not be null");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("host must not be empty");
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        validate(lower);
        return new Host(lower);
    }

    private static void validate(String s) {
        // Minimal RFC 1123 host validation: labels of [a-z0-9-] separated by '.',
        // labels do not start or end with '-'. We do not validate IDN here.
        if (s.length() > 253) {
            throw new IllegalArgumentException("host exceeds 253 chars: " + s);
        }
        for (String label : s.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) {
                throw new IllegalArgumentException("invalid label in host: " + s);
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                throw new IllegalArgumentException("label may not start/end with '-': " + s);
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
                if (!ok) {
                    throw new IllegalArgumentException(
                        "invalid char '" + c + "' in host: " + s);
                }
            }
        }
    }

    public String value() {
        return value;
    }

    /**
     * The Sort-friendly URI Reordering Transform (SURT) of this host —
     * components reversed and comma-joined. Used as the lookup key in
     * CDXJ indexes (see Storage / Archive Service).
     *
     * <p>Example: {@code Host.of("sub.example.com").surt() → "com,example,sub"}
     */
    public String surt() {
        String[] parts = value.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append(',');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * The registrable suffix is the public-suffix-respecting domain.
     * <em>Note:</em> a real implementation needs a Public Suffix List
     * (PSL) lookup. This stub returns the last two labels, which is
     * incorrect for multi-label TLDs (.co.uk, .com.au). Replace with
     * a PSL-backed implementation before production use.
     */
    public String registrableDomain() {
        String[] parts = value.split("\\.");
        if (parts.length <= 2) return value;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Host h && value.equals(h.value);
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
