package com.hkg.crawler.render;

import com.hkg.crawler.common.CanonicalUrl;

import java.time.Instant;
import java.util.Objects;

/**
 * Request to the {@link Renderer}. Originates from the Fetcher when the
 * raw HTML triggers the page-needs-render classifier.
 *
 * <p>Includes the raw HTML so the renderer (or a downstream consumer)
 * can fall back to it if the render fails or times out.
 */
public record RenderRequest(
    CanonicalUrl url,
    String rawHtml,
    Reason reason,
    Instant requestedAt
) {
    public RenderRequest {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(rawHtml, "rawHtml");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    public enum Reason {
        EMPTY_BODY,
        SPA_FRAMEWORK_DETECTED,
        OPERATOR_FORCED,
        CLASSIFIER_OTHER
    }
}
