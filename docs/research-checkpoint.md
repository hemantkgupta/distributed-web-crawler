# Research Checkpoint

Tracks the relationship between the [`web-crawler-deep-research-report`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw/articles/web-crawler-deep-research-report.md) source and this implementation. Every primary-source decision recorded in the report should be cross-referenced here once the relevant module exists.

## Foundational papers and what they imply for this codebase

| Paper / system | Module | Impact |
|---|---|---|
| Mercator (Heydon & Najork, 1999) | `crawler-frontier` | Two-tier frontier: priority front queues + per-host back queues + min-heap on `next_fetch_time`. |
| IRLbot (Lee et al., 2009) | `crawler-dedup` | DRUM-style disk-first batched URL repository for >10⁹ URL scale. |
| Heritrix (Internet Archive) | `crawler-frontier` (admission rules) + `crawler-control-plane` | Scope-as-config, SURT-keyed Sheets, `PathologicalPathDecideRule` for trap detection at admission. |
| UbiCrawler (Boldi et al.) | `crawler-coordinator` | Host-hash consistent-hashing routing; no central coordinator. |
| BUbiNG (Boldi et al., 2014) | `crawler-dns` + `crawler-frontier` (politeness) | Local recursive DNS as essential; per-host AND per-IP politeness. |
| Caffeine (Google, 2010 disclosure) | `crawler-indexer` | Continuous-incremental indexing model; backpressure between crawl and index. |
| Common Crawl operational model | `crawler-storage` | WARC + WAT + WET + CDXJ + Parquet separation of raw / metadata / index / analytics. |

## Protocols pinned

| Protocol | Source | Module |
|---|---|---|
| RFC 9309 (Robots Exclusion Protocol) | normative | `crawler-robots` |
| RFC 9110 (HTTP Semantics) — conditional GET | normative | `crawler-fetcher` |
| RFC 3986 (URI Generic Syntax) — canonicalization | normative | `crawler-common` |
| sitemaps.org schema | non-normative but standard | `crawler-control-plane` (sitemap submission) |

## Algorithms cited in the report and their implementations

| Algorithm | Implementation location | Status |
|---|---|---|
| Bloom-filter sizing math (`m ≈ -n ln(f)/(ln 2)²`) | `crawler-dedup` | not yet implemented |
| 64-bit Simhash with k=3 partition index (Manku-Jain-Das) | `crawler-dedup` | not yet implemented |
| MinHash + LSH alternative | `crawler-dedup` (alternative path) | not planned for v1 |
| OPIC online importance (Abiteboul-Preda-Cobena) | `crawler-importance` | not yet implemented |
| Cho-Garcia-Molina adaptive recrawl | `crawler-importance` | not yet implemented |
| DRUM disk-first dedup | `crawler-dedup` | not yet implemented |
| Boilerplate stripping (Readability-style) | `crawler-parser` | not yet implemented |
| Path-shape trap heuristics (PathologicalPathDecideRule) | `crawler-frontier` | not yet implemented |

## Open questions (per the deep-research report)

* **Googlebot/Caffeine internals** — proprietary; we approximate via OPIC + adaptive recrawl + continuous-incremental indexer pipeline.
* **Cho-Garcia-Molina theorem detail** — full primary-text quoting deferred; behavior matches the high-confidence summary in the deep-research report.
* **BUbiNG IP-level politeness exact algorithm** — paper text is the authoritative reference; our implementation follows the `max(host_ready, ip_ready)` rule from the report.

This file is updated as modules are implemented.
