package com.hkg.crawler.render;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PageNeedsRenderClassifierTest {

    private final PageNeedsRenderClassifier classifier = new PageNeedsRenderClassifier();

    @Test
    void substantive_html_does_not_warrant_render() {
        String html = "<html><body>" + "lorem ipsum content. ".repeat(500) + "</body></html>";
        assertThat(classifier.classify(200, html)).isEmpty();
    }

    @Test
    void small_body_triggers_EMPTY_BODY() {
        String html = "<html><body><p>short</p></body></html>";
        assertThat(classifier.classify(200, html))
            .hasValue(RenderRequest.Reason.EMPTY_BODY);
    }

    @Test
    void react_root_triggers_SPA_FRAMEWORK_DETECTED() {
        // Body large enough to skip the empty-body check.
        String html = "<html><body><div id=\"root\"></div>"
            + "padding ".repeat(2000) + "</body></html>";
        assertThat(classifier.classify(200, html))
            .hasValue(RenderRequest.Reason.SPA_FRAMEWORK_DETECTED);
    }

    @Test
    void app_root_angular_marker_triggers_render() {
        String html = "<html><body><app-root></app-root>"
            + "padding ".repeat(2000) + "</body></html>";
        assertThat(classifier.classify(200, html))
            .hasValue(RenderRequest.Reason.SPA_FRAMEWORK_DETECTED);
    }

    @Test
    void noscript_with_content_triggers_classifier_other() {
        String html = "<html><body>"
            + "padding ".repeat(2000)
            + "<noscript>This page requires JavaScript to display its content.</noscript>"
            + "</body></html>";
        assertThat(classifier.classify(200, html))
            .hasValue(RenderRequest.Reason.CLASSIFIER_OTHER);
    }

    @Test
    void non_2xx_returns_empty() {
        String html = "<html><body><div id=\"root\"></div></body></html>";
        assertThat(classifier.classify(404, html)).isEmpty();
        assertThat(classifier.classify(500, html)).isEmpty();
    }

    @Test
    void null_html_returns_empty() {
        assertThat(classifier.classify(200, null)).isEmpty();
    }

    @Test
    void stub_renderer_returns_raw_html_unchanged() throws Exception {
        try (StubRenderer renderer = new StubRenderer(100)) {
            String html = "<html><body><div id='root'></div></body></html>";
            RenderRequest req = new RenderRequest(
                com.hkg.crawler.common.CanonicalUrl.of("http://example.com/"),
                html, RenderRequest.Reason.SPA_FRAMEWORK_DETECTED,
                java.time.Instant.parse("2026-04-27T12:00:00Z"));

            RenderResult result = renderer.render(req).get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.renderedHtml()).isEqualTo(html);
            assertThat(result.renderDurationMs()).isEqualTo(100);
            assertThat(result.fallbackToRaw()).isFalse();
            assertThat(renderer.renderCount()).isEqualTo(1);
        }
    }

    @Test
    void render_request_is_immutable_and_validates() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new RenderRequest(null, "x", RenderRequest.Reason.EMPTY_BODY,
                java.time.Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }
}
