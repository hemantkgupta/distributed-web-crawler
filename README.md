# Distributed Web Crawler

A Java 21 multi-module reference implementation of a global-scale web crawler. Companion code for the [`web-crawler`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler.md) and [`web-crawler-full`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler-full.md) blog posts in the CSE wiki.

The blog describes the architecture in twelve services. This repo organizes the implementation into Gradle modules with the same boundaries — each service is a module, each module is independently buildable and testable.

## Status

**Checkpoints 1–8 complete.** 127 unit tests passing across 10 test classes; full multi-module build green.

* **CP1 — `crawler-common`**: `Host`, `CanonicalUrl`, `AddressFamily`, `PriorityClass`, `FetchOutcome` (17 tests)
* **CP2 — `crawler-frontier` core**: Mercator-style two-tier scheduler with min-heap and exponential backoff (24 tests)
* **CP3 — `crawler-frontier` persistence**: snapshot/restore + RocksDB-backed store with crash-recovery test (9 tests)
* **CP4 — `crawler-dns`**: caching async resolver with positive + negative caches, request coalescing, per-domain limit, IP reverse-index (10 tests)
* **CP5 — `crawler-robots`**: RFC 9309 parser + cache with 4xx/5xx asymmetry and stale-serve (24 tests)
* **CP6 — `crawler-fetcher`**: JDK `HttpClient`-based fetcher with conditional GET, redirect handling, body cap, timeout (11 tests)
* **CP7 — `crawler-parser`**: jsoup HTML parsing, boilerplate stripping, link extraction with DOM-section attribution, 64-bit Simhash (15 tests)
* **CP8 — `crawler-dedup`**: Bloom filter (sizing math) + two-tier dedup with exact-set fallback (20 tests)

Build: JDK 17 (`jenv local 17.0`), Gradle 8.7.

Module status:

| Module | Status | Implements |
|---|---|---|
| `crawler-common` | foundational types + tests | RFC 3986 URL canonicalization, host normalization + SURT, fetch-outcome enum |
| `crawler-coordinator` | stub | UbiCrawler-style consistent-hash host routing, gossip membership |
| `crawler-frontier` | **core + persistence done** | Mercator two-tier scheduler + RocksDB snapshot store + crash-recovery test |
| `crawler-dns` | **caching resolver done** | Async caching resolver, request coalescing, IP reverse-index |
| `crawler-robots` | **RFC 9309 parser + cache done** | RFC 9309 parser, 24h cache, 4xx/5xx asymmetry, stale-serve |
| `crawler-fetcher` | **JDK HttpClient impl + tests** | HTTP/2-capable fetch, conditional GET (RFC 9110), redirect handling, body cap, timeout |
| `crawler-render` | stub | Headless Chromium queue (Phase 2) |
| `crawler-parser` | **jsoup impl + boilerplate + Simhash + tests** | jsoup parser, boilerplate stripper, link extractor with DOM-section, 64-bit Simhash |
| `crawler-dedup` | **Bloom + two-tier exact-set + tests** | Bloom filter (sizing math), two-tier dedup; DRUM disk-first backstop in CP11 |
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
