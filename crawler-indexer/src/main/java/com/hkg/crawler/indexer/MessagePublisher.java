package com.hkg.crawler.indexer;

/**
 * SPI for publishing typed events to one of the indexer streams.
 *
 * <p>Production: a {@code KafkaMessagePublisher} (Phase 5) wraps a
 * {@code KafkaProducer} and serializes events with Protobuf or Avro.
 * For tests and the single-shard setup, {@link InMemoryMessagePublisher}
 * captures emissions for inspection.
 *
 * <p>Implementations must be thread-safe — many fetcher/parser threads
 * call {@code publish()} concurrently.
 */
public interface MessagePublisher extends AutoCloseable {

    /** Publish an event payload to {@code stream}. */
    void publish(IndexerStream stream, byte[] payload);

    /**
     * Force any buffered events to durable storage / network. Called
     * at shutdown and (in production) periodically by the runtime.
     */
    void flush();

    @Override
    void close();
}
