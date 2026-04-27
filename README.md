# Distributed Web Crawler

A Java 21 multi-module reference implementation of a global-scale web crawler. Companion code for the [`web-crawler`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler.md) and [`web-crawler-full`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler-full.md) blog posts in the CSE wiki.

The blog describes the architecture in twelve services. This repo organizes the implementation into Gradle modules with the same boundaries — each service is a module, each module is independently buildable and testable.

## Status

Checkpoint 2 — `crawler-frontier` core implemented as in-memory Mercator-style two-tier scheduler:

* `Frontier` interface — enqueue, claimNext, reportVerdict, quarantine
* `InMemoryFrontier` — front queues + per-host back queues + min-heap by `next_fetch_time`, with lazy invalidation via per-host generation counter
* `HostState` — politeness clock, exponential backoff (×2 on 429/503, ×0.95 on success, capped at 64×), Crawl-delay override
* `FrontierUrl`, `ClaimedUrl`, `FrontierStats` — public records

24 unit tests passing across `HostStateTest` + `InMemoryFrontierTest`. Full multi-module build green.

Module status:

| Module | Status | Implements |
|---|---|---|
| `crawler-common` | foundational types + tests | RFC 3986 URL canonicalization, host normalization + SURT, fetch-outcome enum |
| `crawler-coordinator` | stub | UbiCrawler-style consistent-hash host routing, gossip membership |
| `crawler-frontier` | **in-memory implementation + tests** | Mercator two-tier scheduler with min-heap; persistence in CP3 |
| `crawler-dns` | stub | Async caching resolver, request coalescing, IP reverse-index |
| `crawler-robots` | stub | RFC 9309 parser, 24h cache, 4xx/5xx asymmetry |
| `crawler-fetcher` | stub | HTTP/2 fetch with virtual threads, conditional GET, WARC writer |
| `crawler-render` | stub | Headless Chromium queue (Phase 2) |
| `crawler-parser` | stub | jsoup HTML parser, boilerplate stripper, link extractor |
| `crawler-dedup` | stub | Bloom + DRUM-style URL dedup, Simhash content dedup |
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
