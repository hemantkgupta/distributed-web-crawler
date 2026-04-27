# Distributed Web Crawler

A Java 21 multi-module reference implementation of a global-scale web crawler. Companion code for the [`web-crawler`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler.md) and [`web-crawler-full`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler-full.md) blog posts in the CSE wiki.

The blog describes the architecture in twelve services. This repo organizes the implementation into Gradle modules with the same boundaries — each service is a module, each module is independently buildable and testable.

## Status

**All 5 phases complete (Checkpoints 1–28). 282 tests passing.** Full multi-module build green across all 17 modules. End-to-end single-JVM crawl (Phase 1+2), distribution primitives for multi-agent deployment (Phase 3), operability layer with operator API + admin CLI + render service + simulator + bench harness (Phase 4), and production deployment manifests + observability + security review (Phase 5).

**Phase 4 — Operability:**

* **CP21 — `crawler-render`**: Renderer SPI, `PageNeedsRenderClassifier` (SPA marker / empty-body / noscript triggers), `StubRenderer` for tests (9 tests)
* **CP22 — `crawler-control-plane`**: `ControlPlane` façade, `ScopeConfig` with allow/denylist + wildcard, `AuditLog` with SHA-256 hash chain (12 tests)
* **CP23 — `crawler-admin`**: `AdminCli` with subcommands (seed, takedown, host quarantine/release, pause/resume, status, audit list) (12 tests)
* **CP24 — `crawler-simulator`**: `SimulatedClock` + `SimulatedNetwork` + `SimulatorHarness` for deterministic-replay testing (9 tests)
* **CP25 — `crawler-bench`**: `BenchRunner` builder API for ad-hoc throughput + p50/p99 latency measurement (7 tests)

**Phase 5 — Production deployment:**

* **CP26 — `deploy/`**: Kubernetes manifests for agent StatefulSet, render Deployment, Unbound DaemonSet for local recursive DNS, plus operational README
* **CP27 — Observability**: `Metrics` SPI in `crawler-common` + `InMemoryMetrics` with Prometheus text-format exporter (8 tests)
* **CP28 — Security review**: `docs/security-review.md` covering threat model, verifiable controls, operator workflow, pre-launch checklist

**Phase 1 — Foundation (single-shard correctness):**

* **CP1 — `crawler-common`**: `Host`, `CanonicalUrl`, `AddressFamily`, `PriorityClass`, `FetchOutcome` (17 tests)
* **CP2 — `crawler-frontier` core**: Mercator-style two-tier scheduler with min-heap and exponential backoff (24 tests)
* **CP3 — `crawler-frontier` persistence**: snapshot/restore + RocksDB-backed store with crash-recovery test (9 tests)
* **CP4 — `crawler-dns`**: caching async resolver with positive + negative caches, request coalescing, per-domain limit, IP reverse-index (10 tests)
* **CP5 — `crawler-robots`**: RFC 9309 parser + cache with 4xx/5xx asymmetry and stale-serve (24 tests)
* **CP6 — `crawler-fetcher`**: JDK `HttpClient` with conditional GET, redirect handling, body cap, timeout (11 tests)
* **CP7 — `crawler-parser`**: jsoup parser, boilerplate stripping, link extraction with DOM-section, 64-bit Simhash (15 tests)
* **CP8 — `crawler-dedup`**: Bloom filter + two-tier dedup with exact-set fallback (20 tests)
* **CP9 — `crawler-node`**: end-to-end integration — composes all services, tested against an embedded `HttpServer` mini-website (3 tests)

**Phase 2 — At-scale primitives:**

* **CP10 — `SimhashIndex`**: Manku-Jain-Das partition-index — 64-bit fingerprints, k=3 default, sublinear lookup (10 tests)
* **CP11 — `RocksDbExactUrlSet`**: DRUM-style 256-bucket RocksDB exact-set for >10⁹ URLs (5 tests)
* **CP12 — `OpicComputer`**: online cash-distribution importance per Abiteboul-Preda-Cobena (7 tests)
* **CP13 — `RecrawlScheduler`**: Cho/Garcia-Molina adaptive recrawl with EWMA λ̂ + interval clamping (9 tests)
* **CP14 — `WarcWriter`**: WARC 1.1 (ISO 28500:2017) record format, rolling local-dir sink (5 tests)
* **CP15 — `CdxjIndexer`**: Common Crawl SURT-keyed lookup index (4 tests)
* **CP16 — `IndexerPipeline`**: three streams (documents, links, operational) over a `MessagePublisher` SPI (6 tests)

**Phase 3 — Distribution:**

* **CP17 — `ConsistentHashRing`**: UbiCrawler-style host-hash routing with virtual nodes (default 200/agent), `O(K/N)` rebalancing (12 tests)
* **CP18 — `MembershipState` + SWIM gossip + `MembershipAnchor` SPI**: ALIVE/SUSPECT/DEAD state machine, generation-based precedence, etcd-anchor reconciliation for split-brain (11 tests)
* **CP19 — `BatchingForwardingQueue` + `CrossAgentForwarder` SPI**: per-target batching (size or age trigger), retry-with-DLQ, in-memory forwarder for tests + future gRPC for production (8 tests)
* **CP20 — `HotHostRegistry` + `SharedPolitenessClock`**: operator-configured spillover policies, URL-hash within participant set, atomic shared politeness clock (Redis WATCH/MULTI/EXEC analog) (12 tests)

Build: JDK 17 (`jenv local 17.0`), Gradle 8.7.

Module status:

| Module | Status | Implements |
|---|---|---|
| `crawler-common` | foundational types + tests | RFC 3986 URL canonicalization, host normalization + SURT, fetch-outcome enum |
| `crawler-coordinator` | **ring + gossip + forwarding + hot-host done** | Consistent-hash ring with vnodes, SWIM gossip + etcd anchor SPI, batched cross-agent forwarder with DLQ, hot-host spillover with shared politeness clock |
| `crawler-frontier` | **core + persistence done** | Mercator two-tier scheduler + RocksDB snapshot store + crash-recovery test |
| `crawler-dns` | **caching resolver done** | Async caching resolver, request coalescing, IP reverse-index |
| `crawler-robots` | **RFC 9309 parser + cache done** | RFC 9309 parser, 24h cache, 4xx/5xx asymmetry, stale-serve |
| `crawler-fetcher` | **JDK HttpClient impl + tests** | HTTP/2-capable fetch, conditional GET (RFC 9110), redirect handling, body cap, timeout |
| `crawler-render` | stub (Phase 4) | Headless Chromium queue |
| `crawler-parser` | **jsoup impl + boilerplate + Simhash + tests** | jsoup parser, boilerplate stripper, link extractor with DOM-section, 64-bit Simhash |
| `crawler-dedup` | **Bloom + two-tier + Simhash partition + DRUM** | Bloom + exact-set + RocksDB-backed DRUM for >10⁹ URLs + Simhash partition index |
| `crawler-importance` | **OPIC + adaptive recrawl + tests** | Online OPIC cash-distribution + Cho/Garcia-Molina change-rate scheduler |
| `crawler-storage` | **WARC writer + CDXJ indexer + tests** | WARC 1.1 records, rolling local sink, SURT-keyed CDXJ lookup index |
| `crawler-indexer` | **IndexerPipeline + in-memory publisher + tests** | Three Kafka-shape streams: documents, links, operational |
| `crawler-node` | **end-to-end CrawlerNode + integration tests** | Composes all services; runs against embedded HttpServer; produces WARC + indexer events |
| `crawler-importance` | stub | OPIC online importance, Cho-G/M adaptive recrawl |
| `crawler-storage` | stub | WARC writer, CDXJ index, Cassandra per-URL state |
| `crawler-indexer` | stub | Three Kafka streams: documents, links, operational |
| `crawler-control-plane` | stub | Operator REST surface, audit log, takedown chain |
| `crawler-node` | stub | Embedded runtime tying modules together |
| `crawler-simulator` | stub | Deterministic-simulation testing harness |
| `crawler-bench` | stub | Load-generation harness |
| `crawler-admin` | stub | CLI for operator tasks |

## Build

Requires JDK 21+.

```sh
./gradlew build
./gradlew :crawler-common:test
```

## Module Structure

```
distributed-web-crawler/
├── crawler-common/          # Shared types, no dependencies
├── crawler-coordinator/     # Host-hash routing layer (depends on common)
├── crawler-frontier/        # Mercator two-tier scheduler
├── crawler-dns/             # Local recursive DNS client + cache
├── crawler-robots/          # RFC 9309 parser + cache
├── crawler-fetcher/         # HTTP/2 fetcher with conditional GET
├── crawler-render/          # Headless Chromium pool (Phase 2)
├── crawler-parser/          # HTML → text + links + Simhash pre-compute
├── crawler-dedup/           # URL Bloom+DRUM, content Simhash
├── crawler-importance/      # OPIC + adaptive recrawl scheduler
├── crawler-storage/         # WARC + CDXJ + per-URL state
├── crawler-indexer/         # Kafka emitters to downstream consumers
├── crawler-control-plane/   # Operator REST API, audit log
├── crawler-node/            # Embedded runtime
├── crawler-simulator/       # Deterministic test harness
├── crawler-bench/           # Load harness
├── crawler-admin/           # Operator CLI
├── docs/
│   ├── adr/                 # Architecture Decision Records
│   ├── implementation-plan.md
│   ├── research-checkpoint.md
│   └── code-companion.md
└── deploy/                  # K8s manifests, Helm charts (later)
```

## Architectural References

* [`raw-blog/web-crawler.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler.md) — standard 4K-word blog
* [`raw-blog/web-crawler-full.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler-full.md) — 23K-word full architectural reference
* [`docs/adr/`](docs/adr/) — Architecture Decision Records for non-obvious choices

## Implementation Order

Following the natural lifecycle of a URL:

1. **`crawler-common`** ← _current_ — foundation types
2. `crawler-frontier` — Mercator two-tier scheduling
3. `crawler-coordinator` — host-hash routing layer
4. `crawler-dns` — async caching resolver
5. `crawler-robots` — RFC 9309 parser + cache
6. `crawler-fetcher` — HTTP/2 fetcher + conditional GET
7. `crawler-parser` — jsoup + boilerplate stripper
8. `crawler-dedup` — Bloom + DRUM-style + Simhash
9. `crawler-importance` — OPIC + adaptive recrawl
10. `crawler-storage` — WARC + CDXJ + per-URL state
11. `crawler-indexer` — Kafka emission to downstream
12. `crawler-render` — headless Chromium (Phase 2)
13. `crawler-control-plane` — operator REST surface
14. `crawler-node` — embedded runtime composition
15. `crawler-simulator` + `crawler-bench` + `crawler-admin` — tools

## License

MIT.
