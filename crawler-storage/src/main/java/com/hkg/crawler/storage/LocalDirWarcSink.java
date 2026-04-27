package com.hkg.crawler.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes WARC records to a rolling sequence of files in a local
 * directory. Files are named with a UTC timestamp and a sequence
 * number; the writer rolls over to a new file every
 * {@link #maxBytesPerFile} bytes.
 *
 * <p>Production deployments would copy completed files to S3 via a
 * background uploader; for the single-shard case the local directory
 * is the destination.
 *
 * <p>Thread-safe (synchronized on this instance).
 */
public final class LocalDirWarcSink implements WarcSink {

    private static final DateTimeFormatter TS = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneOffset.UTC);

    private final Path directory;
    private final String prefix;
    private final long maxBytesPerFile;
    private final AtomicLong sequence = new AtomicLong();

    private OutputStream currentStream;
    private Path currentFile;
    private long currentBytes;

    public LocalDirWarcSink(Path directory) throws IOException {
        this(directory, "crawler", 1024L * 1024 * 1024);   // 1 GiB roll
    }

    public LocalDirWarcSink(Path directory, String prefix, long maxBytesPerFile) throws IOException {
        this.directory = directory;
        this.prefix = prefix;
        this.maxBytesPerFile = maxBytesPerFile;
        Files.createDirectories(directory);
    }

    @Override
    public synchronized void writeRecord(byte[] serialized) throws IOException {
        if (currentStream == null || currentBytes + serialized.length > maxBytesPerFile) {
            rollFile();
        }
        currentStream.write(serialized);
        currentBytes += serialized.length;
    }

    @Override
    public synchronized void flush() throws IOException {
        if (currentStream != null) currentStream.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (currentStream != null) {
            currentStream.flush();
            currentStream.close();
            currentStream = null;
        }
    }

    private void rollFile() throws IOException {
        if (currentStream != null) {
            currentStream.flush();
            currentStream.close();
        }
        long seq = sequence.incrementAndGet();
        String filename = String.format("%s-%s-%05d.warc",
            prefix, TS.format(Instant.now()), seq);
        currentFile = directory.resolve(filename);
        currentStream = Files.newOutputStream(currentFile,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        currentBytes = 0;
    }

    /** Most recently opened WARC file path (for tests / observability). */
    public synchronized Path currentFilePath() { return currentFile; }
}
