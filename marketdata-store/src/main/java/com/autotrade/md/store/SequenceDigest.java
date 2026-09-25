package com.autotrade.md.store;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over (sequence, hash64) pairs in sequence order. Computed once over the source stream and
 * once over the stored rows; equal digests mean every row arrived, once, in order, with its lineage.
 */
public final class SequenceDigest {

    private final MessageDigest sha = newSha256();
    private final ByteBuffer pair = ByteBuffer.allocate(16);
    private long count;
    private long first;
    private long last;
    private boolean strictlyIncreasing = true;

    public void add(long sequence, long hash64) {
        if (count > 0 && sequence <= last) {
            strictlyIncreasing = false;
        }
        if (count == 0) {
            first = sequence;
        }
        last = sequence;
        count++;
        pair.clear();
        pair.putLong(sequence).putLong(hash64);
        sha.update(pair.array());
    }

    public Result result() {
        MessageDigest copy;
        try {
            copy = (MessageDigest) sha.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
        return new Result(count, count == 0 ? null : first, count == 0 ? null : last,
                HexFormat.of().formatHex(copy.digest()), strictlyIncreasing);
    }

    /** First 8 bytes of a SHA-256 hash, big-endian. */
    public static long hash64(byte[] sha256) {
        if (sha256.length < 8) {
            throw new IllegalArgumentException("hash too short");
        }
        return ByteBuffer.wrap(sha256, 0, 8).getLong();
    }

    public static long hash64(String sha256Hex) {
        return hash64(HexFormat.of().parseHex(sha256Hex));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Result(long count, Long firstSequence, Long lastSequence, String sha256, boolean strictlyIncreasing) {

        public boolean sameAs(Result other) {
            return count == other.count && sha256.equals(other.sha256);
        }
    }
}
