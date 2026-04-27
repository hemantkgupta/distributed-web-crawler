package com.hkg.crawler.importance;

import com.hkg.crawler.common.CanonicalUrl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpicComputerTest {

    private CanonicalUrl url(String s) { return CanonicalUrl.of(s); }

    @Test
    void seed_creates_state_with_initial_cash() {
        OpicComputer opic = new OpicComputer(1.0);
        opic.seed(url("http://a.com/"));
        assertThat(opic.cashOf(url("http://a.com/"))).isEqualTo(1.0);
        assertThat(opic.historyOf(url("http://a.com/"))).isZero();
    }

    @Test
    void visit_distributes_cash_to_outlinks() {
        OpicComputer opic = new OpicComputer(1.0);
        opic.seed(url("http://a.com/"));
        // Visit a.com with 2 outlinks → each gets 0.5 cash.
        opic.visit(url("http://a.com/"), List.of(
            url("http://b.com/"), url("http://c.com/")));

        assertThat(opic.cashOf(url("http://a.com/"))).isZero();
        assertThat(opic.historyOf(url("http://a.com/"))).isEqualTo(1.0);
        assertThat(opic.cashOf(url("http://b.com/"))).isEqualTo(0.5);
        assertThat(opic.cashOf(url("http://c.com/"))).isEqualTo(0.5);
    }

    @Test
    void unknown_url_is_auto_seeded_on_visit() {
        OpicComputer opic = new OpicComputer(1.0);
        // No prior seed — visit creates state with initial cash, then distributes.
        opic.visit(url("http://a.com/"), List.of(url("http://b.com/")));

        assertThat(opic.historyOf(url("http://a.com/"))).isEqualTo(1.0);
        // b.com receives 1.0 cash from a.com but no initial allocation.
        assertThat(opic.cashOf(url("http://b.com/"))).isEqualTo(1.0);
    }

    @Test
    void importance_accumulates_through_visits() {
        OpicComputer opic = new OpicComputer(1.0);
        // Three URLs in a chain: a → b → c → b
        opic.seed(url("http://a.com/"));
        opic.seed(url("http://b.com/"));
        opic.seed(url("http://c.com/"));

        opic.visit(url("http://a.com/"), List.of(url("http://b.com/")));
        // b.com now has 1.0 cash + 1.0 initial = 2.0
        assertThat(opic.cashOf(url("http://b.com/"))).isEqualTo(2.0);

        opic.visit(url("http://b.com/"), List.of(url("http://c.com/")));
        // b.com history=2.0, cash=0; c.com cash = 1.0 (initial) + 2.0 = 3.0
        assertThat(opic.historyOf(url("http://b.com/"))).isEqualTo(2.0);
        assertThat(opic.cashOf(url("http://c.com/"))).isEqualTo(3.0);

        opic.visit(url("http://c.com/"), List.of(url("http://b.com/")));
        // b.com receives 3.0 cash; its history is unchanged from prior visit.
        assertThat(opic.cashOf(url("http://b.com/"))).isEqualTo(3.0);
        assertThat(opic.historyOf(url("http://c.com/"))).isEqualTo(3.0);

        // Another visit to b.com adds 3.0 to its history.
        opic.visit(url("http://b.com/"), List.of(url("http://c.com/")));
        assertThat(opic.historyOf(url("http://b.com/"))).isEqualTo(5.0);
    }

    @Test
    void zero_outdegree_keeps_cash_with_visited_url_history() {
        OpicComputer opic = new OpicComputer(1.0);
        opic.seed(url("http://a.com/"));
        // Visit with no outlinks — cash is recorded in history but not distributed.
        opic.visit(url("http://a.com/"), List.of());

        assertThat(opic.historyOf(url("http://a.com/"))).isEqualTo(1.0);
        assertThat(opic.cashOf(url("http://a.com/"))).isZero();
    }

    @Test
    void top_by_history_returns_highest_first() {
        OpicComputer opic = new OpicComputer(1.0);
        opic.seed(url("http://a.com/"));
        opic.seed(url("http://b.com/"));
        opic.seed(url("http://c.com/"));

        // Visit a then b, both pointing to c → c never gets visited so 0 history.
        opic.visit(url("http://a.com/"), List.of(url("http://c.com/")));
        opic.visit(url("http://b.com/"), List.of(url("http://c.com/")));

        List<OpicState> top = opic.topByHistory(3);
        // a and b both have history 1.0; c has 0.
        assertThat(top.get(0).history()).isEqualTo(1.0);
        assertThat(top.get(1).history()).isEqualTo(1.0);
        assertThat(top.get(2).history()).isZero();
    }

    @Test
    void rejects_invalid_initial_cash() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OpicComputer(0))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OpicComputer(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
