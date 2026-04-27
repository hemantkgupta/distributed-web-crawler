package com.hkg.crawler.render;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test/simulator implementation of {@link Renderer} — returns the raw
 * HTML unchanged with a configurable simulated render duration.
 *
 * <p>Production deployments swap in a {@code PlaywrightRenderer} that
 * runs headless Chromium; the SPI is unchanged.
 */
public final class StubRenderer implements Renderer {

    private final long simulatedRenderMs;
    private final AtomicLong renderCount = new AtomicLong();

    public StubRenderer() { this(0); }

    public StubRenderer(long simulatedRenderMs) {
        this.simulatedRenderMs = simulatedRenderMs;
    }

    @Override
    public CompletableFuture<RenderResult> render(RenderRequest request) {
        renderCount.incrementAndGet();
        return CompletableFuture.completedFuture(new RenderResult(
            request.url(),
            request.rawHtml(),    // stub: rendered = raw
            request.rawHtml(),
            simulatedRenderMs,
            0, 0,
            false, false,
            Instant.now()
        ));
    }

    public long renderCount() { return renderCount.get(); }

    @Override
    public void close() { /* no resources */ }
}
