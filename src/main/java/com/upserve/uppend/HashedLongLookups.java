package com.upserve.uppend;

import com.github.benmanes.caffeine.cache.*;
import com.google.common.base.Charsets;
import com.google.common.hash.*;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

@Slf4j
public class HashedLongLookups implements AutoCloseable {
    private final Path dir;
    private final HashFunction hashFunction;
    private final LoadingCache<Path, LongLookup> cache;

    public HashedLongLookups(Path dir, int maxCacheSize) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to mkdirs: " + dir, e);
        }

        hashFunction = Hashing.murmur3_32();

        cache = Caffeine.newBuilder()
                .maximumSize(maxCacheSize)
                .removalListener((RemovalListener<Path, LongLookup>) (key, value, cause) -> {
                    try {
                        if (value != null) {
                            value.close();
                        }
                    } catch (IOException e) {
                        log.error("unable to close " + key, e);
                        throw new UncheckedIOException("unable to close " + key, e);
                    }
                })
                .build(LongLookup::new);
    }

    public LongLookup get(String partition, String key) {
        return cache.get(hashPath(partition, key));
    }

    public LongLookup peek(String partition, String key) {
        return peekLookup(hashPath(partition, key));
    }

    public Stream<String> keys(String partition) {
        Stream<Path> files;
        try {
            files = Files.walk(dir.resolve(partition));
        } catch (IOException e) {
            return Stream.empty();
        }
        return files
                .filter(Files::isRegularFile)
                .flatMap(p -> Arrays.stream(peekLookup(p).keys()));
    }

    public Stream<String> partitions() {
        Stream<Path> files;
        try {
            files = Files.walk(dir);
        } catch (IOException e) {
            return Stream.empty();
        }

        // Paths are "dir/partition/hash/file" - extract just "partition"
        int dirLength = dir.getNameCount();
        return files
                .filter(Files::isRegularFile)
                .map(p -> p.subpath(dirLength, p.getNameCount() - 2).toString())
                .distinct();
    }

    @Override
    public void close() {
        log.trace("closing {}", dir);
        cache.invalidateAll();
        cache.cleanUp();
    }

    public void clear() {
        log.info("clearing {}", dir);
        close();
        try {
            Path tmpDir = Files.createTempFile(dir.getParent(), dir.getFileName().toString(), ".defunct");
            Files.delete(tmpDir);
            Files.move(dir, tmpDir);
            deleteDirectory(tmpDir);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to delete lookups: " + dir, e);
        }
    }

    private LongLookup peekLookup(Path p) {
        // Use cached version if available, but don't turn over cache if not
        LongLookup lookup = cache.getIfPresent(p);
        if (lookup == null) {
            lookup = new LongLookup(p);
        }
        return lookup;
    }

    private Path hashPath(String partition, String key) {
        log.trace("getting from {}: {}", dir, key);
        byte[] hash = hashFunction.hashString(key, Charsets.UTF_8).asBytes();
        String hashPath = String.format("%02x/%01x", 0xff & (int) hash[0], 0xf & (int) hash[1]);
        return dir.resolve(partition).resolve(hashPath);
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (path == null || path.toFile().getAbsolutePath().length() < 4) {
            throw new IOException("refusing to delete null or short path: " + path);
        }
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}
