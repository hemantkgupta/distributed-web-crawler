package com.hkg.crawler.indexer;

/**
 * The three logical streams emitted by the crawler to its downstream
 * consumers, per blog §12 Indexer Pipeline.
 *
 * <ul>
 *   <li>{@link #DOCUMENTS} — parsed text + metadata → search index</li>
 *   <li>{@link #LINKS}     — outlink edges → link graph + importance</li>
 *   <li>{@link #OPERATIONAL} — per-fetch operational events → analytics</li>
 * </ul>
 *
 * <p>In production these map to three Kafka topics
 * ({@code crawler.documents}, {@code crawler.links},
 * {@code crawler.operational}). The {@link MessagePublisher} SPI lets
 * the local in-memory implementation satisfy tests without a Kafka
 * dependency.
 */
public enum IndexerStream {
    DOCUMENTS("crawler.documents"),
    LINKS("crawler.links"),
    OPERATIONAL("crawler.operational");

    private final String topicName;

    IndexerStream(String topicName) {
        this.topicName = topicName;
    }

    public String topicName() { return topicName; }
}
