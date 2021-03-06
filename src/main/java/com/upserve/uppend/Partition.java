package com.upserve.uppend;

import com.google.common.hash.*;
import com.upserve.uppend.blobs.*;
import com.upserve.uppend.lookup.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.IntStream;

public abstract class Partition {
    private static final int MAX_HASH_SIZE = 1 << 24; /* 16,777,216 */

    private static final int HASH_SEED = 219370429;

    final VirtualPageFile longKeyFile;
    final VirtualPageFile metadataBlobFile;

    final PartitionLookupCache lookupCache;

    private final HashFunction hashFunction;

    final int hashSize;

    final LookupData[] lookups;

    Partition(VirtualPageFile longKeyFile, VirtualPageFile metadataBlobFile, PartitionLookupCache lookupCache, int hashSize, int flushThreshold, boolean readOnly) {
        this.longKeyFile = longKeyFile;
        this.metadataBlobFile = metadataBlobFile;

        this.lookupCache = lookupCache;

        this.hashSize = hashSize;

        if (hashSize < 1) {
            throw new IllegalArgumentException("hashSize must be >= 1");
        }
        if (hashSize > MAX_HASH_SIZE) {
            throw new IllegalArgumentException("hashSize must be <= " + MAX_HASH_SIZE);
        }

        if (hashSize == 1) {
            hashFunction = null;
        } else {
            hashFunction = Hashing.murmur3_32(HASH_SEED);
        }

        lookups = IntStream.range(0, hashSize)
                .mapToObj(virtualFileNumber -> new LookupData(
                                new VirtualLongBlobStore(virtualFileNumber, longKeyFile),
                                new VirtualMutableBlobStore(virtualFileNumber, metadataBlobFile),
                                lookupCache,
                                flushThreshold,
                                readOnly
                        )
                )
                .toArray(LookupData[]::new);
    }

    int keyHash(LookupKey key) {
        if (hashFunction == null){
            return 0;
        } else {
            return Math.abs(hashFunction.hashBytes(key.bytes()).asInt()) % hashSize;
        }
    }

    static Path metadataPath(Path partitionDir) {
        return partitionDir.resolve("keyMetadata");
    }

    static Path keysPath(Path partitionDir) {
        return partitionDir.resolve("keys");
    }

    static void validatePartition(String partition) {
        if (partition == null) {
            throw new NullPointerException("null partition");
        }
        if (partition.isEmpty()) {
            throw new IllegalArgumentException("empty partition");
        }

        if (!isValidPartitionCharStart(partition.charAt(0))) {
            throw new IllegalArgumentException("bad first-char of partition: " + partition);
        }

        for (int i = partition.length() - 1; i > 0; i--) {
            if (!isValidPartitionCharPart(partition.charAt(i))) {
                throw new IllegalArgumentException("bad char at position " + i + " of partition: " + partition);
            }
        }
    }

    private static boolean isValidPartitionCharStart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    private static boolean isValidPartitionCharPart(char c) {
        return Character.isJavaIdentifierPart(c) || c == '-';
    }

    public long keyCount() {
        return Arrays.stream(lookups).mapToLong(LookupData::keyCount).sum();
    }
}
