package com.hkg.crawler.node;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.indexer.IndexerStream;
import com.hkg.crawler.robots.RobotsFetcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for {@link CrawlerNode}.
 *
 * <p>Runs an embedded {@link HttpServer} that hosts a tiny website with
 * cross-page links and a {@code robots.txt}, then verifies that the
 * crawler correctly follows links, respects robots, dedups, writes WARC
 * files, emits indexer events, and so on.
 *
 * <p>Test website:
 * <pre>
 *   /          → links to /a, /b, /forbidden
 *   /a         → links to /b, /c, /a (self-loop)
 *   /b         → links to /c, /
 *   /c         → no outlinks
 *   /forbidden → blocked by robots.txt
 *   /robots.txt → "User-agent: *\nDisallow: /forbidden"
 * </pre>
 */
class CrawlerNodeIntegrationTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        register("/",          "<html><body>"
            + "<a href=\"/a\">A</a> <a href=\"/b\">B</a> <a href=\"/forbidden\">X</a>"
            + "</body></html>");
        register("/a",         "<html><body>"
            + "<a href=\"/b\">B</a> <a href=\"/c\">C</a> <a href=\"/a\">self</a>"
            + "</body></html>");
        register("/b",         "<html><body>"
            + "<a href=\"/c\">C</a> <a href=\"/\">root</a>"
            + "</body></html>");
        register("/c",         "<html><body>terminal</body></html>");
        register("/forbidden", "<html><body>SHOULD NOT BE FETCHED</body></html>");

        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void register(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
    }

    /** Stub robots fetcher: returns a synthetic robots.txt for our test host. */
    private RobotsFetcher stubRobots() {
        return host -> {
            String body = "User-agent: *\nDisallow: /forbidden\n";
            return CompletableFuture.completedFuture(
                RobotsFetcher.RobotsResponse.ok(200, body));
        };
    }

    @Test
    void end_to_end_crawl_follows_links_and_respects_robots(@TempDir Path tempDir) throws Exception {
        Path warcDir = tempDir.resolve("warc");

        NodeConfig config = new NodeConfig(
            List.of(CanonicalUrl.of("http://127.0.0.1:" + port + "/")),
            warcDir,
            20,                            // max URLs
            100,                           // max outlinks per page
            "TestCrawler/0.1",
            Duration.ofMillis(50));        // tight politeness for fast test

        try (CrawlerNode node = new CrawlerNode(config, stubRobots())) {
            CrawlStats stats = node.runUntilFrontierEmpty();

            // Should have fetched /, /a, /b, /c (4 URLs); /forbidden is robots-disallowed
            // and counted separately (not as a fetched URL).
            assertThat(stats.urlsFetched()).isEqualTo(4);
            assertThat(stats.urlsSucceeded()).isEqualTo(4);
            assertThat(stats.urlsDisallowedByRobots()).isEqualTo(1);
            assertThat(stats.urlsFailed()).isEqualTo(0);

            // Documents stream: 4 (/, /a, /b, /c are all indexable).
            assertThat(stats.documentsEmitted()).isEqualTo(4);
            assertThat(node.publisher().publishedCount(IndexerStream.DOCUMENTS)).isEqualTo(4);

            // Links stream: total outlinks emitted (3 from /, 3 from /a, 2 from /b, 0 from /c = 8).
            assertThat(stats.linksDiscovered()).isEqualTo(8);
            assertThat(node.publisher().publishedCount(IndexerStream.LINKS)).isEqualTo(8);

            // Operational stream: 4 fetch events.
            assertThat(node.publisher().publishedCount(IndexerStream.OPERATIONAL)).isEqualTo(4);

            // WARC files written.
            assertThat(stats.warcRecordsWritten()).isEqualTo(4);
            List<Path> warcs = Files.list(warcDir).filter(p -> p.toString().endsWith(".warc"))
                .collect(Collectors.toList());
            assertThat(warcs).hasSize(1);
            String warcContent = Files.readString(warcs.get(0));
            assertThat(warcContent).contains("WARC-Type: response");
            assertThat(warcContent).contains("WARC/1.1");

            // Dedup: outlinks include duplicates (/, /a self, /b, /c twice). Some get dedup'd.
            assertThat(stats.urlsDeduplicated()).isGreaterThan(0);

            // Stopped because frontier emptied (not max-reached).
            assertThat(stats.stoppedReason()).isEqualTo(CrawlStats.REASON_FRONTIER_EMPTY);

            // OPIC importance distributed — some URLs have non-zero history.
            CanonicalUrl rootUrl = CanonicalUrl.of("http://127.0.0.1:" + port + "/");
            assertThat(node.opic().historyOf(rootUrl)).isPositive();
        }
    }

    @Test
    void respects_robots_disallow_when_directly_seeded(@TempDir Path tempDir) throws Exception {
        Path warcDir = tempDir.resolve("warc");

        // Seed the disallowed URL directly. Robots check should suppress it.
        NodeConfig config = new NodeConfig(
            List.of(CanonicalUrl.of("http://127.0.0.1:" + port + "/forbidden")),
            warcDir,
            10, 100, "TestCrawler/0.1", Duration.ofMillis(50));

        try (CrawlerNode node = new CrawlerNode(config, stubRobots())) {
            CrawlStats stats = node.runUntilFrontierEmpty();

            // Robots disallowed → no successful fetch.
            assertThat(stats.urlsDisallowedByRobots()).isEqualTo(1);
            assertThat(stats.urlsSucceeded()).isZero();
            assertThat(stats.warcRecordsWritten()).isZero();
        }
    }

    @Test
    void max_urls_to_crawl_is_honored(@TempDir Path tempDir) throws Exception {
        Path warcDir = tempDir.resolve("warc");

        // Set a very low max — should stop before draining the frontier.
        NodeConfig config = new NodeConfig(
            List.of(CanonicalUrl.of("http://127.0.0.1:" + port + "/")),
            warcDir,
            2,                              // max URLs
            100, "TestCrawler/0.1", Duration.ofMillis(50));

        try (CrawlerNode node = new CrawlerNode(config, stubRobots())) {
            CrawlStats stats = node.runUntilFrontierEmpty();
            assertThat(stats.urlsFetched()).isEqualTo(2);
            assertThat(stats.stoppedReason()).isEqualTo(CrawlStats.REASON_MAX_REACHED);
        }
    }
}
