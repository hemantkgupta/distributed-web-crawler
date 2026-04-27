# Kubernetes Deployment

Production deployment manifests for the distributed-web-crawler. The single-process `crawler-node` runs fine on a laptop; this directory describes how to scale it to N agents on Kubernetes.

## Components

| Resource | Purpose | Replicas |
|---|---|---|
| `crawler-agent` (StatefulSet) | One pod per agent; persistent volume per pod for RocksDB state and WARC buffer | N (per cluster size) |
| `crawler-render` (Deployment) | Headless Chromium pool, separate from the main agent; CPU + memory isolated | ~5% of agent count |
| `etcd` (StatefulSet) | Membership anchor + scope config | 3 (quorum) |
| `redis` (StatefulSet) | URL Bloom filter + hot-host shared politeness clock | 1 master + 2 replicas |
| `kafka` (StatefulSet) | Indexer-pipeline streams + cross-region replication | 3+ |
| `cassandra` (StatefulSet) | Per-URL OPIC + recrawl state at billion-URL scale | 6 |
| `postgres` (StatefulSet) | Control-plane state (scope versions, takedown registry) | 1 master + 1 replica |
| `clickhouse` (StatefulSet) | Operational stream + audit log analytics | 3 (sharded) |
| `unbound` (DaemonSet) | Local recursive DNS resolver — colocated with each agent node per BUbiNG's "essential" finding | 1 per node |
| `prometheus` (StatefulSet) | Metrics scrape + alerting | 1 |
| `grafana` (Deployment) | Dashboards | 1 |

## Files

- [`crawler-agent.yaml`](crawler-agent.yaml) — StatefulSet for the agent pods
- [`crawler-render.yaml`](crawler-render.yaml) — Deployment for the Chromium render pool
- [`unbound-daemonset.yaml`](unbound-daemonset.yaml) — DaemonSet for local recursive DNS
- [`crawler-services.yaml`](crawler-services.yaml) — ClusterIP services for inter-pod gRPC
- [`prometheus-config.yaml`](prometheus-config.yaml) — Prometheus scrape config

## Sizing

| Agent count | Sustained pages/sec | Notes |
|---|---|---|
| 1 | ~500 | Single-shard demo |
| 8 | ~5,000 | Small production deployment |
| 32 | ~10,000 | Search-engine class baseline |
| 128 | ~40,000 | Whole-web crawler |

Per-agent resource request: 8 CPU / 16 GB / 200 GB SSD (RocksDB + WARC buffer).
Per-render-pod: 4 CPU / 8 GB; recycled every ~100 renders to bound Chromium memory drift.

## Ordering of operations on initial deploy

1. Bring up `etcd` first (membership anchor).
2. Bring up `redis`, `kafka`, `cassandra`, `postgres`, `clickhouse` (data plane).
3. Bring up `unbound` DaemonSet on every node (so agent pods see a local resolver).
4. Bring up `crawler-agent` StatefulSet — agents register on `etcd` on startup.
5. Bring up `crawler-render` Deployment.
6. Bring up `prometheus` + `grafana`.

## Rolling upgrade

Agent pods are stateful (per-shard frontier, RocksDB, hint replay state) but the in-flight URLs are durable, so a pod can be drained and replaced safely. The order:

1. `kubectl exec crawler-agent-N -- crawler-admin host quarantine <agent-id>` — operator gracefully drains the agent's incoming work.
2. Wait for the agent's Frontier depth to reach zero.
3. Kill the pod; the StatefulSet brings up a fresh one with the same persistent volume.
4. The new pod restores from RocksDB checkpoint and rejoins gossip.

## Multi-region

Three regions (US-East / EU-West / APAC), each with its own full deployment. Cross-region:

- WARC archive replicates via S3 cross-region replication for DR.
- Search index replicates via Elasticsearch CCR.
- Cassandra and Redis stay regional (no cross-region politeness coordination needed since each region owns its host bucket).
