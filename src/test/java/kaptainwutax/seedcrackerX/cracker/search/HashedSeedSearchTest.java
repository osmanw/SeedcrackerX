package kaptainwutax.seedcrackerX.cracker.search;

import com.seedfinding.mccore.rand.seed.WorldSeed;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashedSeedSearchTest {

    private static final long MASK_48 = (1L << 48) - 1;

    @Test
    void hashAgreesWithLibrary() {
        Random random = new Random(11);
        for (int i = 0; i < 10_000; i++) {
            long seed = random.nextLong();
            assertEquals(WorldSeed.toHash(seed), HashedSeedSearch.hash(seed));
        }
    }

    @Test
    void findsWorldSeedAmongManyStructureSeeds() {
        Random random = new Random(12);
        long worldSeed = random.nextLong();
        long[] candidates = new long[64];
        for (int i = 0; i < candidates.length; i++) {
            candidates[i] = random.nextLong() & MASK_48;
        }
        candidates[37] = worldSeed & MASK_48;

        HashedSeedSearch.Result result = HashedSeedSearch.search(candidates, WorldSeed.toHash(worldSeed), () -> false, ParallelSearch.Progress.NONE);

        assertTrue(result.complete());
        assertEquals(Set.of(worldSeed), result.worldSeeds());
    }
}
