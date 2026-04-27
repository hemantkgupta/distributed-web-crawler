package com.hkg.crawler.dedup;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact URL set backed by {@link ConcurrentHashMap}. Suitable for the
 * single-shard case at up to ~10⁸ URLs (above that, switch to
 * {@link RocksDbExactUrlSet}).
 */
public final class InMemoryExactUrlSet implements ExactUrlSet {

    private final Set<String> set = ConcurrentHashMap.newKeySet();

    @Override public boolean add(String url)      { return set.add(url); }
    @Override public boolean contains(String url) { return set.contains(url); }
    @Override public long    size()               { return set.size(); }
    @Override public void    close()              { /* no resources */ }
}
