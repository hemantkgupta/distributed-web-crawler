package com.hkg.crawler.coordinator.forwarding;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.PriorityClass;
import com.hkg.crawler.coordinator.AgentId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One bulk batch of URLs forwarded from one agent to another. The
 * batching layer accumulates {@link UrlEntry}s up to a configured size
 * (default 100) or flush interval (default 50ms), whichever comes first.
 *
 * <p>Batching amortizes the cross-agent RPC cost ~100×. At 500K
 * cross-agent forwards/sec across the cluster (10K pages/sec × 50
 * outlinks/page × ~95% non-local at 20 agents), per-URL synchronous
 * RPC is the bottleneck.
 */
public record ForwardingBatch(
    AgentId sourceAgent,
    AgentId targetAgent,
    long    batchSequence,
    Instant emittedAt,
    List<UrlEntry> urls
) {
    public ForwardingBatch {
        Objects.requireNonNull(sourceAgent, "sourceAgent");
        Objects.requireNonNull(targetAgent, "targetAgent");
        Objects.requireNonNull(emittedAt, "emittedAt");
        Objects.requireNonNull(urls, "urls");
        urls = List.copyOf(urls);
    }

    /** One URL inside a forwarding batch. */
    public record UrlEntry(
        CanonicalUrl url,
        PriorityClass priorityClass,
        CanonicalUrl discoveredFromUrl,
        Instant discoveredAt
    ) {
        public UrlEntry {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(priorityClass, "priorityClass");
            Objects.requireNonNull(discoveredAt, "discoveredAt");
            // discoveredFromUrl may be null for seeds.
        }
    }

    public int size() { return urls.size(); }
}
