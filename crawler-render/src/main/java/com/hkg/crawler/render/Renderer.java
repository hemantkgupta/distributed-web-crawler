package com.hkg.crawler.render;

import java.util.concurrent.CompletableFuture;

/**
 * Render an HTML page through a JavaScript-capable engine. Production
 * implementation uses Playwright + headless Chromium; the SPI is
 * decoupled so tests can use a stub and the runtime can choose between
 * implementations at deploy time.
 *
 * <p>Important per blog §7: rendering is **50–100× the cost** of a raw
 * fetch. The Fetcher gates entry into this service via the
 * {@link PageNeedsRenderClassifier}; the runtime gates concurrency via
 * a separate pool with its own budget.
 */
public interface Renderer extends AutoCloseable {

    /**
     * Render {@code request} and return the result. The future never
     * throws — failures are surfaced via {@link RenderResult#fallbackToRaw}
     * or {@link RenderResult#timeoutHit}.
     */
    CompletableFuture<RenderResult> render(RenderRequest request);

    @Override
    void close();
}
