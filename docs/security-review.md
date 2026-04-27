# Security Review (CP28)

A deliberately practical security review for the distributed-web-crawler. Focuses on the things that matter for a system that fetches arbitrary public-web content, processes it, and exposes operator controls.

## Threat model

| Threat | Severity | Mitigation |
|---|---|---|
| Malicious HTML / SSRF in parser | Medium | Treat all fetched content as untrusted; jsoup is parse-only (no script execution); render service is sandboxed (see below). |
| Server-side resource exhaustion via large responses | High | Hard 10 MB body cap in `crawler-fetcher`; HEAD-only for binaries; per-fetch wall-clock budget. |
| Tamper of audit log | High | SHA-256 hash chain in `AuditEvent` — `findFirstTamperedEvent()` walks the chain post-hoc to detect modification. |
| Operator account compromise | High | OIDC SSO via corporate IdP for human operators; mTLS with internal CA for machine-to-machine; audit every action with the actor identity. |
| Crawler hammering target servers | High → reputation risk | Per-host AND per-IP politeness clocks (BUbiNG); 4xx/5xx asymmetry on robots.txt; exponential backoff on 429/503. |
| Headless browser escape (render service) | Medium | Seccomp-BPF (`RuntimeDefault` profile); separate Chromium pod-per-render-burst; pod recycle every 100 renders; `allowPrivilegeEscalation: false`. |
| Secrets exposure | High | Sealed-secrets or external-secrets for production; `etcd` keys for shared state are not secret-class. |
| Cross-region data residency | Per-jurisdiction | WARC archive replication only for DR (one DR region); per-URL state stays regional. |
| Fingerprinting / abuse of crawl traffic | Medium | Honest `User-Agent`, `From:` header pointing to abuse mailbox, public bot-info page. Treat takedown requests via the Control Plane. |
| GDPR personal-data processing | Per-jurisdiction | Scope rules to exclude regions/sites; takedown chain actually removes from index + WARC suppress flag; 7-year retention is operator-configurable per region. |

## Verifiable controls (built in)

* **Audit log integrity** — `AuditEvent.verifyChainHash()` + `AuditLog.findFirstTamperedEvent()` ensure operator-action history can be verified after the fact.
* **Robots.txt 4xx/5xx asymmetry** — enforced in `RobotsCache.refresh`; tested in `RobotsCacheTest`.
* **Per-host + per-IP politeness** — `Frontier.HostState` + `DnsResolver.hostsSharingIp()` feed both clocks.
* **Body size cap** — `JdkHttpFetcher` truncates above `maxBodyBytes`; tested.
* **Render isolation** — separate Kubernetes Deployment with its own Seccomp profile; pod recycle bounds memory drift.
* **Two-tier dedup** — `TwoTierUrlDedup` guarantees no real URL is silently dropped to a Bloom false positive; tested at deliberate Bloom saturation.

## Operator workflow controls

* **Pause / resume** the entire crawl (`AdminCli pause` / `resume`) — useful during incidents.
* **Quarantine a host** (`crawler-admin host quarantine x.com`) — immediately stops fetches; URLs already in the frontier stay there until explicit drain.
* **Takedown a URL** (`crawler-admin takedown URL REASON`) — atomic chain across indexer + storage + frontier + dedup; recorded in audit log.
* **Scope updates** are versioned; rolling back a bad scope change is a `POST /v1/scope/activate?version=N-1`.

## Operational checklist before going live

- [ ] Audit log writes are mirrored to ClickHouse with at-least-once semantics.
- [ ] OIDC client registered with corporate IdP for the operator console.
- [ ] mTLS certificates issued from internal CA for inter-agent gRPC.
- [ ] DNS resolver runs as the local DaemonSet (`unbound-daemonset.yaml`) — confirm by running `dig @host_ip example.com` from inside an agent pod.
- [ ] Bot abuse mailbox is monitored; takedown SLA documented.
- [ ] WARC retention lifecycle policy on S3 bucket.
- [ ] Cross-region DR replication on critical paths.
- [ ] Prometheus alerts configured for: fetch error rate spike, frontier depth growth, host backoff distribution skew, dedup hit rate drop.
- [ ] Scope rules exclude jurisdictions with restrictive regulations (configurable per region).
- [ ] Rate-limit operator API endpoints (the control plane is operator-latency, not DDoS-resistant by default).

## Open questions

* **Crawl-traffic anonymity** — should agents tunnel through Tor for jurisdictions that observe outbound IPs? Out of scope for v1.
* **End-to-end encryption of WARC at rest** — currently relies on S3 server-side encryption. KMS-backed envelope encryption is a Phase 6 add.
* **HSM-anchored audit log signing** — the SHA-256 hash chain is tamper-detectable but not cryptographically signed. A KMS HMAC over each event's `chainHash` is the upgrade path.
