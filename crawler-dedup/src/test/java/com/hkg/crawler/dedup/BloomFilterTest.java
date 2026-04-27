package com.hkg.crawler.dedup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BloomFilterTest {

    @Test
    void empty_filter_says_no_to_everything() {
        BloomFilter f = BloomFilter.create(1000, 0.01);
        assertThat(f.mightContain("any")).isFalse();
        assertThat(f.mightContain("thing")).isFalse();
    }

    @Test
    void added_element_always_returns_might_contain() {
        BloomFilter f = BloomFilter.create(1000, 0.01);
        f.add("https://example.com/page");
        f.add("https://example.com/other");

        // Both must always be "might contain" — Bloom never has false negatives.
        assertThat(f.mightContain("https://example.com/page")).isTrue();
        assertThat(f.mightContain("https://example.com/other")).isTrue();
    }

    @Test
    void no_false_negatives_at_design_capacity() {
        BloomFilter f = BloomFilter.create(10_000, 0.01);
        for (int i = 0; i < 10_000; i++) {
            f.add("url-" + i);
        }
        // Every previously-added element MUST be reported as "might contain".
        for (int i = 0; i < 10_000; i++) {
            assertThat(f.mightContain("url-" + i))
                .as("Bloom must never return false negative for added elements")
                .isTrue();
        }
    }

    @Test
    void false_positive_rate_close_to_target_at_capacity() {
        long n = 10_000;
        double targetFpr = 0.01;
        BloomFilter f = BloomFilter.create(n, targetFpr);
        for (long i = 0; i < n; i++) {
            f.add("url-" + i);
        }
        // Probe 10,000 unseen URLs; count false positives.
        int falsePositives = 0;
        int trials = 10_000;
        for (int i = 0; i < trials; i++) {
            if (f.mightContain("never-seen-" + i)) falsePositives++;
        }
        double observedFpr = (double) falsePositives / trials;
        // Allow 4× target as upper bound for test stability (statistical noise).
        assertThat(observedFpr).isLessThan(targetFpr * 4);
    }

    @Test
    void sizing_at_one_percent_uses_approximately_9_6_bits_per_element() {
        BloomFilter f = BloomFilter.create(10_000, 0.01);
        double bitsPerElement = (double) f.bitCount() / 10_000;
        // Target is ~9.585 bits/element; allow ±0.5
        assertThat(bitsPerElement).isBetween(9.0, 10.5);
    }

    @Test
    void sizing_at_point_one_percent_uses_approximately_14_4_bits_per_element() {
        BloomFilter f = BloomFilter.create(10_000, 0.001);
        double bitsPerElement = (double) f.bitCount() / 10_000;
        assertThat(bitsPerElement).isBetween(13.5, 15.5);
    }

    @Test
    void hash_count_is_optimal() {
        // Optimal k for 1% FPR ≈ 7
        BloomFilter f = BloomFilter.create(10_000, 0.01);
        assertThat(f.hashCount()).isBetween(6, 8);
    }

    @Test
    void rejects_bad_inputs() {
        assertThatThrownBy(() -> BloomFilter.create(0, 0.01))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BloomFilter.create(100, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BloomFilter.create(100, 1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void add_returns_true_on_first_insert_and_false_when_bits_already_set() {
        BloomFilter f = BloomFilter.create(1000, 0.01);
        assertThat(f.add("alpha")).isTrue();
        assertThat(f.add("alpha")).isFalse();   // identical insert — no new bits
    }

    @Test
    void estimated_fpr_grows_with_fill() {
        BloomFilter f = BloomFilter.create(10_000, 0.01);
        double initialFpr = f.estimatedFalsePositiveRate();
        for (int i = 0; i < 10_000; i++) f.add("url-" + i);
        double finalFpr = f.estimatedFalsePositiveRate();
        assertThat(initialFpr).isZero();
        assertThat(finalFpr).isPositive().isLessThan(0.05);
    }

    @Test
    void approximate_size_matches_bit_count() {
        BloomFilter f = BloomFilter.create(1_000_000, 0.01);
        // ~9.6 bits/elem × 1M / 8 = ~1.2 MB
        assertThat(f.approximateSizeBytes()).isBetween(1_100_000L, 1_300_000L);
    }
}
