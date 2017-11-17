package com.upserve.uppend;

import com.upserve.uppend.lookup.LongLookup;
import org.slf4j.Logger;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class FileCounterStore implements CounterStore {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /**
     * DEFAULT_FLUSH_DELAY_SECONDS is the number of seconds to wait between
     * automatically flushing writes.
     */
    public static final int DEFAULT_FLUSH_DELAY_SECONDS = FileAppendOnlyStore.DEFAULT_FLUSH_DELAY_SECONDS;

    private final Path dir;
    private final LongLookup lookup;

    private final AtomicBoolean isClosed;

    public FileCounterStore(Path dir) {
        this(
                dir,
                LongLookup.DEFAULT_HASH_SIZE,
                LongLookup.DEFAULT_WRITE_CACHE_SIZE,
                DEFAULT_FLUSH_DELAY_SECONDS
        );
    }

    public FileCounterStore(Path dir, int longLookupHashSize, int longLookupWriteCacheSize, int flushDelaySeconds) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to mkdirs: " + dir, e);
        }

        this.dir = dir;
        lookup = new LongLookup(
                dir.resolve("inc-lookup"),
                longLookupHashSize,
                longLookupWriteCacheSize
        );
        AutoFlusher.register(flushDelaySeconds, this);

        isClosed = new AtomicBoolean(false);
    }

    @Override
    public long set(String partition, String key, long value) {
        log.trace("setting {}={} in partition '{}'", key, value, partition);
        return lookup.put(partition, key, value);
    }

    @Override
    public long increment(String partition, String key, long delta) {
        log.trace("incrementing by {} key '{}' in partition '{}'", delta, key, partition);
        return lookup.increment(partition, key, delta);
    }

    @Override
    public void flush() {
        log.info("flushing {}", dir);
        lookup.flush();
        log.info("flushed {}", dir);
    }

    @Override
    public long get(String partition, String key) {
        log.trace("getting value for key '{}' in partition '{}'", key, partition);
        long val = lookup.get(partition, key);
        return val == -1 ? 0 : val;
    }

    @Override
    public Stream<String> keys(String partition) {
        log.trace("getting keys in partition '{}'", partition);
        return lookup.keys(partition);
    }

    @Override
    public Stream<String> partitions() {
        log.trace("getting partitions");
        return lookup.partitions();
    }

    @Override
    public void clear() {
        log.trace("clearing");
        lookup.clear();
    }

    @Override
    public void close() throws Exception {
        if (!isClosed.compareAndSet(false, true)) {
            log.warn("close called twice on counter store: " + dir);
            return;
        }
        log.info("closing: " + dir);
        AutoFlusher.deregister(this);
        try {
            lookup.close();
        } catch (Exception e) {
            log.error("unable to close lookup", e);
        }
    }
}