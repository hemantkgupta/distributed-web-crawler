package com.hkg.crawler.node;

import com.hkg.crawler.common.CanonicalUrl;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for one {@link CrawlerNode} run.
 *
 * <p>Single-shard scope: this is the configuration for a stand-alone
 * crawl, not a distributed cluster. Phase 3 will introduce a cluster
 * config layered on top.
 */
public record NodeConfig(
    List<CanonicalUrl> seeds,
    Path warcOutputDir,
    int  maxUrlsToCrawl,
    int  maxOutlinksPerPage,
    String userAgent,
    Duration politenessDelay
) {

    public static final int      DEFAULT_MAX_URLS = 1000;
    public static final int      DEFAULT_MAX_OUTLINKS = 100;
    public static final String   DEFAULT_UA = "DistributedWebCrawler/0.1 (+https://example.com/bot)";
    public static final Duration DEFAULT_POLITENESS_DELAY = Duration.ofMillis(100);

    public NodeConfig {
        Objects.requireNonNull(seeds, "seeds");
        Objects.requireNonNull(warcOutputDir, "warcOutputDir");
        Objects.requireNonNull(userAgent, "userAgent");
        Objects.requireNonNull(politenessDelay, "politenessDelay");
        seeds = List.copyOf(seeds);
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("seeds must be non-empty");
        }
        if (maxUrlsToCrawl < 1) {
            throw new IllegalArgumentException("maxUrlsToCrawl must be ≥ 1");
        }
    }

    public static NodeConfig defaults(List<CanonicalUrl> seeds, Path warcOutputDir) {
        return new NodeConfig(seeds, warcOutputDir,
            DEFAULT_MAX_URLS, DEFAULT_MAX_OUTLINKS, DEFAULT_UA, DEFAULT_POLITENESS_DELAY);
    }
}
