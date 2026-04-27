package com.hkg.crawler.fetcher;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.FetchOutcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link JdkHttpFetcher} against a local
 * {@link com.sun.net.httpserver.HttpServer}. Tests the full HTTP path
 * end-to-end (real socket, real HTTP parsing) rather than mocking.
 */
class JdkHttpFetcherTest {

    private HttpServer server;
    private int port;
    private JdkHttpFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
        // Use HTTP/1.1 for the test server (HttpServer doesn't support HTTP/2);
        // ALPN will negotiate HTTP/1.1 automatically.
        fetcher = new JdkHttpFetcher(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private CanonicalUrl url(String path) {
        return CanonicalUrl.of("http://127.0.0.1:" + port + path);
    }

    @Test
    void fetches_200_with_body() throws Exception {
        server.createContext("/", exchange -> respond(exchange, 200, "Hello, world!",
            "text/plain", null, null));

        FetchResult result = fetcher.fetch(FetchRequest.forUrl(url("/")))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(FetchOutcome.SUCCESS_200);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.body()).isPresent();
        assertThat(new String(result.body().orElseThrow(), StandardCharsets.UTF_8))
            .isEqualTo("Hello, world!");
        assertThat(result.contentType()).contains("text/plain");
        assertThat(result.fetchDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void returns_404_outcome_for_client_error() throws Exception {
        server.createContext("/missing", exchange -> respond(exchange, 404, "Not Found",
            "text/plain", null, null));

        FetchResult result = fetcher.fetch(FetchRequest.forUrl(url("/missing")))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(FetchOutcome.CLIENT_ERROR_4XX);
        assertThat(result.httpStatus()).isEqualTo(404);
        assertThat(result.body()).isEmpty();
    }

    @Test
    void returns_429_as_rate_limited() throws Exception {
        server.createContext("/throttled", exchange -> respond(exchange, 429, "Too Many",
            "text/plain", null, null));

        FetchResult result = fetcher.fetch(FetchRequest.forUrl(url("/throttled")))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(FetchOutcome.RATE_LIMITED_429);
    }

    @Test
    void returns_503_as_server_error() throws Exception {
        server.createContext("/down", exchange -> respond(exchange, 503, "Unavailable",
            "text/plain", null, null));

        FetchResult result = fetcher.fetch(FetchRequest.forUrl(url("/down")))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(FetchOutcome.SERVER_ERROR_5XX);
    }

    @Test
    void honors_conditional_get_with_etag() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        server.createContext("/resource", exchange -> {
            requestCount.incrementAndGet();
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if ("\"abc123\"".equals(ifNoneMatch)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
            } else {
                respond(exchange, 200, "content", "text/plain", "\"abc123\"", null);
            }
        });

        FetchResult first = fetcher.fetch(FetchRequest.forUrl(url("/resource")))
            .get(5, TimeUnit.SECONDS);
        assertThat(first.outcome()).isEqualTo(FetchOutcome.SUCCESS_200);
        assertThat(first.etag()).contains("\"abc123\"");

        FetchRequest conditional = FetchRequest.forUrl(url("/resource"))
            .withConditionalEtag("\"abc123\"");
        FetchResult second = fetcher.fetch(conditional).get(5, TimeUnit.SECONDS);

        assertThat(second.outcome()).isEqualTo(FetchOutcome.NOT_MODIFIED_304);
        assertThat(second.conditionalUsed()).isTrue();
        assertThat(second.conditionalMatched()).isTrue();
        assertThat(second.body()).isEmpty();
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void follows_single_redirect() throws Exception {
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/end");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/end", exchange ->
            respond(exchange, 200, "redirected", "text/plain", null, null));

        FetchResult result = fetcher.fetch(FetchRequest.forUrl(url("/start")))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(FetchOutcome.SUCCESS_200);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.redirectHopCount()).isEqualTo(1);
        assertThat(result.finalUrl().value()).endsWith("/end");
        assertThat(new String(result.body().orElseThrow(), StandardCharsets.UTF_8))
            .isEqualTo("redirected");
    }

    @Test
    void stops_at_max_redirects() throws Exception {
        // Loop redirect chain.
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        FetchResult result = fetcher.fetch(FetchRequest.forUrl(url("/loop")))
            .get(10, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(FetchOutcome.REDIRECT_3XX);
        assertThat(result.errorMessage()).isPresent();
        assertThat(result.errorMessage().orElseThrow()).contains("redirect limit");
    }

    @Test
    void truncates_body_above_size_cap() throws Exception {
        // 100 KB body
        byte[] big = new byte[100_000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) ('x');
        server.createContext("/big", exchange -> respond(exchange, 200,
            new String(big, StandardCharsets.UTF_8), "text/plain", null, null));

        FetchRequest req = new FetchRequest(
            url("/big"),
            java.util.Optional.empty(), java.util.Optional.empty(),
            FetchRequest.DEFAULT_CONNECT_TIMEOUT,
            FetchRequest.DEFAULT_READ_TIMEOUT,
            FetchRequest.DEFAULT_TOTAL_TIMEOUT,
            FetchRequest.DEFAULT_MAX_REDIRECTS,
            10_000L,                  // 10 KB cap → truncation
            FetchRequest.DEFAULT_USER_AGENT
        );
        FetchResult result = fetcher.fetch(req).get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(FetchOutcome.SUCCESS_200);
        assertThat(result.body()).isPresent();
        assertThat(result.body().orElseThrow().length).isEqualTo(10_000);
    }

    @Test
    void timeout_is_reported_as_TIMEOUT_outcome() throws Exception {
        // Block forever in the handler.
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(Duration.ofSeconds(10).toMillis()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });

        FetchRequest req = new FetchRequest(
            url("/slow"),
            java.util.Optional.empty(), java.util.Optional.empty(),
            Duration.ofMillis(500),
            Duration.ofMillis(500),
            Duration.ofMillis(500),
            FetchRequest.DEFAULT_MAX_REDIRECTS,
            FetchRequest.DEFAULT_MAX_BODY_BYTES,
            FetchRequest.DEFAULT_USER_AGENT
        );
        FetchResult result = fetcher.fetch(req).get(10, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(FetchOutcome.TIMEOUT);
        assertThat(result.errorMessage()).isPresent();
    }

    @Test
    void user_agent_header_is_sent() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        server.createContext("/ua", exchange -> {
            String ua = exchange.getRequestHeaders().getFirst("User-Agent");
            if (ua != null && ua.contains("DistributedWebCrawler")) {
                seen.incrementAndGet();
            }
            respond(exchange, 200, "ok", "text/plain", null, null);
        });

        fetcher.fetch(FetchRequest.forUrl(url("/ua"))).get(5, TimeUnit.SECONDS);
        assertThat(seen.get()).isEqualTo(1);
    }

    @Test
    void parses_etag_and_last_modified_headers() throws Exception {
        server.createContext("/headers", exchange ->
            respond(exchange, 200, "x", "text/plain", "\"v1\"",
                "Wed, 21 Oct 2026 07:28:00 GMT"));

        FetchResult result = fetcher.fetch(FetchRequest.forUrl(url("/headers")))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.etag()).contains("\"v1\"");
        assertThat(result.lastModified()).isPresent();
    }

    // ---- helpers -----------------------------------------------------

    private static void respond(HttpExchange exchange, int status, String body,
                                 String contentType, String etag, String lastModified) {
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            if (etag != null) {
                exchange.getResponseHeaders().add("ETag", etag);
            }
            if (lastModified != null) {
                exchange.getResponseHeaders().add("Last-Modified", lastModified);
            }
            if (status >= 400) {
                exchange.sendResponseHeaders(status, payload.length);
            } else {
                exchange.sendResponseHeaders(status, payload.length);
            }
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
