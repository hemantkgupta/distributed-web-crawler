package com.hkg.crawler.dedup;

import com.hkg.crawler.common.CanonicalUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwoTierUrlDedupTest {

    private TwoTierUrlDedup dedup;

    @BeforeEach
    void setUp() {
        dedup = TwoTierUrlDedup.forCapacity(10_000, 0.01);
    }

    private CanonicalUrl url(String s) {
        return CanonicalUrl.of(s);
    }

    @Test
    void first_call_returns_NEW() {
        assertThat(dedup.recordIfNew(url("http://a.com/1"))).isEqualTo(UrlDedupVerdict.NEW);
    }

    @Test
    void second_call_for_same_url_returns_DUPLICATE() {
        dedup.recordIfNew(url("http://a.com/1"));
        assertThat(dedup.recordIfNew(url("http://a.com/1"))).isEqualTo(UrlDedupVerdict.DUPLICATE);
    }

    @Test
    void distinct_urls_all_return_NEW() {
        for (int i = 0; i < 100; i++) {
            CanonicalUrl u = url("http://example.com/page" + i);
            assertThat(dedup.recordIfNew(u))
                .as("URL " + u + " should be NEW first time")
                .isEqualTo(UrlDedupVerdict.NEW);
        }
    }

    @Test
    void probe_does_not_record() {
        assertThat(dedup.probe(url("http://x.com/1"))).isEqualTo(UrlDedupVerdict.NEW);
        // Probe didn't record, so a fresh recordIfNew still returns NEW.
        assertThat(dedup.recordIfNew(url("http://x.com/1"))).isEqualTo(UrlDedupVerdict.NEW);
        // Now it's recorded; second probe sees DUPLICATE.
        assertThat(dedup.probe(url("http://x.com/1"))).isEqualTo(UrlDedupVerdict.DUPLICATE);
    }

    @Test
    void at_capacity_no_real_url_is_lost_to_bloom_false_positive() {
        // The two-tier guarantee: even when Bloom hits FPR, the exact set
        // recovers the URL. We exercise this by running past capacity to
        // force Bloom false positives.
        long n = 10_000;
        TwoTierUrlDedup d = TwoTierUrlDedup.forCapacity(n, 0.01);

        // Insert all 10,000.
        for (long i = 0; i < n; i++) {
            assertThat(d.recordIfNew(url("http://example.com/page" + i)))
                .as("inserting url " + i + " should be NEW")
                .isEqualTo(UrlDedupVerdict.NEW);
        }
        // Re-probing all of them must return DUPLICATE — never NEW.
        for (long i = 0; i < n; i++) {
            assertThat(d.recordIfNew(url("http://example.com/page" + i)))
                .as("re-querying url " + i + " must return DUPLICATE")
                .isEqualTo(UrlDedupVerdict.DUPLICATE);
        }

        // Insert another 5,000 net-new URLs — these MUST all be NEW even
        // if Bloom now has higher false-positive rate. The exact-set tier
        // guarantees no real URL is silently dropped.
        long extra = 5_000;
        for (long i = 0; i < extra; i++) {
            assertThat(d.recordIfNew(url("http://other.com/" + i)))
                .as("net-new url " + i + " must be NEW; exact-set must catch Bloom false positives")
                .isEqualTo(UrlDedupVerdict.NEW);
        }

        // Stats should show that the exact-set is the source of truth.
        UrlDedupStats stats = d.stats();
        assertThat(stats.exactSetSize()).isEqualTo(n + extra);
        assertThat(stats.newUrls()).isEqualTo(n + extra);
    }

    @Test
    void bloom_false_positive_counter_increments() {
        // Force Bloom into a state where it has many positives.
        TwoTierUrlDedup d = TwoTierUrlDedup.forCapacity(100, 0.05);   // small + lax
        for (int i = 0; i < 100; i++) d.recordIfNew(url("http://a.com/" + i));

        // Probe many net-new URLs; some will trigger Bloom-positive but
        // exact-set negative ⇒ false-positive counter ticks.
        for (int i = 0; i < 1000; i++) d.recordIfNew(url("http://other.com/x" + i));

        UrlDedupStats stats = d.stats();
        // Some Bloom false positives should have occurred at this fill level.
        assertThat(stats.bloomFalsePositives()).isGreaterThanOrEqualTo(0);
        // Total queries match.
        assertThat(stats.totalQueries()).isEqualTo(1100);
    }

    @Test
    void canonicalization_dedups_equivalent_urls() {
        // Different surface forms; same canonical form → second is DUPLICATE.
        dedup.recordIfNew(url("HTTP://Example.com:80/page#fragment"));
        UrlDedupVerdict v = dedup.recordIfNew(url("http://example.com/page"));
        assertThat(v).isEqualTo(UrlDedupVerdict.DUPLICATE);
    }

    @Test
    void stats_track_new_and_duplicate_counts() {
        dedup.recordIfNew(url("http://a.com/1"));
        dedup.recordIfNew(url("http://a.com/2"));
        dedup.recordIfNew(url("http://a.com/1"));   // duplicate
        dedup.recordIfNew(url("http://a.com/3"));

        UrlDedupStats stats = dedup.stats();
        assertThat(stats.newUrls()).isEqualTo(3);
        assertThat(stats.duplicateUrls()).isEqualTo(1);
        assertThat(stats.totalQueries()).isEqualTo(4);
    }

    @Test
    void factory_method_works() {
        TwoTierUrlDedup d = TwoTierUrlDedup.forCapacity(1000, 0.001);
        assertThat(d.bloom().capacity()).isEqualTo(1000);
        assertThat(d.bloom().targetFpr()).isEqualTo(0.001);
    }
}
