package com.hkg.crawler.frontier;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.FetchOutcome;
import com.hkg.crawler.common.Host;
import com.hkg.crawler.common.PriorityClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FrontierSnapshotTest {

    private Instant t0;

    @BeforeEach
    void setUp() {
        t0 = Instant.parse("2026-04-27T12:00:00Z");
    }

    private FrontierUrl url(String s, PriorityClass cls) {
        return new FrontierUrl(CanonicalUrl.of(s), cls, t0);
    }

    @Test
    @DisplayName("snapshot of empty Frontier round-trips")
    void empty_snapshot_round_trips() {
        InMemoryFrontier original = new InMemoryFrontier();
        FrontierSnapshot snap = original.snapshot(t0);

        InMemoryFrontier restored = InMemoryFrontier.restore(snap);
        assertThat(restored.stats().totalUrlsInBackQueues()).isZero();
        assertThat(restored.stats().activeHostCount()).isZero();
    }

    @Test
    @DisplayName("snapshot preserves URLs and host state across restore")
    void state_round_trips() {
        InMemoryFrontier original = new InMemoryFrontier();
        original.enqueue(url("http://a.com/1", PriorityClass.HIGH_OPIC));
        original.enqueue(url("http://a.com/2", PriorityClass.HIGH_OPIC));
        original.enqueue(url("http://b.com/1", PriorityClass.MEDIUM_OPIC));

        FrontierSnapshot snap = original.snapshot(t0);
        InMemoryFrontier restored = InMemoryFrontier.restore(snap);

        assertThat(restored.stats().activeHostCount()).isEqualTo(2);
        assertThat(restored.stats().totalUrlsInBackQueues()).isEqualTo(3);
        assertThat(restored.backQueueDepth(Host.of("a.com"))).isEqualTo(2);
        assertThat(restored.backQueueDepth(Host.of("b.com"))).isEqualTo(1);
    }

    @Test
    @DisplayName("snapshot preserves backoff factor")
    void backoff_factor_round_trips() {
        InMemoryFrontier original = new InMemoryFrontier();
        original.enqueue(url("http://flaky.com/a", PriorityClass.HIGH_OPIC));
        ClaimedUrl claim = original.claimNext(t0).orElseThrow();
        original.reportVerdict(claim.url(), FetchOutcome.SERVER_ERROR_5XX, t0.plusSeconds(1));
        original.reportVerdict(claim.url(), FetchOutcome.SERVER_ERROR_5XX, t0.plusSeconds(2));

        double before = original.hostStateFor(Host.of("flaky.com")).orElseThrow().backoffFactor();
        assertThat(before).isEqualTo(4.0);

        FrontierSnapshot snap = original.snapshot(t0);
        InMemoryFrontier restored = InMemoryFrontier.restore(snap);

        double after = restored.hostStateFor(Host.of("flaky.com")).orElseThrow().backoffFactor();
        assertThat(after).isEqualTo(4.0);
    }

    @Test
    @DisplayName("snapshot preserves quarantine state")
    void quarantine_state_round_trips() {
        InMemoryFrontier original = new InMemoryFrontier();
        original.enqueue(url("http://blocked.com/a", PriorityClass.HIGH_OPIC));
        original.quarantineHost(Host.of("blocked.com"));

        FrontierSnapshot snap = original.snapshot(t0);
        InMemoryFrontier restored = InMemoryFrontier.restore(snap);

        // Restored host should still be quarantined → no claims.
        assertThat(restored.claimNext(t0)).isEmpty();
        assertThat(restored.hostStateFor(Host.of("blocked.com")).orElseThrow().isQuarantined())
            .isTrue();
    }

    @Test
    @DisplayName("snapshot encodes deterministically and decodes back")
    void encode_decode_round_trips() {
        InMemoryFrontier original = new InMemoryFrontier();
        original.enqueue(url("http://example.com/page", PriorityClass.URGENT_RECRAWL));

        FrontierSnapshot snap = original.snapshot(t0);
        byte[] encoded = FrontierSnapshotStore.encode(snap);
        FrontierSnapshot decoded = FrontierSnapshotStore.decode(encoded);

        assertThat(decoded.formatVersion()).isEqualTo(FrontierSnapshot.CURRENT_FORMAT_VERSION);
        assertThat(decoded.takenAtEpochMs()).isEqualTo(t0.toEpochMilli());
        assertThat(decoded.hosts()).hasSize(1);
        assertThat(decoded.backQueueUrls()).hasSize(1);
        assertThat(decoded.backQueueUrls().get(0).url())
            .isEqualTo("http://example.com/page");
    }

    @Test
    @DisplayName("InMemoryFrontierSnapshotStore round-trips")
    void in_memory_store_round_trips() {
        InMemoryFrontier original = new InMemoryFrontier();
        original.enqueue(url("http://a.com/1", PriorityClass.HIGH_OPIC));

        try (FrontierSnapshotStore store = new InMemoryFrontierSnapshotStore()) {
            store.save(original.snapshot(t0));
            Optional<FrontierSnapshot> loaded = store.load();
            assertThat(loaded).isPresent();
            assertThat(loaded.get().backQueueUrls()).hasSize(1);
        }
    }

    @Test
    @DisplayName("RocksDB-backed store survives close + reopen — full crash-recovery test")
    void rocksdb_store_survives_close_and_reopen(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("frontier-rocksdb");

        // 1. Original Frontier with state.
        InMemoryFrontier original = new InMemoryFrontier();
        for (int i = 0; i < 10; i++) {
            original.enqueue(url("http://example.com/page" + i, PriorityClass.MEDIUM_OPIC));
        }
        original.enqueue(url("http://other.com/page", PriorityClass.HIGH_OPIC));
        ClaimedUrl claim = original.claimNext(t0).orElseThrow();
        original.reportVerdict(claim.url(), FetchOutcome.SERVER_ERROR_5XX, t0.plusSeconds(1));

        // 2. Save snapshot to RocksDB.
        try (FrontierSnapshotStore store = new RocksDbFrontierSnapshotStore(dbPath)) {
            store.save(original.snapshot(t0));
        }   // RocksDB closed here.

        // 3. New process: reopen RocksDB, load snapshot, restore Frontier.
        InMemoryFrontier restored;
        try (FrontierSnapshotStore store = new RocksDbFrontierSnapshotStore(dbPath)) {
            FrontierSnapshot snap = store.load().orElseThrow();
            restored = InMemoryFrontier.restore(snap);
        }

        // 4. Verify restored state matches original.
        assertThat(restored.stats().activeHostCount()).isEqualTo(2);
        // example.com had 10 URLs; we claimed 1, so 9 left + other.com 1 = 10 total
        assertThat(restored.stats().totalUrlsInBackQueues()).isEqualTo(10);
        // Backoff factor for example.com should be preserved
        Host example = Host.of("example.com");
        assertThat(restored.hostStateFor(example).orElseThrow().backoffFactor())
            .isEqualTo(2.0);
    }

    @Test
    @DisplayName("RocksDB store: empty when no snapshot saved")
    void rocksdb_store_empty_initially(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("empty-frontier");
        try (FrontierSnapshotStore store = new RocksDbFrontierSnapshotStore(dbPath)) {
            assertThat(store.load()).isEmpty();
        }
    }

    @Test
    @DisplayName("RocksDB store: latest snapshot overwrites previous")
    void rocksdb_store_overwrites(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("overwrite-test");

        InMemoryFrontier f1 = new InMemoryFrontier();
        f1.enqueue(url("http://a.com/1", PriorityClass.HIGH_OPIC));

        InMemoryFrontier f2 = new InMemoryFrontier();
        f2.enqueue(url("http://a.com/1", PriorityClass.HIGH_OPIC));
        f2.enqueue(url("http://a.com/2", PriorityClass.HIGH_OPIC));
        f2.enqueue(url("http://b.com/1", PriorityClass.MEDIUM_OPIC));

        try (FrontierSnapshotStore store = new RocksDbFrontierSnapshotStore(dbPath)) {
            store.save(f1.snapshot(t0));
            store.save(f2.snapshot(t0.plusSeconds(10)));   // overwrites f1

            FrontierSnapshot loaded = store.load().orElseThrow();
            assertThat(loaded.backQueueUrls()).hasSize(3);   // f2's state
        }
    }
}
