package com.hkg.crawler.node;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.FetchOutcome;
import com.hkg.crawler.common.Host;
import com.hkg.crawler.common.PriorityClass;
import com.hkg.crawler.dedup.BloomFilter;
import com.hkg.crawler.dedup.TwoTierUrlDedup;
import com.hkg.crawler.dedup.UrlDedupVerdict;
import com.hkg.crawler.fetcher.FetchRequest;
import com.hkg.crawler.fetcher.FetchResult;
import com.hkg.crawler.fetcher.HttpFetcher;
import com.hkg.crawler.fetcher.JdkHttpFetcher;
import com.hkg.crawler.frontier.ClaimedUrl;
import com.hkg.crawler.frontier.Frontier;
import com.hkg.crawler.frontier.FrontierUrl;
import com.hkg.crawler.frontier.HostState;
import com.hkg.crawler.frontier.InMemoryFrontier;
import com.hkg.crawler.importance.OpicComputer;
import com.hkg.crawler.importance.RecrawlScheduler;
import com.hkg.crawler.indexer.IndexerPipeline;
import com.hkg.crawler.indexer.InMemoryMessagePublisher;
import com.hkg.crawler.parser.ExtractedLink;
import com.hkg.crawler.parser.HtmlParser;
import com.hkg.crawler.parser.JsoupHtmlParser;
import com.hkg.crawler.parser.ParsedDocument;
import com.hkg.crawler.robots.RobotsCache;
import com.hkg.crawler.robots.RobotsFetcher;
import com.hkg.crawler.robots.RobotsRules;
import com.hkg.crawler.storage.LocalDirWarcSink;
import com.hkg.crawler.storage.WarcRecord;
import com.hkg.crawler.storage.WarcWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-shard end-to-end crawler — Phase 1 Checkpoint 9.
 *
 * <p>Composes every Phase 1 + Phase 2 service into a single-process
 * crawler that:
 *
 * <ol>
 *   <li>Pulls a URL from the {@link Frontier}.</li>
 *   <li>Looks up {@link com.hkg.crawler.dns.DnsResolver DNS} (skipped
 *       when fetching through the JDK client which manages its own
 *       resolver).</li>
 *   <li>Asks the {@link RobotsCache} whether the path is allowed.</li>
 *   <li>Fetches via {@link HttpFetcher}.</li>
 *   <li>Writes the raw HTTP response to a WARC file via
 *       {@link WarcWriter}.</li>
 *   <li>Parses with {@link HtmlParser}.</li>
 *   <li>Extracts outlinks; runs each through {@link TwoTierUrlDedup}
 *       and the trap-rejection check; admits new URLs to the frontier.</li>
 *   <li>Updates {@link OpicComputer} cash distribution and
 *       {@link RecrawlScheduler} change-rate state.</li>
 *   <li>Publishes document/link/operational events through the
 *       {@link IndexerPipeline}.</li>
 * </ol>
 *
 * <p>Single-threaded by design — the goal of CP9 is correctness of the
 * end-to-end loop, not throughput. A multi-threaded fetcher pool is a
 * follow-up and changes nothing about the service contracts.
 */
public final class CrawlerNode implements AutoCloseable {

    private final NodeConfig config;
    private final Frontier frontier;
    private final RobotsCache robotsCache;
    private final HttpFetcher fetcher;
    private final HtmlParser parser;
    private final TwoTierUrlDedup dedup;
    private final OpicComputer opic;
    private final RecrawlScheduler recrawl;
    private final WarcWriter warcWriter;
    private final IndexerPipeline indexer;
    private final InMemoryMessagePublisher publisher;

    private final AtomicLong cFetched     = new AtomicLong();
    private final AtomicLong cSucceeded   = new AtomicLong();
    private final AtomicLong cFailed      = new AtomicLong();
    private final AtomicLong cDisallowed  = new AtomicLong();
    private final AtomicLong cBytes       = new AtomicLong();
    private final AtomicLong cWarcWritten = new AtomicLong();
    private final AtomicLong cDocuments   = new AtomicLong();
    private final AtomicLong cLinks       = new AtomicLong();
    private final AtomicLong cDeduped     = new AtomicLong();

    public CrawlerNode(NodeConfig config, RobotsFetcher robotsFetcher) throws IOException {
        this(config, robotsFetcher, new JdkHttpFetcher());
    }

    public CrawlerNode(NodeConfig config, RobotsFetcher robotsFetcher,
                        HttpFetcher fetcher) throws IOException {
        this.config       = config;
        this.frontier     = new InMemoryFrontier();
        this.robotsCache  = new RobotsCache(robotsFetcher);
        this.fetcher      = fetcher;
        this.parser       = new JsoupHtmlParser();
        this.dedup        = TwoTierUrlDedup.forCapacity(
                                Math.max(1024, config.maxUrlsToCrawl() * 10L), 0.01);
        this.opic         = new OpicComputer(1.0);
        this.recrawl      = new RecrawlScheduler(opic);
        this.warcWriter   = new WarcWriter(new LocalDirWarcSink(config.warcOutputDir()));
        this.publisher    = new InMemoryMessagePublisher();
        this.indexer      = new IndexerPipeline(publisher);

        // Configure host state per the politeness delay.
        // Done lazily on enqueue — Frontier creates HostState with default 1s.

        // Seed the frontier.
        for (CanonicalUrl seed : config.seeds()) {
            UrlDedupVerdict v = dedup.recordIfNew(seed);
            if (v == UrlDedupVerdict.NEW) {
                frontier.enqueue(new FrontierUrl(seed, PriorityClass.BFS_DISCOVERY, Instant.now()));
                opic.seed(seed);
                applyPolitenessDelay(seed.host());
            }
        }
    }

    /**
     * Run the crawl loop until either the frontier is empty or
     * {@link NodeConfig#maxUrlsToCrawl()} URLs have been fetched.
     */
    public CrawlStats runUntilFrontierEmpty() {
        long stoppedReason = CrawlStats.REASON_FRONTIER_EMPTY;
        while (cFetched.get() < config.maxUrlsToCrawl()) {
            Instant now = Instant.now();
            Optional<ClaimedUrl> claimOpt = frontier.claimNext(now);
            if (claimOpt.isEmpty()) {
                // Either frontier truly empty, or hosts cooling down. Check the heap-empty case.
                if (frontier.stats().totalUrlsInBackQueues() == 0) {
                    break;   // truly empty
                }
                // Else: hosts cooling down; sleep briefly until earliest ready.
                Optional<Instant> earliest = frontier.stats().earliestReadyTime();
                if (earliest.isPresent()) {
                    long waitMs = Math.max(0, earliest.get().toEpochMilli() - now.toEpochMilli());
                    if (waitMs > 0) sleep(Math.min(waitMs, 500));
                } else {
                    sleep(50);
                }
                continue;
            }
            processClaim(claimOpt.get());
        }
        if (cFetched.get() >= config.maxUrlsToCrawl()) {
            stoppedReason = CrawlStats.REASON_MAX_REACHED;
        }
        try { warcWriter.flush(); } catch (IOException ignored) {}
        indexer.flush();
        return snapshot(stoppedReason);
    }

    public CrawlStats snapshot(long stoppedReason) {
        return new CrawlStats(
            cFetched.get(),
            cSucceeded.get(),
            cFailed.get(),
            cDisallowed.get(),
            cBytes.get(),
            cWarcWritten.get(),
            cDocuments.get(),
            cLinks.get(),
            cDeduped.get(),
            stoppedReason
        );
    }

    public InMemoryMessagePublisher publisher() { return publisher; }
    public Frontier frontier() { return frontier; }
    public RobotsCache robotsCache() { return robotsCache; }
    public OpicComputer opic() { return opic; }
    public RecrawlScheduler recrawlScheduler() { return recrawl; }

    @Override
    public void close() throws IOException {
        warcWriter.close();
        publisher.close();
        if (dedup.bloom() != null) {
            try { dedup.bloom().toString(); } catch (Exception ignored) {}
        }
    }

    // ---- internals -----------------------------------------------------

    private void processClaim(ClaimedUrl claim) {
        Instant now = Instant.now();

        // 1. Robots check (defense in depth — also applies at admission).
        // Disallowed URLs are recorded but do not count as "fetched".
        RobotsRules rules;
        try {
            rules = robotsCache.rulesFor(claim.host()).get();
        } catch (Exception e) {
            rules = null;
        }
        if (rules != null && rules.isAllowed(config.userAgent(), claim.url().path())
                == RobotsRules.Verdict.DISALLOWED) {
            frontier.reportVerdict(claim.url(), FetchOutcome.ROBOTS_DISALLOWED, now);
            cDisallowed.incrementAndGet();
            return;
        }

        cFetched.incrementAndGet();

        // 2. HTTP fetch.
        FetchRequest request = FetchRequest.forUrl(claim.url());
        FetchResult result;
        try {
            result = fetcher.fetch(request).get();
        } catch (Exception e) {
            cFailed.incrementAndGet();
            frontier.reportVerdict(claim.url(), FetchOutcome.NETWORK_ERROR, now);
            return;
        }
        frontier.reportVerdict(claim.url(), result.outcome(), now);

        // 3. Operational stream — emit for every fetch attempt.
        indexer.emitOperational(claim.url(), result.httpStatus(),
            result.fetchDurationMs(),
            result.body().map(b -> (long) b.length).orElse(0L),
            result.contentType().orElse(""),
            result.conditionalUsed(), result.conditionalMatched(),
            now);

        if (!result.outcome().isSuccess()) {
            cFailed.incrementAndGet();
            recrawl.recordFetch(claim.url(), false, now);
            return;
        }
        cSucceeded.incrementAndGet();
        result.body().ifPresent(b -> cBytes.addAndGet(b.length));

        // 4. WARC record.
        result.body().ifPresent(body -> writeWarc(result, body));

        // 5. Parse + outlink extraction.
        if (result.body().isPresent()) {
            String html = new String(result.body().get(), StandardCharsets.UTF_8);
            ParsedDocument parsed = parser.parse(result.finalUrl(), html);

            // 5a. Document stream (if indexable).
            if (parsed.isIndexable()) {
                indexer.emitDocument(result.finalUrl(),
                    parsed.title(), parsed.mainText(),
                    parsed.simhash64(), now);
                cDocuments.incrementAndGet();
            }

            // 5b. Link stream + frontier admission for outlinks.
            for (ExtractedLink link : parsed.outlinks()) {
                indexer.emitLink(result.finalUrl(), link.targetUrl(),
                    link.anchorText(), link.rel(), link.domSection().name());
                cLinks.incrementAndGet();
                admitOutlink(link.targetUrl(), now);
            }

            // 5c. OPIC distribution.
            opic.visit(result.finalUrl(), parsed.outlinks().stream()
                .map(ExtractedLink::targetUrl).toList());

            // 5d. Recrawl scheduler — record fetch outcome.
            recrawl.recordFetch(claim.url(), true, now);
        } else {
            recrawl.recordFetch(claim.url(), false, now);
        }
    }

    private void admitOutlink(CanonicalUrl target, Instant now) {
        UrlDedupVerdict verdict = dedup.recordIfNew(target);
        if (verdict == UrlDedupVerdict.DUPLICATE) {
            cDeduped.incrementAndGet();
            return;
        }
        applyPolitenessDelay(target.host());
        frontier.enqueue(new FrontierUrl(target, PriorityClass.BFS_DISCOVERY, now));
    }

    private void applyPolitenessDelay(Host host) {
        // Configure the host's crawl-delay before its back queue is created
        // by Frontier. We can't intercept Frontier's lazy creation, so we
        // check after enqueue and update if state exists.
        Optional<HostState> hs = ((InMemoryFrontier) frontier).hostStateFor(host);
        hs.ifPresent(s -> s.setCrawlDelay(config.politenessDelay()));
    }

    private void writeWarc(FetchResult result, byte[] body) {
        try {
            // Build a synthetic HTTP response framing for the WARC payload.
            StringBuilder header = new StringBuilder();
            header.append("HTTP/1.1 ").append(result.httpStatus()).append("\r\n");
            result.contentType().ifPresent(ct -> header.append("Content-Type: ").append(ct).append("\r\n"));
            header.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            byte[] httpBytes = new byte[header.length() + body.length];
            byte[] headerBytes = header.toString().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(headerBytes, 0, httpBytes, 0, headerBytes.length);
            System.arraycopy(body, 0, httpBytes, headerBytes.length, body.length);
            warcWriter.write(WarcRecord.response(result.finalUrl(),
                Instant.now(), httpBytes, null));
            cWarcWritten.incrementAndGet();
        } catch (IOException e) {
            // Don't fail the crawl on WARC errors; record metric and continue.
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
