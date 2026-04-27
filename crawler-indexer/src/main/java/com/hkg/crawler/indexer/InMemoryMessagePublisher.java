package com.hkg.crawler.indexer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link MessagePublisher} that captures published payloads
 * per stream. Used for tests and the single-shard runtime when no Kafka
 * deployment is available.
 */
public final class InMemoryMessagePublisher implements MessagePublisher {

    private final Map<IndexerStream, ConcurrentLinkedQueue<byte[]>> queues =
        new EnumMap<>(IndexerStream.class);
    private final Map<IndexerStream, AtomicLong> publishedCounts =
        new EnumMap<>(IndexerStream.class);

    public InMemoryMessagePublisher() {
        for (IndexerStream s : IndexerStream.values()) {
            queues.put(s, new ConcurrentLinkedQueue<>());
            publishedCounts.put(s, new AtomicLong());
        }
    }

    @Override
    public void publish(IndexerStream stream, byte[] payload) {
        queues.get(stream).offer(payload);
        publishedCounts.get(stream).incrementAndGet();
    }

    @Override public void flush() { /* in-memory; no-op */ }
    @Override public void close() { /* in-memory; no-op */ }

    /** Number of payloads published to {@code stream} (cumulative; not drained). */
    public long publishedCount(IndexerStream stream) {
        return publishedCounts.get(stream).get();
    }

    /** Snapshot copy of all currently-queued payloads on {@code stream}. */
    public List<byte[]> snapshot(IndexerStream stream) {
        return List.copyOf(queues.get(stream));
    }

    /** Drain and return all currently-queued payloads on {@code stream}. */
    public List<byte[]> drain(IndexerStream stream) {
        ConcurrentLinkedQueue<byte[]> q = queues.get(stream);
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        byte[] item;
        while ((item = q.poll()) != null) out.add(item);
        return out;
    }
}
