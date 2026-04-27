package com.hkg.crawler.storage;

import java.io.IOException;

/**
 * Where the WARC writer flushes serialized records.
 *
 * <p>Production: {@link LocalDirWarcSink} writes to an NVMe directory
 * that's later batch-uploaded to S3 (the buffered-then-flushed pattern
 * from blog §11). Tests use {@link LocalDirWarcSink} too — it's a thin
 * file-system wrapper, no fakery needed.
 */
public interface WarcSink extends AutoCloseable {

    /** Write a fully-serialized WARC record to the sink. */
    void writeRecord(byte[] serialized) throws IOException;

    /** Force any buffered bytes to durable storage. */
    void flush() throws IOException;

    @Override
    void close() throws IOException;
}
