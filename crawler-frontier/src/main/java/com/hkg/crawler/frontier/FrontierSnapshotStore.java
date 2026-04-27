package com.hkg.crawler.frontier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Persistence backend for {@link FrontierSnapshot}. Implementations:
 *
 * <ul>
 *   <li>{@code InMemoryFrontierSnapshotStore} — for tests and simulator.</li>
 *   <li>{@code RocksDbFrontierSnapshotStore} — production: writes the
 *       snapshot bytes to a single key in a colocated RocksDB instance.</li>
 * </ul>
 *
 * <p>The store contract is intentionally simple: it persists exactly one
 * snapshot at a time (the latest), no versioning. The Frontier
 * checkpointer overwrites the existing snapshot every 10 seconds.
 *
 * <p>Wire format: Java {@link ObjectOutputStream} bytes.
 * {@link FrontierSnapshot#formatVersion()} carries the version number
 * for forward-compatibility.
 */
public interface FrontierSnapshotStore extends AutoCloseable {

    /** Persist the latest snapshot, replacing any previous one. */
    void save(FrontierSnapshot snapshot);

    /** Load the latest snapshot, or empty if none has been saved. */
    Optional<FrontierSnapshot> load();

    @Override
    void close();

    // -------- shared codec ----------------------------------------------

    static byte[] encode(FrontierSnapshot snapshot) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode snapshot", e);
        }
    }

    static FrontierSnapshot decode(byte[] bytes) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (FrontierSnapshot) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("failed to decode snapshot", e);
        }
    }
}
