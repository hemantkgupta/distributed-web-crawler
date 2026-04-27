# Code Companion

Cross-reference between the [`web-crawler-full.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler-full.md) blog and this codebase. For every architectural decision recorded in the blog, this doc points at the module + class + ADR (if any) that implements it.

## §1 Control Plane — `crawler-control-plane`

_Stub. No code yet._

## §2 Coordinator / Host Routing — `crawler-coordinator`

_Stub. No code yet._

## §3 Frontier — `crawler-frontier`

In-memory Mercator-style two-tier scheduler (Checkpoint 2). Public API:

| Class | Purpose |
|---|---|
| `Frontier` (interface) | Public contract: `enqueue` / `claimNext` / `reportVerdict` / `quarantineHost` / `releaseHost` / `stats` |
| `InMemoryFrontier` | Front queues (per `PriorityClass`) + per-host back queues + ready-host min-heap with generation-counter lazy invalidation |
| `HostState` | Per-host politeness state: `next_fetch_time`, `crawl_delay`, `backoff_factor`, `consecutive_errors`, `back_queue_depth`, `quarantined` |
| `FrontierUrl` | A URL waiting in front or back queue |
| `ClaimedUrl` | A URL handed to a fetcher worker |
| `FrontierStats` | Snapshot for observability |

Implements the blueprint's §3 (Frontier Service) decisions:

| Blog decision | Implementation |
|---|---|
| Two-tier (front + back) | `frontQueues` + `backQueues` maps; `drainFrontQueueFor()` and `refillBackQueueFromFront()` route between them |
| Min-heap on `next_fetch_time` | `readyHeap` `PriorityQueue<HeapEntry>` |
| Lazy heap invalidation | `hostGeneration` counter; stale entries skipped on pop |
| Exponential backoff | `HostState.recordVerdict()` — `× 2.0` on 429/503/timeout/network, `× 0.95` on success, capped at `MAX_BACKOFF=64.0` |
| 4xx leaves backoff alone | `HostState.recordVerdict()` switches on `outcome.isBackoffSignal()` and `outcome.isSuccess()` only |
| Crawl-delay override | `HostState.setCrawlDelay()` — used to honor robots `Crawl-delay:` |
| Quarantine | `Frontier.quarantineHost()` / `releaseHost()` flips `HostState.quarantined`; `claimNext` skips quarantined hosts |

Out of scope for Checkpoint 2:
* RocksDB persistence (Checkpoint 3)
* Multi-shard host-hash routing (Checkpoint 17)
* Hot-host spillover to Redis (Checkpoint 20)
* Front-queue weighted selection (currently first-fit by host)

## §4 DNS — `crawler-dns`

_Stub. No code yet._

## §5 Robots — `crawler-robots`

_Stub. No code yet._

## §6 Fetcher — `crawler-fetcher`

_Stub. No code yet._

## §7 Render — `crawler-render`

_Stub. No code yet._

## §8 Parser — `crawler-parser`

_Stub. No code yet._

## §9 Dedup — `crawler-dedup`

_Stub. No code yet._

## §10 Importance & Recrawl — `crawler-importance`

_Stub. No code yet._

## §11 Storage / Archive — `crawler-storage`

_Stub. No code yet._

## §12 Indexer Pipeline — `crawler-indexer`

_Stub. No code yet._

---

## Foundation Types — `crawler-common`

| Type | Purpose | Blog reference |
|---|---|---|
| `Host` | Authority component, normalized + SURT | §2 (host-hash routing key), §11 (CDXJ key) |
| `CanonicalUrl` | RFC 3986 safe-canonicalized URL | §3 (frontier key), §9 (dedup key), §8 (extracted-link target) |
| `AddressFamily` | IPv4/IPv6 dispatch | §4 (DNS cache key) |
| `PriorityClass` | Front-queue priority bucket | §3 (front queues) |
| `FetchOutcome` | Verdict reported by Fetcher → Frontier | §6 (verdict report), §3 (host backoff factor update) |
