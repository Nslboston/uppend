package com.upserve.uppend;

import java.util.Map;
import java.util.function.*;
import java.util.stream.*;

public interface ReadOnlyCounterStore extends AutoCloseable {
    /**
     * Get the value for a given partition and key
     *
     * @param partition the partition to get
     * @param key the key to get
     * @throws IllegalArgumentException if partition is invalid
     * @return the value for the given partition and key, or 0 if not found
     */
    long get(String partition, String key);

    /**
     * Enumerate the keys for a given partition
     *
     * @param partition the partition under which to retrieve
     * @throws IllegalArgumentException if partition is invalid
     * @return a stream of string keys
     */
    Stream<String> keys(String partition);

    /**
     * Enumerate the partitions in the data store
     *
     * @return a stream of string partition
     */
    Stream<String> partitions();

    /**
     * Scan all the keys and values in a partition, returning a stream of
     * entries
     *
     * @param partition the partition to scan
     * @return a stream of entries of key to counter values
     */
    Stream<Map.Entry<String, Long>> scan(String partition);

    /**
     * Scan the given partition, calling the given function with each key and
     * counter value
     *
     * @param partition the partition to scan
     * @param callback function to call for each key and value
     */
    void scan(String partition, ObjLongConsumer<String> callback);
}
