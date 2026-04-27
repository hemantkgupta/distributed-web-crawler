package com.hkg.crawler.fetcher;

import java.util.concurrent.CompletableFuture;

/**
 * The HTTP layer of the crawler.
 *
 * <p>Production implementation: {@link JdkHttpFetcher}, using
 * {@code java.net.http.HttpClient} with HTTP/2 support, conditional GET,
 * redirect handling (≤ 5 hops per RFC 9309), content-type filtering,
 * and a 10 MB body cap.
 *
 * <p>Per-fetch budget is enforced via {@code FetchRequest.totalTimeout}.
 * Slow-tail responses that exceed the budget are reported as
 * {@link com.hkg.crawler.common.FetchOutcome#TIMEOUT}; the Frontier will
 * double the host's backoff factor in response.
 */
public interface HttpFetcher {

    /**
     * Fetch the URL described by {@code request}. The returned future
     * always completes with a {@link FetchResult} — never throws —
     * encoding errors as {@link com.hkg.crawler.common.FetchOutcome#NETWORK_ERROR}
     * or {@code TIMEOUT}.
     */
    CompletableFuture<FetchResult> fetch(FetchRequest request);
}
