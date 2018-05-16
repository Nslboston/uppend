package com.upserve.uppend.metrics;

import com.codahale.metrics.*;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.collect.Maps;
import com.upserve.uppend.AppendOnlyStore;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class AppendOnlyStoreWithMetrics implements AppendOnlyStore {
    public static final String WRITE_TIMER_METRIC_NAME = "writeTimer";
    public static final String FLUSH_TIMER_METRIC_NAME = "flushTimer";
    public static final String READ_TIMER_METRIC_NAME = "readTimer";
    public static final String KEYS_TIMER_METRIC_NAME = "keysTimer";
    public static final String PARTITIONS_TIMER_METRIC_NAME = "partitionsTimer";
    public static final String SCAN_TIMER_METRIC_NAME = "scanTimer";
    public static final String CLEAR_TIMER_METRIC_NAME = "clearTimer";
    public static final String CLOSE_TIMER_METRIC_NAME = "closeTimer";
    public static final String PURGE_TIMER_METRIC_NAME = "purgeTimer";

    public static final String WRITE_BYTES_METER_METRIC_NAME = "writeBytesMeter";
    public static final String READ_BYTES_METER_METRIC_NAME = "readBytesMeter";
    public static final String SCAN_BYTES_METER_METRIC_NAME = "scanBytesMeter";
    public static final String SCAN_KEYS_METER_METRIC_NAME = "scanKeysMeter";

    private final AppendOnlyStore store;
    private final MetricRegistry metrics;

    private final Timer writeTimer;
    private final Timer flushTimer;
    private final Timer readTimer;
    private final Timer keysTimer;
    private final Timer partitionsTimer;
    private final Timer scanTimer;
    private final Timer clearTimer;
    private final Timer closeTimer;
    private final Timer trimTimer;

    private final Meter writeBytesMeter;
    private final Meter readBytesMeter;
    private final Meter scanBytesMeter;
    private final Meter scanKeysMeter;

    public AppendOnlyStoreWithMetrics(AppendOnlyStore store, MetricRegistry metrics) {
        this.store = store;
        this.metrics = metrics;

        writeTimer = metrics.timer(getFullMetricName(store, WRITE_TIMER_METRIC_NAME));
        flushTimer = metrics.timer(getFullMetricName(store, FLUSH_TIMER_METRIC_NAME));
        readTimer = metrics.timer(getFullMetricName(store, READ_TIMER_METRIC_NAME));
        keysTimer = metrics.timer(getFullMetricName(store, KEYS_TIMER_METRIC_NAME));
        partitionsTimer = metrics.timer(getFullMetricName(store, PARTITIONS_TIMER_METRIC_NAME));
        scanTimer = metrics.timer(getFullMetricName(store, SCAN_TIMER_METRIC_NAME));
        clearTimer = metrics.timer(getFullMetricName(store, CLEAR_TIMER_METRIC_NAME));
        closeTimer = metrics.timer(getFullMetricName(store, CLOSE_TIMER_METRIC_NAME));
        trimTimer = metrics.timer(getFullMetricName(store, PURGE_TIMER_METRIC_NAME));

        writeBytesMeter = metrics.meter(getFullMetricName(store, WRITE_BYTES_METER_METRIC_NAME));
        readBytesMeter = metrics.meter(getFullMetricName(store, READ_BYTES_METER_METRIC_NAME));
        scanBytesMeter = metrics.meter(getFullMetricName(store, SCAN_BYTES_METER_METRIC_NAME));
        scanKeysMeter = metrics.meter(getFullMetricName(store, SCAN_KEYS_METER_METRIC_NAME));

    }

    public static String getFullMetricName(AppendOnlyStore store, String metricName) {
        return String.join(".", store.getName(), metricName);
    }

    @Override
    public void append(String partition, String key, byte[] value) {
        final Timer.Context context = writeTimer.time();
        try {
            writeBytesMeter.mark(value.length);
            store.append(partition, key, value);
        } finally {
            context.stop();
        }
    }

    @Override
    public void register(int seconds) {
        store.register(seconds);
    }

    @Override
    public void deregister() {
        store.deregister();
    }

    @Override
    public void flush() {
        final Timer.Context context = flushTimer.time();
        try {
            store.flush();
        } finally {
            context.stop();
        }
    }

    @Override
    public Stream<byte[]> read(String partition, String key) {
        final Timer.Context context = readTimer.time();
        try {
            return store.read(partition, key)
                    .peek(bytes -> readBytesMeter.mark(bytes.length));
        } finally {
            context.stop();
        }
    }

    @Override
    public Stream<byte[]> readSequential(String partition, String key) {
        final Timer.Context context = readTimer.time();
        try {
            return store.readSequential(partition, key)
                    .peek(bytes -> readBytesMeter.mark(bytes.length));
        } finally {
            context.stop();
        }
    }

    @Override
    public byte[] readLast(String partition, String key) {
        final Timer.Context context = readTimer.time();
        try {
            byte[] bytes = store.readLast(partition, key);
            readBytesMeter.mark(bytes.length);
            return bytes;
        } finally {
            context.stop();
        }
    }

    @Override
    public Stream<String> keys(String partition) {
        final Timer.Context context = keysTimer.time();
        try {
            return store.keys(partition);
        } finally {
            context.stop();
        }
    }

    @Override
    public Stream<String> partitions() {
        final Timer.Context context = partitionsTimer.time();
        try {
            return store.partitions();
        } finally {
            context.stop();
        }
    }

    @Override
    public Stream<Map.Entry<String, Stream<byte[]>>> scan(String partition) {
        final Timer.Context context = scanTimer.time();
        try {
            return store.scan(partition)
                    .peek(entry -> scanKeysMeter.mark(1))
                    .map(entry -> Maps.immutableEntry(entry.getKey(), entry.getValue().peek(bytes -> scanBytesMeter.mark(bytes.length))));
        } finally {
            context.stop();
        }
    }

    @Override
    public void scan(String partition, BiConsumer<String, Stream<byte[]>> callback) {
        final Timer.Context context = scanTimer.time();
        try {
            store.scan(partition, callback);
        } finally {
            context.stop();
        }
    }

    @Override
    public void clear() {
        final Timer.Context context = clearTimer.time();
        try {
            store.clear();
        } finally {
            context.stop();
        }
    }

    @Override
    public String getName() {
        return store.getName();
    }

    @Override
    public CacheStats getBlobPageCacheStats() {
        return store.getBlobPageCacheStats();
    }

    @Override
    public CacheStats getKeyPageCacheStats() {
        return store.getKeyPageCacheStats();
    }

    @Override
    public CacheStats getLookupKeyCacheStats() {
        return store.getLookupKeyCacheStats();
    }

    @Override
    public CacheStats getMetadataCacheStats() {
        return store.getMetadataCacheStats();
    }

    @Override
    public long keyCount() {
        return store.keyCount();
    }

    @Override
    public void trim() {
        final Timer.Context context = trimTimer.time();
        try {
            store.trim();
        } finally {
            context.stop();
        }
    }

    @Override
    public void close() throws Exception {
        final Timer.Context context = closeTimer.time();
        try {
            store.close();
        } finally {
            context.stop();
        }
    }
}
