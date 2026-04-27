package com.hkg.crawler.common;

/**
 * Outcome of a fetch attempt, reported by the Fetcher to the Frontier
 * so it can update host politeness state ({@code backoff_factor},
 * {@code consecutive_errors}, etc.).
 *
 * <p>Each outcome maps to a specific Frontier action:
 * <ul>
 *   <li>{@link #SUCCESS_200} → multiply {@code backoff_factor} by 0.95
 *       (gradual recovery), reset {@code consecutive_errors} to 0</li>
 *   <li>{@link #NOT_MODIFIED_304} → same as success; the conditional
 *       GET worked and we saved bandwidth</li>
 *   <li>{@link #CLIENT_ERROR_4XX} → record verdict, no backoff change
 *       (the host is fine; the URL is gone or forbidden)</li>
 *   <li>{@link #SERVER_ERROR_5XX}, {@link #RATE_LIMITED_429} →
 *       multiply {@code backoff_factor} by 2.0, capped at 64.0</li>
 *   <li>{@link #NETWORK_ERROR}, {@link #TIMEOUT} → same as 5xx; the
 *       fetcher couldn't reach the host</li>
 *   <li>{@link #ROBOTS_DISALLOWED} → record verdict, no fetch occurred,
 *       no backoff change (politeness state already protected us)</li>
 * </ul>
 */
public enum FetchOutcome {
    SUCCESS_200,
    NOT_MODIFIED_304,
    REDIRECT_3XX,
    CLIENT_ERROR_4XX,
    SERVER_ERROR_5XX,
    RATE_LIMITED_429,
    NETWORK_ERROR,
    TIMEOUT,
    ROBOTS_DISALLOWED;

    /**
     * Should this outcome trigger an exponential-backoff increase on
     * the host's politeness clock? See {@code Frontier.host_state.backoff_factor}.
     */
    public boolean isBackoffSignal() {
        return this == SERVER_ERROR_5XX
            || this == RATE_LIMITED_429
            || this == NETWORK_ERROR
            || this == TIMEOUT;
    }

    /**
     * Did the fetch succeed (with or without a body)?
     */
    public boolean isSuccess() {
        return this == SUCCESS_200 || this == NOT_MODIFIED_304;
    }
}
