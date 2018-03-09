package com.upserve.uppend;

import com.google.common.primitives.*;
import com.upserve.uppend.util.ThreadLocalByteBuffers;
import org.slf4j.Logger;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class Blobs implements AutoCloseable, Flushable {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    public static final int DEFAULT_BLOB_STRIPES = 8;

    private final Path dir;

    private final int stripes;
    private final int stripeMask;
    private static final long MAX_POSITION = 256L * 256L * 256L * 256L * 256L * 256L; // 6 Bytes allows 256TB per stripe file

    // Replace with an array
    private final FileChannel[] blobChannels;
    private final MappedByteBuffer[] blobBuffers;
    private final AtomicLong[] blobFilePositions;
    private final AtomicInteger nextStripe;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean readOnly;

    public Blobs(Path blobDir,  int stripes) {
        this(blobDir, stripes,false);
    }

    public Blobs(Path blobDir,  int stripes, boolean readOnly) {
        this.readOnly = readOnly;

        this.dir = blobDir;
        this.stripes = stripes;
        this.stripeMask = stripes -1;

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to mkdirs: " + dir, e);
        }

        OpenOption[] openOptions;
        if (readOnly) {
            openOptions = new OpenOption[]{StandardOpenOption.READ};
        } else {
            openOptions = new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE};
        }

        blobChannels = new FileChannel[stripes];
        blobBuffers = new MappedByteBuffer[stripes];
        blobFilePositions = new AtomicLong[stripes];

        nextStripe = new AtomicInteger();

        for (int i = 0; i < stripes; i++) {
            Path file = dir.resolve("blobs." + String.valueOf(i));
            try {
                FileChannel chan = FileChannel.open(file, openOptions);
                blobFilePositions[i] = new AtomicLong(chan.size());
                blobChannels[i] = chan;
            } catch (IOException e) {
                throw new UncheckedIOException("unable to init or get size of blob file: " + file, e);
            }
        }
    }

    public long append(byte[] bytes) {
        int writeSize = bytes.length + 4;
        ByteBuffer intBuf = ThreadLocalByteBuffers.LOCAL_INT_BUFFER.get();
        intBuf.putInt(bytes.length).flip();

        final int stripe = getNextStripe();

        final long stripePos = blobFilePositions[stripe].getAndAdd(writeSize);

        try {
            int writen = blobChannels[stripe].write(ByteBuffer.wrap(Bytes.concat(intBuf.array(), bytes)), stripePos);
            if (writen != writeSize) throw new IOException("Bytes written" + writen + "did not equal write size " + writeSize);
        } catch (IOException e) {
            throw new UncheckedIOException("unable write " + writeSize + " bytes @ " + stripePos + " in blob stripe: " + stripe, e);
        }

        if (stripePos > MAX_POSITION) {
            throw new RuntimeException("Max blob file size exceeded: " + stripePos);
        }

        byte[] bytePos = Longs.toByteArray(stripePos);
        bytePos[0] = (byte) stripe;
        bytePos[1] = log2Ceiling(writeSize);

        log.trace("appended {} bytes to blob stripe file {} @ {}", bytes.length, stripe, stripePos);
        return Longs.fromByteArray(bytePos);
    }

    public long size() {
        if (readOnly) {
            try {

                long size = 0;
                for (int i = 0; i < stripes; i++) {
                    size += blobChannels[i].size();
                }
                return size;
            } catch (IOException e) {
                log.error("Unable to get blobs file size: " + dir, e);
                return -1;
            }
        } else {
            return Arrays.stream(blobFilePositions).mapToLong(AtomicLong::get).sum();
        }

    }


    public byte[] read(long encodedLong) {
        byte[] longBytes = Longs.toByteArray(encodedLong);
        log.trace("reading from {} with {}", dir, longBytes);

        int stripe = byteToUnsignedInt(longBytes[0]);
        int pos = Ints.fromBytes(longBytes[4], longBytes[5], longBytes[6], longBytes[7]);

        byte[] result;

        try {
            if (blobBuffers[stripe] == null || blobBuffers[stripe].capacity() != blobChannels[stripe].size()) {
                synchronized (Integer.valueOf(stripe)) {
                    if (blobBuffers[stripe] == null || blobBuffers[stripe].capacity() != blobChannels[stripe].size()) {
                        blobBuffers[stripe] = blobChannels[stripe].map(FileChannel.MapMode.READ_ONLY, 0, blobChannels[stripe].size());
                        log.info("Re-reading {}: {} != {}", stripe, blobBuffers[stripe].capacity(), blobChannels[stripe].size());
                    }
                }
            }
            int readSize = blobBuffers[stripe].getInt(pos);
            result = new byte[readSize];
            for (int i = 0; i < readSize; i++) {
                result[i] = blobBuffers[stripe].get(pos + i + 4);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read bytes from blob stripe " + stripe + " @ " + pos, e);
        }

        log.trace("read {} bytes from blob stripe {} @ {}", result.length, stripe, pos);
        return result;
    }

    public void clear() {
        log.trace("clearing {}", dir);

        for (int i = 0; i < stripes; i++) {
            try {
                blobFilePositions[i].set(0);
                blobChannels[i].truncate(0);
            } catch (IOException e) {
                throw new UncheckedIOException("unable to clear blob stripe : " + i, e);
            }
        }
        nextStripe.set(0);
       log.trace("clear complete {}", dir);
    }

    @Override
    public void close() {
        log.trace("closing blobs {}", dir);
        closed.set(true);
        for (int i = 0; i < stripes; i++) {
            try {
                blobChannels[i].close();
            } catch (IOException e) {
                throw new UncheckedIOException("unable to close blob stripe : " + i, e);
            }
        }
        log.trace("closed blobs {}", dir);
    }

    @Override
    public void flush() {
        log.trace("flushing blobs {}", dir);
        if (readOnly) return;

        for (int i = 0; i < stripes; i++) {
            try {
                blobChannels[i].force(true);
            } catch (IOException e) {
                if (closed.get()) {
                    log.debug("Unable to flush closed blobs {}", i, e);
                } else {
                    throw new UncheckedIOException("unable to flush blob stripe: " + i, e);
                }
            }
        }
        log.trace("flushed blobs {}", dir);
    }

    private byte log2Ceiling(int val) {
        if (val < 1) return 0;
        double scaledVal = Math.ceil(Math.log(val) / Math.log(2));
        return (byte) scaledVal;
    }

    private int intPowTwo(byte bval) {
        double ceilingVal = Math.min(Integer.MAX_VALUE, Math.pow(2, byteToUnsignedInt(bval)));
        return (int) ceilingVal;
    }

    private int byteToUnsignedInt(byte bval){
        int val = 0;
        val |= bval & 0xFF;
        return val;
    }

    private int getNextStripe() {
        return nextStripe.getAndIncrement() & stripeMask;
    }
}
