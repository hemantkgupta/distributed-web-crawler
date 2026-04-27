package com.hkg.crawler.frontier;

import com.hkg.crawler.common.PriorityClass;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Snapshot of the Frontier's internal state for observability and tests.
 * Computed by walking the front queues, back queues, and ready-host heap;
 * not a hot-path read.
 */
public record FrontierStats(
    int totalUrlsInFrontQueues,
    int totalUrlsInBackQueues,
    Map<PriorityClass, Integer> frontQueueSizes,
    int activeHostCount,
    int readyHostCount,
    int quarantinedHostCount,
    Optional<Instant> earliestReadyTime
) {}
