package com.hkg.crawler.importance;

import com.hkg.crawler.common.CanonicalUrl;

/**
 * Per-URL OPIC state — the {@code cash} currently held and the {@code
 * history} (running total of cash ever distributed to this URL).
 *
 * <p>{@code history} is the importance estimate; the higher the value,
 * the more "cash" has flowed through this URL's outlinks back to it.
 * Used by the recrawl scheduler as the {@code value(u)} term.
 */
public final class OpicState {

    private final CanonicalUrl url;
    private double cash;
    private double history;

    public OpicState(CanonicalUrl url, double initialCash) {
        this.url = url;
        this.cash = initialCash;
        this.history = 0.0;
    }

    public CanonicalUrl url()    { return url; }
    public double       cash()   { return cash; }
    public double       history(){ return history; }

    /** Add cash to this URL — called when an inbound visitor distributes. */
    public void receiveCash(double amount) {
        if (amount < 0) throw new IllegalArgumentException("cash must be non-negative");
        this.cash += amount;
    }

    /**
     * Process a visit: accumulate this URL's current cash into history,
     * then zero the cash. The visitor distributes the snapshot value to
     * outlinks externally. Returns the cash-at-visit-time so the caller
     * can use it for the distribution.
     */
    public double recordVisitAndZeroCash() {
        double snapshot = cash;
        history += cash;
        cash = 0.0;
        return snapshot;
    }
}
