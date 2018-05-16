package com.upserve.uppend;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.upserve.uppend.blobs.PageCache;
import com.upserve.uppend.lookup.LookupCache;
import org.slf4j.Logger;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;

import static com.upserve.uppend.Partition.listPartitions;

public class FileCounterStore extends FileStore<CounterStorePartition> implements CounterStore {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final PageCache keyPageCache;
    private final LookupCache lookupCache;
    private final Function<String, CounterStorePartition> openPartitionFunction;
    private final Function<String, CounterStorePartition> createPartitionFunction;

    FileCounterStore(boolean readOnly, CounterStoreBuilder builder) {
        super(builder.getDir(), builder.getFlushDelaySeconds(), readOnly);

        keyPageCache = builder.buildLookupPageCache(getName());
        lookupCache = builder.buildLookupCache(getName());

        openPartitionFunction = partitionKey -> CounterStorePartition.openPartition(partionPath(dir), partitionKey, builder.getLookupHashSize(), builder.getFlushThreshold(), builder.getMetadataPageSize(), keyPageCache, lookupCache, readOnly);
        createPartitionFunction = partitionKey -> CounterStorePartition.createPartition(partionPath(dir), partitionKey, builder.getLookupHashSize(), builder.getFlushThreshold(), builder.getMetadataPageSize(), keyPageCache, lookupCache);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Long set(String partition, String key, long value) {
        log.trace("setting {}={} in partition '{}'", key, value, partition);
        if (readOnly) throw new RuntimeException("Can not set value of counter store opened in read only mode:" + dir);
        return getOrCreate(partition).set(key, value);
    }

    @Override
    public long increment(String partition, String key, long delta) {
        log.trace("incrementing by {} key '{}' in partition '{}'", delta, key, partition);
        if (readOnly)
            throw new RuntimeException("Can not increment value of counter store opened in read only mode:" + dir);
        return getOrCreate(partition).increment(key, delta);
    }

    @Override
    public Long get(String partition, String key) {
        log.trace("getting value for key '{}' in partition '{}'", key, partition);
        return getIfPresent(partition).map(partitionObject -> partitionObject.get(key)).orElse(null);
    }

    @Override
    public Stream<String> keys(String partition) {
        log.trace("getting keys in partition {}", partition);
        return getIfPresent(partition)
                .map(CounterStorePartition::keys)
                .orElse(Stream.empty());
    }

    @Override
    public Stream<String> partitions() {
        return listPartitions(partionPath(dir));
    }

    @Override
    public Stream<Map.Entry<String, Long>> scan(String partition) {
        return getIfPresent(partition).map(CounterStorePartition::scan).orElse(Stream.empty());
    }

    @Override
    public void scan(String partition, ObjLongConsumer<String> callback) {
        getIfPresent(partition).ifPresent(partitionObject -> partitionObject.scan(callback));
    }

    @Override
    public CacheStats getKeyPageCacheStats() {
        return keyPageCache.stats();
    }

    @Override
    public CacheStats getLookupKeyCacheStats() {
        return lookupCache.keyStats();
    }

    @Override
    public CacheStats getMetadataCacheStats() {
        return lookupCache.metadataStats();
    }

    @Override
    public long keyCount() {
        return listPartitions(partionPath(dir))
                .map(this::getIfPresent)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .mapToLong(CounterStorePartition::keyCount)
                .sum();
    }

    @Override
    public void clear() {
        log.trace("clearing");
        if (readOnly) throw new RuntimeException("Can not clear a store opened in read only mode:" + dir);

        log.trace("clearing");

        listPartitions(partionPath(dir))
                .map(this::getIfPresent)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(counterStorePartition -> {
                    try {
                        counterStorePartition.clear();
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to clear counter store partition", e);
                    }
                });
        partitionMap.clear();
        lookupCache.flush();
        keyPageCache.flush();
    }

    @Override
    public void trimInternal() {
        if (!readOnly) flushInternal();
        lookupCache.flush();
        keyPageCache.flush();
    }

    @Override
    Function<String, CounterStorePartition> getOpenPartitionFunction() {
        return openPartitionFunction;
    }

    @Override
    Function<String, CounterStorePartition> getCreatePartitionFunction() {
        return createPartitionFunction;
    }

    @Override
    protected void flushInternal() {
        if (readOnly) throw new RuntimeException("Can not flush a store opened in read only mode:" + dir);

        partitionMap.values().parallelStream().forEach(counterStorePartition -> {
            try {
                counterStorePartition.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Error flushing store " + dir, e);
            }
        });
    }

    @Override
    protected void closeInternal() {
        partitionMap.values().parallelStream().forEach(counterStorePartition -> {
            try {
                counterStorePartition.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Error closing store " + dir, e);
            }
        });

        lookupCache.flush();
        keyPageCache.flush();
    }
}
