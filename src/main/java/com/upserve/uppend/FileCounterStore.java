package com.upserve.uppend;

import com.upserve.uppend.blobs.*;
import com.upserve.uppend.lookup.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.stream.Stream;

import static com.upserve.uppend.FileAppendOnlyStore.*;
import static com.upserve.uppend.Partition.listPartitions;

public class FileCounterStore extends FileStore<CounterStorePartition> implements CounterStore {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final int longLookupHashSize;

    private final FileCache fileCache;
    private final LookupCache lookupCache;
    private final Function<String, CounterStorePartition> openPartitionFunction;
    private final Function<String, CounterStorePartition> createPartitionFunction;

    FileCounterStore(Path dir, int flushDelaySeconds, boolean readOnly, int longLookupHashSize, int longLookupWriteCacheSize) {
        super(dir, flushDelaySeconds, readOnly);
        this.longLookupHashSize = longLookupHashSize;

        fileCache = new FileCache(DEFAULT_INITIAL_FILE_CACHE_SIZE, DEFAULT_MAXIMUM_FILE_CACHE_SIZE, readOnly);
        PagedFileMapper lookupPageCache = new PagedFileMapper(DEFAULT_LOOKUP_PAGE_SIZE, DEFAULT_INITIAL_LOOKUP_CACHE_SIZE, DEFAULT_MAXIMUM_LOOKUP_CACHE_SIZE, fileCache);
        lookupCache = new LookupCache(lookupPageCache);

        openPartitionFunction = partitionKey -> CounterStorePartition.openPartition(partionPath(dir), partitionKey, longLookupHashSize, lookupCache);
        createPartitionFunction = partitionKey -> CounterStorePartition.createPartition(partionPath(dir), partitionKey, longLookupHashSize, lookupCache);

    }

    private static Path partionPath(Path dir){
        return dir.resolve("partitions");
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
        if (readOnly) throw new RuntimeException("Can not increment value of counter store opened in read only mode:" + dir);
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
    public void clear() {
        log.trace("clearing");
        if (readOnly) throw new RuntimeException("Can not clear a store opened in read only mode:" + dir);

        log.trace("clearing");

        listPartitions(partionPath(dir))
                .map(this::getIfPresent)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(CounterStorePartition::clear);
        partitionMap.clear();
        lookupCache.flush();
        fileCache.flush();
    }

    @Override
    public void trimInternal() throws IOException {
        if (!readOnly) flushInternal();
        lookupCache.flush();
        fileCache.flush();
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
    protected void flushInternal() throws IOException {
        if (readOnly) throw new RuntimeException("Can not flush a store opened in read only mode:" + dir);

        // Only need to flush open partitions
        for (CounterStorePartition counterStorePartition : partitionMap.values()){
            counterStorePartition.flush();
        }
    }

    @Override
    protected void closeInternal() throws IOException {
        flushInternal();
    }
}
