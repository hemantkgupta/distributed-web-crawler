package com.hkg.crawler.render;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a page warrants the cost of a full render. Per
 * blog §7 Problem 1 (Recommendation): classifier-driven, calibrated
 * to ~5% of fetches.
 *
 * <p>Triggers (any one suffices):
 * <ul>
 *   <li>{@code <noscript>} block contains substantive content (the
 *       page is signaling that it needs JS to render properly).</li>
 *   <li>Document body innerHTML is small for a non-error response —
 *       suggests an SPA shell waiting for client-side hydration.</li>
 *   <li>Known SPA framework markers in the HTML
 *       ({@code <div id="root">}, {@code <app-root>}, etc.).</li>
 *   <li>Operator-forced via the per-host policy.</li>
 * </ul>
 *
 * <p>Stateless and thread-safe.
 */
public final class PageNeedsRenderClassifier {

    /** Body bytes below this threshold for a 200-OK response → suspicious. */
    public static final int EMPTY_BODY_THRESHOLD_BYTES = 5_000;

    private static final Pattern SPA_MARKER = Pattern.compile(
        "<(div|app-root)\\s+[^>]*\\bid\\s*=\\s*\"(root|app|main|__next)\""
            + "|<app-root\\b"
            + "|<ng-app\\b"
            + "|data-react",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern NOSCRIPT_WITH_CONTENT = Pattern.compile(
        "<noscript[^>]*>[^<]{20,}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Should this page be re-rendered through the full browser?
     *
     * @param httpStatus the response status (only inspect 2xx)
     * @param rawHtml    body of the response
     * @return optional reason if rendering is warranted; empty otherwise
     */
    public java.util.Optional<RenderRequest.Reason> classify(int httpStatus, String rawHtml) {
        if (httpStatus < 200 || httpStatus >= 300) return java.util.Optional.empty();
        if (rawHtml == null) return java.util.Optional.empty();

        // 1. Empty-ish body → SPA shell suspect.
        int bodyLen = approximateBodyLength(rawHtml);
        if (bodyLen >= 0 && bodyLen < EMPTY_BODY_THRESHOLD_BYTES) {
            return java.util.Optional.of(RenderRequest.Reason.EMPTY_BODY);
        }
        // 2. SPA framework markers.
        if (SPA_MARKER.matcher(rawHtml).find()) {
            return java.util.Optional.of(RenderRequest.Reason.SPA_FRAMEWORK_DETECTED);
        }
        // 3. <noscript> warning of substantial JS-only content.
        if (NOSCRIPT_WITH_CONTENT.matcher(rawHtml).find()) {
            return java.util.Optional.of(RenderRequest.Reason.CLASSIFIER_OTHER);
        }
        return java.util.Optional.empty();
    }

    /**
     * Cheap approximation of {@code <body>} length: locate the
     * outermost open + close tag. Returns -1 if we can't find them.
     */
    private static int approximateBodyLength(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        int open = lower.indexOf("<body");
        if (open < 0) return -1;
        int afterTag = lower.indexOf('>', open);
        if (afterTag < 0) return -1;
        int close = lower.lastIndexOf("</body>");
        if (close < 0 || close < afterTag) return -1;
        return close - afterTag;
    }
}
