package com.hkg.crawler.dedup;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimhashIndexTest {

    @Test
    void empty_index_finds_no_candidates() {
        SimhashIndex idx = new SimhashIndex();
        assertThat(idx.findCandidates(0xdeadbeefL)).isEmpty();
        assertThat(idx.hasNearDuplicate(0xdeadbeefL)).isFalse();
    }

    @Test
    void exact_match_is_found_as_candidate() {
        SimhashIndex idx = new SimhashIndex();
        long fp = 0x1234567890abcdefL;
        idx.add(fp);
        // Querying with the same fingerprint excludes self by design.
        assertThat(idx.findCandidates(fp)).isEmpty();
        // But a fingerprint with a single bit flipped is found.
        long oneBitOff = fp ^ 0x1L;
        assertThat(idx.findCandidates(oneBitOff)).contains(fp);
    }

    @Test
    void near_duplicate_within_k3_is_detected() {
        SimhashIndex idx = new SimhashIndex(3);
        long fp = 0x1234567890abcdefL;
        idx.add(fp);

        // 3 bits flipped — within threshold.
        long threeBitsOff = fp ^ 0b111L;
        assertThat(idx.hasNearDuplicate(threeBitsOff)).isTrue();
        Optional<Long> nearest = idx.findNearestNearDup(threeBitsOff);
        assertThat(nearest).hasValue(fp);
    }

    @Test
    void distance_greater_than_k_returns_no_match() {
        SimhashIndex idx = new SimhashIndex(3);
        long fp = 0L;
        idx.add(fp);
        // Flip 8 bits — well outside k=3 threshold.
        long farFp = 0xffL;
        assertThat(idx.hasNearDuplicate(farFp)).isFalse();
    }

    @Test
    void candidate_set_unions_across_partitions() {
        SimhashIndex idx = new SimhashIndex(3);
        // Two fingerprints sharing different lanes with the query.
        long a = 0x0000_0000_0000_FFFFL;   // lane A = 0xFFFF
        long b = 0x0000_0000_FFFF_0000L;   // lane B = 0xFFFF
        idx.add(a);
        idx.add(b);

        // Query shares lane A with `a` and lane B with `b`.
        long query = 0x0000_0000_FFFF_FFFFL;
        Set<Long> candidates = idx.findCandidates(query);
        assertThat(candidates).contains(a, b);
    }

    @Test
    void rejects_invalid_k() {
        assertThatThrownBy(() -> new SimhashIndex(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimhashIndex(8))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void at_scale_finds_near_dup_in_corpus_of_10k() {
        SimhashIndex idx = new SimhashIndex(3);
        Random r = new Random(42);
        // Insert 10K random fingerprints.
        for (int i = 0; i < 10_000; i++) {
            idx.add(r.nextLong());
        }
        // Insert a target fingerprint and a 2-bit-perturbed near-dup.
        long target = 0x0123_4567_89ab_cdefL;
        long nearDup = target ^ 0b11L;   // 2 bits flipped
        idx.add(target);

        Optional<Long> match = idx.findNearestNearDup(nearDup);
        assertThat(match).hasValue(target);
    }

    @Test
    void partition_count_is_k_plus_one() {
        assertThat(new SimhashIndex(1).partitionCount()).isEqualTo(2);
        assertThat(new SimhashIndex(3).partitionCount()).isEqualTo(4);
        assertThat(new SimhashIndex(7).partitionCount()).isEqualTo(8);
    }

    @Test
    void size_reflects_distinct_fingerprints() {
        SimhashIndex idx = new SimhashIndex();
        idx.add(0xAAAAL);
        idx.add(0xBBBBL);
        idx.add(0xAAAAL);   // duplicate of first
        assertThat(idx.size()).isEqualTo(2);
    }

    @Test
    void closest_match_is_the_smallest_distance() {
        SimhashIndex idx = new SimhashIndex(5);
        long fp = 0L;
        long farMatch  = 0b11111L;        // 5 bits off (at the threshold)
        long nearMatch = 0b1L;            // 1 bit off
        idx.add(farMatch);
        idx.add(nearMatch);

        assertThat(idx.findNearestNearDup(fp)).hasValue(nearMatch);
    }
}
