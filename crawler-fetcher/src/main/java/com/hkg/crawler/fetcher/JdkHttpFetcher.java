package com.hkg.crawler.fetcher;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.FetchOutcome;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * JDK {@code HttpClient}-based {@link HttpFetcher} implementation.
 *
 * <p>HTTP/2 by default with HTTP/1.1 fallback (ALPN-negotiated).
 * Conditional GET via {@code If-None-Match} and {@code If-Modified-Since}.
 * Redirects followed up to {@link FetchRequest#maxRedirects()} hops; we
 * implement redirect handling manually rather than letting the JDK do it
 * because we need to re-canonicalize each hop and stop at the limit.
 *
 * <p>Body size enforced post-fetch (the JDK client doesn't expose a
 * pre-allocation cap); responses larger than {@code maxBodyBytes} are
 * returned with the body truncated and a metric flag.
 *
 * <p>Thread-safe — one shared {@code HttpClient} can serve many
 * concurrent {@code fetch()} calls.
 */
public final class JdkHttpFetcher implements HttpFetcher {

    private final HttpClient client;

    public JdkHttpFetcher() {
        this(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(FetchRequest.DEFAULT_CONNECT_TIMEOUT)
            .build());
    }

    public JdkHttpFetcher(HttpClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<FetchResult> fetch(FetchRequest request) {
        long startNs = System.nanoTime();
        return fetchWithRedirects(request, request.url(), 0, startNs)
            .exceptionally(ex -> fromException(request, ex, startNs));
    }

    // ---- internals -----------------------------------------------------

    private CompletableFuture<FetchResult> fetchWithRedirects(
            FetchRequest req, CanonicalUrl currentUrl, int hopCount, long startNs) {

        if (hopCount > req.maxRedirects()) {
            return CompletableFuture.completedFuture(
                redirectLimitExceeded(req, currentUrl, hopCount, startNs));
        }

        HttpRequest httpRequest = buildRequest(req, currentUrl);
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
            .orTimeout(req.totalTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .thenCompose(resp -> {
                int status = resp.statusCode();
                if (status >= 300 && status < 400 && status != 304) {
                    return handleRedirect(req, currentUrl, resp, hopCount, startNs);
                }
                return CompletableFuture.completedFuture(
                    fromResponse(req, currentUrl, resp, hopCount, startNs));
            });
    }

    private CompletableFuture<FetchResult> handleRedirect(
            FetchRequest req, CanonicalUrl from, HttpResponse<byte[]> resp,
            int hopCount, long startNs) {

        Optional<String> location = resp.headers().firstValue("Location");
        if (location.isEmpty()) {
            // 3xx without Location — treat as a successful 3xx, no redirect.
            return CompletableFuture.completedFuture(
                fromResponse(req, from, resp, hopCount, startNs));
        }
        String resolved = URI.create(from.value()).resolve(location.get()).toString();
        CanonicalUrl next;
        try {
            next = CanonicalUrl.of(resolved);
        } catch (IllegalArgumentException e) {
            // Malformed redirect target — treat as completed redirect.
            return CompletableFuture.completedFuture(
                fromResponse(req, from, resp, hopCount, startNs));
        }
        return fetchWithRedirects(req, next, hopCount + 1, startNs);
    }

    private HttpRequest buildRequest(FetchRequest req, CanonicalUrl url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url.value()))
            .timeout(req.readTimeout())
            .GET()
            .header("User-Agent", req.userAgent())
            .header("Accept-Encoding", "gzip, deflate")
            .header("From", "bot-abuse@example.com");

        // Conditional headers: send both per RFC 9110 — the server must
        // ignore If-Modified-Since when If-None-Match is present.
        req.conditionalEtag().ifPresent(etag -> b.header("If-None-Match", etag));
        req.conditionalLastModified().ifPresent(lm ->
            b.header("If-Modified-Since",
                DateTimeFormatter.RFC_1123_DATE_TIME.format(lm.atOffset(java.time.ZoneOffset.UTC))));

        return b.build();
    }

    private FetchResult fromResponse(FetchRequest req, CanonicalUrl finalUrl,
                                      HttpResponse<byte[]> resp, int hopCount, long startNs) {
        int status = resp.statusCode();
        long durationMs = elapsedMillis(startNs);
        HttpHeaders h = resp.headers();
        Map<String, List<String>> headerMap = new HashMap<>(h.map());

        Optional<String> contentType     = h.firstValue("Content-Type");
        Optional<String> contentEncoding = h.firstValue("Content-Encoding");
        Optional<String> etag            = h.firstValue("ETag");
        Optional<Instant> lastModified   = parseHttpDate(h.firstValue("Last-Modified"));

        FetchOutcome outcome = mapStatusToOutcome(status);
        boolean conditionalUsed = req.conditionalEtag().isPresent()
            || req.conditionalLastModified().isPresent();
        boolean conditionalMatched = (status == 304);

        Optional<byte[]> body = Optional.empty();
        if (status >= 200 && status < 300) {
            byte[] raw = resp.body();
            if (raw != null && raw.length > 0) {
                if (raw.length > req.maxBodyBytes()) {
                    byte[] truncated = new byte[(int) req.maxBodyBytes()];
                    System.arraycopy(raw, 0, truncated, 0, truncated.length);
                    body = Optional.of(truncated);
                } else {
                    body = Optional.of(raw);
                }
            }
        }

        return new FetchResult(
            req.url(),
            finalUrl,
            status,
            headerMap,
            body,
            contentType,
            contentEncoding,
            etag,
            lastModified,
            durationMs,
            hopCount,
            conditionalUsed,
            conditionalMatched,
            outcome,
            Optional.empty()
        );
    }

    private FetchResult fromException(FetchRequest req, Throwable ex, long startNs) {
        Throwable cause = ex instanceof CompletionException ce && ce.getCause() != null
            ? ce.getCause()
            : ex;
        FetchOutcome outcome;
        if (cause instanceof java.util.concurrent.TimeoutException
            || cause instanceof java.net.http.HttpTimeoutException) {
            outcome = FetchOutcome.TIMEOUT;
        } else if (cause instanceof IOException) {
            outcome = FetchOutcome.NETWORK_ERROR;
        } else {
            outcome = FetchOutcome.NETWORK_ERROR;
        }
        return new FetchResult(
            req.url(),
            req.url(),
            0,
            Map.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            elapsedMillis(startNs),
            0,
            req.conditionalEtag().isPresent() || req.conditionalLastModified().isPresent(),
            false,
            outcome,
            Optional.of(cause.getMessage() == null ? cause.toString() : cause.getMessage())
        );
    }

    private FetchResult redirectLimitExceeded(FetchRequest req, CanonicalUrl finalUrl,
                                               int hops, long startNs) {
        return new FetchResult(
            req.url(),
            finalUrl,
            0,
            Map.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            elapsedMillis(startNs),
            hops,
            false, false,
            FetchOutcome.REDIRECT_3XX,
            Optional.of("redirect limit exceeded")
        );
    }

    private static FetchOutcome mapStatusToOutcome(int status) {
        if (status == 304)               return FetchOutcome.NOT_MODIFIED_304;
        if (status >= 200 && status < 300) return FetchOutcome.SUCCESS_200;
        if (status >= 300 && status < 400) return FetchOutcome.REDIRECT_3XX;
        if (status == 429)                return FetchOutcome.RATE_LIMITED_429;
        if (status >= 400 && status < 500) return FetchOutcome.CLIENT_ERROR_4XX;
        if (status >= 500 && status < 600) return FetchOutcome.SERVER_ERROR_5XX;
        return FetchOutcome.NETWORK_ERROR;
    }

    private static Optional<Instant> parseHttpDate(Optional<String> raw) {
        if (raw.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Instant.from(
                DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw.get())));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static long elapsedMillis(long startNs) {
        return Duration.ofNanos(System.nanoTime() - startNs).toMillis();
    }
}
