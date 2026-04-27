package com.hkg.crawler.render;

import com.hkg.crawler.common.CanonicalUrl;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of a render attempt. Carries the rendered DOM bytes plus
 * operational metrics (render duration, network call count, console
 * error count, timeout flag) so the §7 cost model can be tracked.
 */
public record RenderResult(
    CanonicalUrl url,
    String renderedHtml,
    String rawHtml,
    long renderDurationMs,
    int networkRequestCount,
    int consoleErrorCount,
    boolean timeoutHit,
    boolean fallbackToRaw,
    Instant renderedAt
) {
    public RenderResult {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(renderedHtml, "renderedHtml");
        Objects.requireNonNull(rawHtml, "rawHtml");
        Objects.requireNonNull(renderedAt, "renderedAt");
    }
}
