package com.hkg.crawler.common;

/**
 * Front-queue priority class for the Mercator-style URL Frontier.
 *
 * <p>The Frontier maintains one FIFO front queue per priority class.
 * The Back-Queue Selector pulls URLs from the front queues weighted by
 * class to feed the per-host back queues. Default weighting (tunable):
 *
 * <ul>
 *   <li>{@link #URGENT_RECRAWL} — 5%   (changed-recently URLs)</li>
 *   <li>{@link #HIGH_OPIC}      — 30%  (top-decile importance)</li>
 *   <li>{@link #MEDIUM_OPIC}    — 40%  (mid-tier importance)</li>
 *   <li>{@link #BFS_DISCOVERY}  — 20%  (newly discovered URLs)</li>
 *   <li>{@link #LOW_OPIC}       — 5%   (bottom-decile importance)</li>
 * </ul>
 *
 * <p>An empty class's weight is redistributed proportionally among
 * non-empty classes in the current selection batch.
 */
public enum PriorityClass {
    URGENT_RECRAWL,
    HIGH_OPIC,
    MEDIUM_OPIC,
    BFS_DISCOVERY,
    LOW_OPIC
}
