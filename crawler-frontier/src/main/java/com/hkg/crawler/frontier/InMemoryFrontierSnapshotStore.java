package com.hkg.crawler.frontier;

import java.util.Optional;

/**
 * In-memory snapshot store for tests and the simulator. The bytes round-trip
 * through the same {@link FrontierSnapshotStore#encode} / {@code decode}
 * codec used in production so test coverage of the snapshot format is real.
 */
public final class InMemoryFrontierSnapshotStore implements FrontierSnapshotStore {

    private byte[] latest;

    @Override
    public synchronized void save(FrontierSnapshot snapshot) {
        latest = FrontierSnapshotStore.encode(snapshot);
    }

    @Override
    public synchronized Optional<FrontierSnapshot> load() {
        if (latest == null) return Optional.empty();
        return Optional.of(FrontierSnapshotStore.decode(latest));
    }

    @Override
    public synchronized void close() { /* no resources */ }
}
