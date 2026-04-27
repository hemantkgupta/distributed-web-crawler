package com.hkg.crawler.robots;

/**
 * A single {@code Allow:} or {@code Disallow:} rule from a robots.txt
 * group. RFC 9309 §2.2.2 specifies path matching: prefix-match starting
 * at the first octet of the URL's path, case-sensitive, with the **most
 * specific** (longest) matching rule winning.
 *
 * <p>{@code matchLength} is the precomputed character count of the
 * pattern excluding wildcard semantics, used as the tie-breaker for
 * longest-match selection.
 */
public record RobotsRule(String pathPattern, int matchLength, RuleType type) {

    public enum RuleType { ALLOW, DISALLOW }

    public RobotsRule(String pathPattern, RuleType type) {
        this(pathPattern, pathPattern.length(), type);
    }

    /**
     * Does this rule's path pattern match {@code path} as a prefix?
     * Pure prefix match per RFC 9309 §2.2.2; we do not implement wildcard
     * extensions ({@code *}, {@code $}) in this checkpoint.
     */
    public boolean matches(String path) {
        return path.startsWith(pathPattern);
    }
}
