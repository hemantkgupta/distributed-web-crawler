# Implementation Plan

A checkpoint-based plan for implementing the distributed-web-crawler. Each checkpoint adds a coherent slice of functionality with passing tests; cumulative state is committable.

## Phase 1 — Foundation (single-shard correctness)

Goal: a working single-process crawler that respects robots, dedupes URLs, and produces parsed output. No distribution, no advanced features.

* **Checkpoint 1: `crawler-common` types.** `Host`, `CanonicalUrl`, `AddressFamily`, `PriorityClass`, `FetchOutcome`. Unit tests for canonicalization rules. **(Done)**
* **Checkpoint 2: `crawler-frontier` core.** In-memory Mercator-style two-tier with min-heap of host queues. Single-process; no persistence yet. Unit tests for ready-host scheduling and per-host politeness clocks.
* **Checkpoint 3: `crawler-frontier` persistence.** RocksDB-backed back queues + host state. Crash recovery test. Heap rebuilt from `host_state` on startup.
* **Checkpoint 4: `crawler-dns` core.** Async resolver with positive + negative cache, request coalescing, per-domain semaphore. Tests against a stub resolver. No local recursive yet.
* **Checkpoint 5: `crawler-robots` core.** RFC 9309 parser with longest-match algorithm, 4xx-as-allow / 5xx-as-disallow asymmetry. Compliance tests against the RFC's test corpus.
* **Checkpoint 6: `crawler-fetcher` core.** HTTP/2 client with virtual threads, conditional GET, redirect handling, content-type filtering. Local test server for end-to-end fetch tests.
* **Checkpoint 7: `crawler-parser` core.** jsoup HTML parsing, link extraction, boilerplate stripper, language detection, Simhash pre-compute.
* **Checkpoint 8: `crawler-dedup` URL tier.** Bloom filter + in-memory exact-set fallback. Two-tier verdict tests with seeded false positives.
* **Checkpoint 9: end-to-end single-shard.** `crawler-node` composes all the above. Crawls a small seed list end-to-end with WARC output. Integration test.

## Phase 2 — At-scale primitives

Goal: the mechanisms that distinguish a billion-URL crawler from a million-URL one.

* **Checkpoint 10: `crawler-dedup` content tier.** 64-bit Simhash with k=3 partition-index lookup (Manku-Jain-Das). Near-dup detection tests.
* **Checkpoint 11: `crawler-dedup` DRUM-style URL backstop.** Disk-first batched URL repository for >10⁹ URL scale. Bucket-sharded RocksDB.
* **Checkpoint 12: `crawler-importance` OPIC.** Online cash distribution; Cassandra (or RocksDB-embedded for now) per-URL state.
* **Checkpoint 13: `crawler-importance` adaptive recrawl.** Per-URL change-rate estimator, recrawl priority computation.
* **Checkpoint 14: `crawler-storage` WARC writer.** Local NVMe buffer → S3 flush. WARC 1.1 spec compliance tests.
* **Checkpoint 15: `crawler-storage` CDXJ indexer.** Sorted-by-SURT index over WARC outputs.
* **Checkpoint 16: `crawler-indexer` Kafka emitters.** Three streams (documents, links, operational). Local Redpanda for testing.

## Phase 3 — Distribution

Goal: scale beyond a single agent.

* **Checkpoint 17: `crawler-coordinator` consistent-hash ring.** UbiCrawler-style host-hash routing with virtual nodes. In-process tests with multiple agent stubs.
* **Checkpoint 18: `crawler-coordinator` gossip membership.** SWIM-like gossip, etcd anchor for tiebreaking. Test with simulated network partitions.
* **Checkpoint 19: cross-agent forwarding.** Streaming gRPC forwarding queue with bulk batches. Failure-injection tests.
* **Checkpoint 20: hot-host policy.** Shared-state spillover via Redis. Politeness clock coordination across agents.

## Phase 4 — Operability

Goal: the crawler can be run by humans.

* **Checkpoint 21: `crawler-render`.** Playwright-driven headless Chromium pool. Resource filtering, page-needs-render classifier.
* **Checkpoint 22: `crawler-control-plane`.** Operator REST API, scope config, takedown chain, audit log.
* **Checkpoint 23: `crawler-admin` CLI.** Operator commands matching the REST API.
* **Checkpoint 24: `crawler-simulator`.** Deterministic-simulation harness for race-condition testing.
* **Checkpoint 25: `crawler-bench`.** Load-generation harness.

## Phase 5 — Production deployment

* **Checkpoint 26: K8s deployment manifests** in `deploy/`.
* **Checkpoint 27: Observability** — Prometheus metrics, OpenTelemetry traces, Grafana dashboards.
* **Checkpoint 28: Security review** — sandbox hardening, network policy, secrets management.

## Out of scope for v1

* Multi-region active-active replication
* JavaScript fingerprinting beyond Simhash (semantic embeddings)
* Focused/topical crawler classifier mode
* Real-time alerting on content changes
