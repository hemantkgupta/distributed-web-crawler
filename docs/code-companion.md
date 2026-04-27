# Code Companion

Cross-reference between the [`web-crawler-full.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/web-crawler-full.md) blog and this codebase. For every architectural decision recorded in the blog, this doc points at the module + class + ADR (if any) that implements it.

## §1 Control Plane — `crawler-control-plane`

_Stub. No code yet._

## §2 Coordinator / Host Routing — `crawler-coordinator`

_Stub. No code yet._

## §3 Frontier — `crawler-frontier`

_Stub. No code yet._

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
