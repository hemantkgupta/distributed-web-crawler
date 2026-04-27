package com.hkg.crawler.controlplane;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.Host;

import java.util.List;
import java.util.Objects;

/**
 * Operator-defined scope rules controlling what the crawler is
 * allowed to fetch. Versioned in PostgreSQL (production) or in-memory
 * (tests); pushed to all agents via etcd watch.
 *
 * <p>Per blog §1 Control Plane: scope is the most-touched config —
 * operators add/remove host allows daily as new sources come online.
 */
public record ScopeConfig(
    int    version,
    String configName,
    List<HostRule> hostAllowlist,
    List<HostRule> hostDenylist,
    int    maxDepthPerHost,
    List<String> contentTypeAllowlist
) {

    public static final int DEFAULT_MAX_DEPTH = 30;

    public ScopeConfig {
        Objects.requireNonNull(configName, "configName");
        Objects.requireNonNull(hostAllowlist, "hostAllowlist");
        Objects.requireNonNull(hostDenylist, "hostDenylist");
        Objects.requireNonNull(contentTypeAllowlist, "contentTypeAllowlist");
        if (maxDepthPerHost < 1) {
            throw new IllegalArgumentException("maxDepthPerHost must be ≥ 1");
        }
        hostAllowlist = List.copyOf(hostAllowlist);
        hostDenylist = List.copyOf(hostDenylist);
        contentTypeAllowlist = List.copyOf(contentTypeAllowlist);
    }

    /** Default permissive scope — useful for tests; do not use in prod. */
    public static ScopeConfig permissive() {
        return new ScopeConfig(1, "permissive",
            List.of(), List.of(),
            DEFAULT_MAX_DEPTH,
            List.of("text/html", "application/xhtml+xml", "application/xml", "text/plain"));
    }

    /**
     * Decide whether a URL is in scope. Decision order:
     * <ol>
     *   <li>Explicit denylist match → DENY (highest precedence).</li>
     *   <li>Explicit allowlist non-empty + match → ALLOW.</li>
     *   <li>Explicit allowlist non-empty + no match → DENY.</li>
     *   <li>Empty allowlist → ALLOW (permissive default).</li>
     * </ol>
     */
    public Verdict check(CanonicalUrl url) {
        Host host = url.host();
        for (HostRule rule : hostDenylist) {
            if (rule.matches(host)) return Verdict.DENY;
        }
        if (hostAllowlist.isEmpty()) return Verdict.ALLOW;
        for (HostRule rule : hostAllowlist) {
            if (rule.matches(host)) return Verdict.ALLOW;
        }
        return Verdict.DENY;
    }

    /** A single host-pattern rule — exact match or simple suffix wildcard ({@code *.example.com}). */
    public record HostRule(String pattern) {
        public HostRule {
            Objects.requireNonNull(pattern, "pattern");
            if (pattern.isBlank()) {
                throw new IllegalArgumentException("pattern must not be blank");
            }
        }

        public boolean matches(Host host) {
            String value = host.value();
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                return value.endsWith(suffix) && !value.equals(suffix.substring(1));
            }
            return value.equals(pattern);
        }
    }

    public enum Verdict { ALLOW, DENY }
}
