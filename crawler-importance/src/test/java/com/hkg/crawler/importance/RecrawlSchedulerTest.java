package com.hkg.crawler.importance;

import com.hkg.crawler.common.CanonicalUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecrawlSchedulerTest {

    private OpicComputer opic;
    private RecrawlScheduler scheduler;
    private Instant t0;

    @BeforeEach
    void setUp() {
        opic = new OpicComputer(1.0);
        scheduler = new RecrawlScheduler(opic, Duration.ofHours(1));
        t0 = Instant.parse("2026-04-27T12:00:00Z");
    }

    private CanonicalUrl url(String s) { return CanonicalUrl.of(s); }

    @Test
    void register_creates_entry_with_initial_interval() {
        RecrawlEntry e = scheduler.register(url("http://a.com/"), t0);
        assertThat(e.url().value()).isEqualTo("http://a.com/");
        assertThat(e.currentInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(e.nextRecrawlTime()).isEqualTo(t0.plusSeconds(3600));
    }

    @Test
    void content_changed_halves_interval() {
        scheduler.register(url("http://a.com/"), t0);
        scheduler.recordFetch(url("http://a.com/"), true, t0.plusSeconds(3600));
        RecrawlEntry e = scheduler.entryFor(url("http://a.com/")).orElseThrow();
        assertThat(e.currentInterval()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void content_unchanged_doubles_interval() {
        scheduler.register(url("http://a.com/"), t0);
        scheduler.recordFetch(url("http://a.com/"), false, t0.plusSeconds(3600));
        RecrawlEntry e = scheduler.entryFor(url("http://a.com/")).orElseThrow();
        assertThat(e.currentInterval()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void interval_clamped_to_max() {
        scheduler.register(url("http://a.com/"), t0);
        Instant cursor = t0;
        // 30 unchanged fetches should cap interval at MAX_INTERVAL.
        for (int i = 0; i < 30; i++) {
            cursor = cursor.plusSeconds(3600);
            scheduler.recordFetch(url("http://a.com/"), false, cursor);
        }
        RecrawlEntry e = scheduler.entryFor(url("http://a.com/")).orElseThrow();
        assertThat(e.currentInterval()).isEqualTo(RecrawlEntry.MAX_INTERVAL);
    }

    @Test
    void interval_clamped_to_min() {
        scheduler.register(url("http://a.com/"), t0);
        Instant cursor = t0;
        // Many "changed" fetches should floor interval at MIN_INTERVAL.
        for (int i = 0; i < 30; i++) {
            cursor = cursor.plusSeconds(3600);
            scheduler.recordFetch(url("http://a.com/"), true, cursor);
        }
        RecrawlEntry e = scheduler.entryFor(url("http://a.com/")).orElseThrow();
        assertThat(e.currentInterval()).isEqualTo(RecrawlEntry.MIN_INTERVAL);
    }

    @Test
    void due_recrawls_returns_only_overdue_urls() {
        scheduler.register(url("http://a.com/"), t0);
        scheduler.register(url("http://b.com/"), t0);

        // 30 minutes later — neither overdue (both have 1h initial interval).
        Instant t30 = t0.plusSeconds(1800);
        assertThat(scheduler.dueRecrawlsByPriority(t30, 10)).isEmpty();
        assertThat(scheduler.overdueCount(t30)).isZero();

        // 90 minutes later — both overdue.
        Instant t90 = t0.plusSeconds(5400);
        List<CanonicalUrl> due = scheduler.dueRecrawlsByPriority(t90, 10);
        assertThat(due).contains(url("http://a.com/"), url("http://b.com/"));
        assertThat(scheduler.overdueCount(t90)).isEqualTo(2);
    }

    @Test
    void priority_orders_by_importance_times_change_rate() {
        // a.com has high OPIC history (multiple inbound visitors push cash to it);
        // b.com has only its initial cash.
        scheduler.register(url("http://a.com/"), t0);
        scheduler.register(url("http://b.com/"), t0);

        // Three inbound visitors push cash to a.com.
        for (int i = 0; i < 3; i++) {
            opic.visit(url("http://referrer" + i + ".com/"), List.of(url("http://a.com/")));
        }
        // Now visit a.com to accumulate the inbound cash into its history.
        opic.visit(url("http://a.com/"), List.of(url("http://other.com/")));

        // b.com has only its initial 1.0 cash; visiting accumulates 1.0 history.
        opic.visit(url("http://b.com/"), List.of(url("http://other.com/")));

        // a.com history should be ~4.0 (1.0 initial + 3.0 inbound); b.com ~1.0.
        assertThat(opic.historyOf(url("http://a.com/")))
            .isGreaterThan(opic.historyOf(url("http://b.com/")));

        // Both overdue.
        Instant tDue = t0.plusSeconds(7200);
        List<CanonicalUrl> ordered = scheduler.dueRecrawlsByPriority(tDue, 10);
        assertThat(ordered.get(0)).isEqualTo(url("http://a.com/"));
    }

    @Test
    void recordFetch_auto_registers_unknown_url() {
        // No prior register → recordFetch creates entry.
        scheduler.recordFetch(url("http://x.com/"), true, t0);
        assertThat(scheduler.entryFor(url("http://x.com/"))).isPresent();
    }

    @Test
    void due_recrawls_limit_is_honored() {
        // 5 URLs all overdue.
        for (int i = 0; i < 5; i++) {
            scheduler.register(url("http://x.com/" + i), t0);
        }
        Instant tDue = t0.plusSeconds(7200);
        List<CanonicalUrl> due = scheduler.dueRecrawlsByPriority(tDue, 3);
        assertThat(due).hasSize(3);
    }
}
