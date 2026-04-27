package com.hkg.crawler.frontier;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Production snapshot store backed by an embedded RocksDB instance.
 * Stores the latest {@link FrontierSnapshot} as a single key/value pair;
 * older snapshots are overwritten on each {@link #save}.
 *
 * <p>Why RocksDB and not just a flat file? Two reasons:
 * <ol>
 *   <li>The same RocksDB process can later host other crawler-frontier
 *       column families (back-queue persistence, host state) when we
 *       move beyond snapshot-only durability in a future checkpoint.</li>
 *   <li>RocksDB's atomic write semantics ensure {@code save} either
 *       fully replaces the prior snapshot or leaves it intact —
 *       crash mid-write does not leave a half-corrupt snapshot.</li>
 * </ol>
 *
 * <p>Single instance per Frontier; not safe to open the same path from
 * multiple processes (RocksDB enforces this with a file lock).
 */
public final class RocksDbFrontierSnapshotStore implements FrontierSnapshotStore {

    private static final byte[] KEY = "frontier:snapshot:latest".getBytes(StandardCharsets.UTF_8);

    static {
        RocksDB.loadLibrary();
    }

    private final Options options;
    private final RocksDB db;

    public RocksDbFrontierSnapshotStore(Path path) {
        this.options = new Options().setCreateIfMissing(true);
        try {
            java.nio.file.Files.createDirectories(path);
            this.db = RocksDB.open(options, path.toString());
        } catch (RocksDBException | java.io.IOException e) {
            options.close();
            throw new RuntimeException("failed to open RocksDB at " + path, e);
        }
    }

    @Override
    public void save(FrontierSnapshot snapshot) {
        try {
            db.put(KEY, FrontierSnapshotStore.encode(snapshot));
        } catch (RocksDBException e) {
            throw new RuntimeException("failed to save snapshot", e);
        }
    }

    @Override
    public Optional<FrontierSnapshot> load() {
        try {
            byte[] value = db.get(KEY);
            if (value == null) return Optional.empty();
            return Optional.of(FrontierSnapshotStore.decode(value));
        } catch (RocksDBException e) {
            throw new RuntimeException("failed to load snapshot", e);
        }
    }

    @Override
    public void close() {
        db.close();
        options.close();
    }
}
