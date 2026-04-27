package com.hkg.crawler.coordinator.forwarding;

import java.util.concurrent.CompletableFuture;

/**
 * SPI for shipping a {@link ForwardingBatch} to another agent.
 *
 * <p>Production: {@code GrpcCrossAgentForwarder} streams batches over
 * a long-lived gRPC channel with HTTP/2 flow control (Phase 5
 * deployment).
 *
 * <p>Tests + single-process simulator: {@link InMemoryCrossAgentForwarder}
 * delivers batches to local target-agent stubs.
 *
 * <p>The contract: {@code send()} is async and never throws — failure
 * is reported via the returned future's exceptional completion. The
 * {@link BatchingForwardingQueue} above this SPI handles retries and
 * dead-letter routing on persistent failure.
 */
public interface CrossAgentForwarder extends AutoCloseable {

    /** Send {@code batch} to its destination agent. */
    CompletableFuture<Void> send(ForwardingBatch batch);

    @Override
    void close();
}
