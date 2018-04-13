package com.upserve.uppend;

import com.upserve.uppend.blobs.*;
import com.upserve.uppend.lookup.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.stream.*;

import static com.upserve.uppend.Partition.listPartitions;

public class FileAppendOnlyStore extends FileStore<AppendStorePartition> implements AppendOnlyStore {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    protected static final int DEFAULT_BLOBS_PER_BLOCK = 127;
    protected static final int DEFAULT_BLOB_PAGE_SIZE = 1024 * 1024;
    protected static final int DEFAULT_MAXIMUM_BLOB_CACHE_SIZE = 10_000;
    protected static final int DEFAULT_INITIAL_BLOB_CACHE_SIZE = 100;

    protected static final int DEFAULT_LOOKUP_PAGE_SIZE = 256 *1024;
    protected static final int DEFAULT_MAXIMUM_LOOKUP_CACHE_SIZE = 10_000;
    protected static final int DEFAULT_INITIAL_LOOKUP_CACHE_SIZE = 100;

    protected static final int DEFAULT_MAXIMUM_FILE_CACHE_SIZE = 8_000;
    protected static final int DEFAULT_INITIAL_FILE_CACHE_SIZE = 1000;

    protected static final int DEFAULT_HASH_SIZE = 256;

    protected final BlockedLongs blocks;

    private final PagedFileMapper blobPageCache;
    private final LookupCache lookupCache;
    private final FileCache fileCache;
    private final int longLookupHashSize;

    private final Function<String, AppendStorePartition> openPartitionFunction;
    private final Function<String, AppendStorePartition> createPartitionFunction;

    FileAppendOnlyStore(Path dir, int flushDelaySeconds, boolean readOnly, int longLookupHashSize, int blobsPerBlock) {
        super(dir, flushDelaySeconds, readOnly);

        this.longLookupHashSize = longLookupHashSize;

        fileCache = new FileCache(DEFAULT_INITIAL_FILE_CACHE_SIZE, DEFAULT_MAXIMUM_FILE_CACHE_SIZE, readOnly);
        blobPageCache = new PagedFileMapper(DEFAULT_BLOB_PAGE_SIZE, DEFAULT_INITIAL_BLOB_CACHE_SIZE, DEFAULT_MAXIMUM_BLOB_CACHE_SIZE, fileCache);
        PagedFileMapper lookupPageCache = new PagedFileMapper(DEFAULT_LOOKUP_PAGE_SIZE, DEFAULT_INITIAL_LOOKUP_CACHE_SIZE, DEFAULT_MAXIMUM_LOOKUP_CACHE_SIZE, fileCache);
        lookupCache = new LookupCache(lookupPageCache);

        blocks = new BlockedLongs(dir.resolve("blocks"), blobsPerBlock, readOnly);

        openPartitionFunction = partitionKey -> AppendStorePartition.openPartition(partionPath(dir), partitionKey, longLookupHashSize, blobPageCache, lookupCache);

        createPartitionFunction = partitionKey -> AppendStorePartition.createPartition(partionPath(dir), partitionKey, longLookupHashSize, blobPageCache, lookupCache);
    }

    private static Path partionPath(Path dir){
        return dir.resolve("partitions");
    }

    @Override
    public void append(String partition, String key, byte[] value) {
        log.trace("appending for partition '{}', key '{}'", partition, key);
        if (readOnly) throw new RuntimeException("Can not append to store opened in read only mode:" + dir);
        getOrCreate(partition).append(key, value, blocks);
    }

    @Override
    public Stream<byte[]> read(String partition, String key) {
        log.trace("reading in partition {} with key {}", partition, key);

        return getIfPresent(partition)
                .map(partitionObject -> partitionObject.read(key, blocks))
                .orElse(Stream.empty());
    }

    @Override
    public Stream<byte[]> readSequential(String partition, String key) {
        log.trace("reading sequential in partition {} with key {}", partition, key);
        return getIfPresent(partition)
                .map(partitionObject -> partitionObject.readSequential(key, blocks))
                .orElse(Stream.empty());
    }

    public byte[] readLast(String partition, String key) {
        log.trace("reading last in partition {} with key {}", partition, key);
        return getIfPresent(partition)
                .map(partitionObject -> partitionObject.readLast(key, blocks))
                .orElse(null);
    }

    @Override
    public Stream<String> keys(String partition) {
        log.trace("getting keys in partition {}", partition);
        return getIfPresent(partition)
                .map(AppendStorePartition::keys)
                .orElse(Stream.empty());
    }

    @Override
    public Stream<String> partitions() {
        return listPartitions(partionPath(dir));
    }

    @Override
    public Stream<Map.Entry<String, Stream<byte[]>>> scan(String partition) {
        return getIfPresent(partition)
                .map(partitionObject -> partitionObject.scan(blocks))
                .orElse(Stream.empty());
    }

    @Override
    public void scan(String partition, BiConsumer<String, Stream<byte[]>> callback) {
        getIfPresent(partition)
                .ifPresent(partitionObject -> partitionObject.scan(blocks, callback));
    }

    @Override
    public void clear() {
        // Consider using a ReadWrite lock for clear and close?
        if (readOnly) throw new RuntimeException("Can not clear a store opened in read only mode:" + dir);

        log.trace("clearing");
        blocks.clear();

        listPartitions(partionPath(dir))
                .map(this::getIfPresent)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(AppendStorePartition::clear);
        partitionMap.clear();
        lookupCache.flush();
        blobPageCache.flush();
        fileCache.flush();
    }

    @Override
    Function<String, AppendStorePartition> getOpenPartitionFunction() {
        return openPartitionFunction;
    }

    @Override
    Function<String, AppendStorePartition> getCreatePartitionFunction() {
        return createPartitionFunction;
    }

    @Override
    protected void flushInternal() throws IOException {
        // Flush lookups, then blocks, then blobs, since this is the access order of a read.
        // Check non null because the super class is registered in the autoflusher before the constructor finishes
        if (readOnly) throw new RuntimeException("Can not flush a store opened in read only mode:" + dir);

        blocks.flush();
        for (AppendStorePartition appendStorePartition : partitionMap.values()){
            appendStorePartition.flush();
        }
    }

    @Override
    public void trimInternal() throws IOException {
        if (!readOnly) flushInternal();
        lookupCache.flush();
        blobPageCache.flush();
        fileCache.flush();
    }

    @Override
    protected void closeInternal() throws IOException {
        trimInternal();

        try {
            blocks.close();
        } catch (Exception e) {
            log.error("unable to close blocks", e);
        }
    }
}
