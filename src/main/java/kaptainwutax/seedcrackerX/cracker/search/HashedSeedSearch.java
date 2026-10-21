package kaptainwutax.seedcrackerX.cracker.search;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Finds the upper 16 bits of the world seed by matching the hashed seed the server sends on login
 * (the first 8 bytes of SHA-256 over the little-endian world seed).
 */
public final class HashedSeedSearch {

    private static final int UPPER_BITS = 16;
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    private HashedSeedSearch() {
    }

    /** Same as {@code WorldSeed.toHash}, without allocating a digest per call. */
    public static long hash(long worldSeed) {
        byte[] buffer = new byte[32];
        return hash(worldSeed, SHA_256.get(), buffer);
    }

    private static long hash(long worldSeed, MessageDigest digest, byte[] buffer) {
        for (int i = 0; i < 8; i++) {
            buffer[i] = (byte) (worldSeed >>> (i << 3));
        }

        digest.update(buffer, 0, 8);
        try {
            digest.digest(buffer, 0, 32);
        } catch (DigestException e) {
            throw new IllegalStateException(e);
        }

        long hash = 0;
        for (int i = 0; i < 8; i++) {
            hash |= (buffer[i] & 0xFFL) << (i << 3);
        }
        return hash;
    }

    /**
     * Tries all 2^16 upper bits for every structure seed.
     *
     * @return the world seeds whose hash matches, and whether every structure seed was tried
     */
    public static Result search(long[] structureSeeds, long hashedSeed, BooleanSupplier cancelled, ParallelSearch.Progress progress) {
        Set<Long> worldSeeds = ConcurrentHashMap.newKeySet();
        long perSeed = 1L << UPPER_BITS;

        boolean complete = ParallelSearch.forEachChunk(structureSeeds.length * perSeed, perSeed, cancelled, (from, to) -> {
            long structureSeed = structureSeeds[(int) (from >>> UPPER_BITS)];
            MessageDigest digest = SHA_256.get();
            byte[] buffer = new byte[32];

            for (long upper = 0; upper < perSeed; upper++) {
                long worldSeed = (upper << 48) | structureSeed;
                if (hash(worldSeed, digest, buffer) == hashedSeed) {
                    worldSeeds.add(worldSeed);
                }
            }
        }, progress);

        return new Result(worldSeeds, complete);
    }

    public record Result(Set<Long> worldSeeds, boolean complete) {
    }
}
