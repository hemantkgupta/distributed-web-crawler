package com.hkg.crawler.robots;

import com.hkg.crawler.common.Host;

import java.util.concurrent.CompletableFuture;

/**
 * SPI for fetching {@code /robots.txt} from origin. The Robots service
 * has its own minimal HTTP client (separate from the main Fetcher) so
 * robots.txt fetches don't consume the host's per-second crawl-delay
 * budget.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code JdkHttpRobotsFetcher} (production) — JDK {@code HttpClient}
 *       with 5s timeout, max 5 redirects, 500 KiB body cap.</li>
 *   <li>{@code StubRobotsFetcher} (tests) — canned responses.</li>
 * </ul>
 */
@FunctionalInterface
public interface RobotsFetcher {

    CompletableFuture<RobotsResponse> fetch(Host host);

    /** HTTP-level outcome of one robots.txt fetch attempt. */
    record RobotsResponse(int httpStatus, String body, FetchError error) {

        public static RobotsResponse ok(int status, String body) {
            return new RobotsResponse(status, body, null);
        }

        public static RobotsResponse clientError(int status) {
            return new RobotsResponse(status, "", null);
        }

        public static RobotsResponse serverError(int status) {
            return new RobotsResponse(status, "", null);
        }

        public static RobotsResponse networkError(FetchError reason) {
            return new RobotsResponse(0, "", reason);
        }

        public boolean isSuccess() { return httpStatus >= 200 && httpStatus < 300; }
        public boolean is4xx()     { return httpStatus >= 400 && httpStatus < 500; }
        public boolean is5xx()     { return httpStatus >= 500 && httpStatus < 600; }
        public boolean isNetworkError() { return error != null; }
    }

    enum FetchError { TIMEOUT, DNS_FAILURE, CONNECTION_REFUSED, OTHER }
}
