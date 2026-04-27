package com.hkg.crawler.dedup;

import com.hkg.crawler.common.CanonicalUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RocksDbExactUrlSetTest {

    @Test
    void add_and_contains_round_trip(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("urls-db");
        try (RocksDbExactUrlSet set = new RocksDbExactUrlSet(dbPath)) {
            assertThat(set.add("http://example.com/a")).isTrue();
            assertThat(set.contains("http://example.com/a")).isTrue();
            assertThat(set.contains("http://example.com/b")).isFalse();
        }
    }

    @Test
    void add_returns_false_for_duplicate(@TempDir Path tempDir) {
        try (RocksDbExactUrlSet set = new RocksDbExactUrlSet(tempDir.resolve("dup"))) {
            assertThat(set.add("http://x.com/1")).isTrue();
            assertThat(set.add("http://x.com/1")).isFalse();
        }
    }

    @Test
    void survives_close_and_reopen(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("durable");

        try (RocksDbExactUrlSet set = new RocksDbExactUrlSet(dbPath)) {
            for (int i = 0; i < 100; i++) {
                set.add("http://example.com/page" + i);
            }
        }

        try (RocksDbExactUrlSet set = new RocksDbExactUrlSet(dbPath)) {
            for (int i = 0; i < 100; i++) {
                assertThat(set.contains("http://example.com/page" + i))
                    .as("URL " + i + " must survive reopen")
                    .isTrue();
            }
            assertThat(set.contains("http://example.com/never-added")).isFalse();
        }
    }

    @Test
    void distributes_across_buckets(@TempDir Path tempDir) {
        // Insert URLs from many hosts; they should distribute across
        // the 256 buckets without runtime errors.
        try (RocksDbExactUrlSet set = new RocksDbExactUrlSet(tempDir.resolve("buckets"))) {
            for (int i = 0; i < 1000; i++) {
                set.add("http://host" + i + ".com/page");
            }
            assertThat(set.size()).isEqualTo(1000);
            for (int i = 0; i < 1000; i++) {
                assertThat(set.contains("http://host" + i + ".com/page")).isTrue();
            }
        }
    }

    @Test
    void integrates_with_TwoTierUrlDedup(@TempDir Path tempDir) {
        BloomFilter bloom = BloomFilter.create(10_000, 0.01);
        try (RocksDbExactUrlSet exactSet = new RocksDbExactUrlSet(tempDir.resolve("two-tier"))) {
            TwoTierUrlDedup dedup = new TwoTierUrlDedup(bloom, exactSet);

            assertThat(dedup.recordIfNew(CanonicalUrl.of("http://a.com/1")))
                .isEqualTo(UrlDedupVerdict.NEW);
            assertThat(dedup.recordIfNew(CanonicalUrl.of("http://a.com/1")))
                .isEqualTo(UrlDedupVerdict.DUPLICATE);
            assertThat(dedup.recordIfNew(CanonicalUrl.of("http://a.com/2")))
                .isEqualTo(UrlDedupVerdict.NEW);
        }
    }
}
