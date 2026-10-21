package kaptainwutax.seedcrackerX.cracker.search;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.Igloo;
import com.seedfinding.mcfeature.structure.JunglePyramid;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Shipwreck;
import com.seedfinding.mcfeature.structure.SwampHut;
import com.seedfinding.mcfeature.structure.UniformStructure;
import com.seedfinding.mcseed.lcg.LCG;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureLifterTest {

    private static final long MASK_48 = (1L << 48) - 1;

    static List<UniformStructure<?>> typicalStructures(MCVersion version) {
        return List.of(new DesertPyramid(version), new Igloo(version), new SwampHut(version),
                new Shipwreck(version), new Shipwreck(version));
    }

    /** Places each structure where a world with this structure seed attempts it, in a random region. */
    static List<RegionStructure.Data<?>> attempts(long structureSeed, List<UniformStructure<?>> structures, Random random) {
        ChunkRand rand = new ChunkRand();
        List<RegionStructure.Data<?>> data = new ArrayList<>();

        for (UniformStructure<?> structure : structures) {
            CPos pos = structure.getInRegion(structureSeed, random.nextInt(400) - 200, random.nextInt(400) - 200, rand);
            data.add(structure.at(pos.getX(), pos.getZ()));
        }

        return data;
    }

    private static long bound(RegionStructure.Data<?> data) {
        return ((UniformStructure<?>) data.feature).getOffset();
    }

    private static boolean inlined(long seed, RegionStructure.Data<?> data) {
        return StructureLifter.matches(seed, data.baseRegionSeed, (int) bound(data), data.offsetX, data.offsetZ);
    }

    @ParameterizedTest
    @EnumSource(value = MCVersion.class, names = {"v1_13", "v1_13_1", "v1_16", "v1_21"})
    void inlinedCheckAgreesWithLibrary(MCVersion version) {
        Random random = new Random(version.ordinal());
        ChunkRand rand = new ChunkRand();
        List<UniformStructure<?>> structures = List.of(new DesertPyramid(version), new JunglePyramid(version), new Shipwreck(version));

        for (int i = 0; i < 2_000; i++) {
            long seed = random.nextLong() & MASK_48;
            for (RegionStructure.Data<?> data : attempts(seed, structures, random)) {
                assertTrue(inlined(seed, data), "true position rejected");
                assertTrue(data.testStart(seed, rand));

                for (int j = 0; j < 50; j++) {
                    long other = random.nextLong() & MASK_48;
                    assertEquals(data.testStart(other, rand), inlined(other, data));
                }
            }
        }
    }

    @Test
    void inlinedCheckAgreesWithLibraryWhenNextIntRejects() {
        // nextInt(bound) draws again when next(31) lands in the last, incomplete block of 2^31.
        // Build seeds whose first draw is the largest possible value so that path actually runs.
        LCG back = LCG.JAVA.combine(-1);
        ChunkRand rand = new ChunkRand();
        Random random = new Random(7);

        for (UniformStructure<?> structure : List.of(new DesertPyramid(MCVersion.v1_21), new Shipwreck(MCVersion.v1_21))) {
            RegionStructure.Data<?> data = structure.at(random.nextInt(1000), random.nextInt(1000));

            for (int i = 0; i < 1_000; i++) {
                long firstState = (0x7FFFFFFFL << 17) | (random.nextLong() & 0x1FFFFL);
                long seed = ((back.nextSeed(firstState) ^ LCG.JAVA.multiplier) - data.baseRegionSeed) & MASK_48;
                assertEquals(data.testStart(seed, rand), inlined(seed, data));
            }
        }
    }

    @Test
    void recoversStructureSeedFromFiveStructures() {
        Random random = new Random(42);

        for (int run = 0; run < 3; run++) {
            long seed = random.nextLong() & MASK_48;
            List<RegionStructure.Data<?>> data = attempts(seed, typicalStructures(MCVersion.v1_21), random);
            StructureLifter lifter = new StructureLifter(data, List.copyOf(data));

            StructureLifter.Result result = lifter.search(() -> false, ParallelSearch.Progress.NONE);

            assertTrue(result.complete());
            assertTrue(result.structureSeeds().contains(seed), "lost the real seed " + seed);
            ChunkRand rand = new ChunkRand();
            for (long found : result.structureSeeds()) {
                for (Feature.Data<?> d : data) {
                    assertTrue(d.testStart(found, rand), "returned a seed that doesn't fit the data");
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = MCVersion.class, names = {"v1_13", "v1_13_1"})
    void keepsTheSeedWhenShipwreckBoundsCanNotBeLifted(MCVersion version) {
        // Shipwrecks use nextInt(7) in 1.13 and nextInt(8) in 1.13.1-1.15, neither leaks low bits.
        Random random = new Random(version.ordinal());
        long seed = random.nextLong() & MASK_48;
        List<UniformStructure<?>> structures = List.of(new DesertPyramid(version), new Igloo(version), new SwampHut(version),
                new JunglePyramid(version), new Shipwreck(version), new Shipwreck(version));
        List<RegionStructure.Data<?>> data = attempts(seed, structures, random);

        StructureLifter.Result result = new StructureLifter(data, List.copyOf(data)).search(() -> false, ParallelSearch.Progress.NONE);

        assertTrue(result.complete());
        assertTrue(result.structureSeeds().contains(seed));
    }

    @Test
    void refusesToLiftPowerOfTwoBoundsOnly() {
        List<RegionStructure.Data<?>> data = attempts(123L, List.of(new Shipwreck(MCVersion.v1_14), new Shipwreck(MCVersion.v1_14)), new Random(1));
        StructureLifter lifter = new StructureLifter(data, List.copyOf(data));

        assertFalse(lifter.canLift());
        StructureLifter.Result result = lifter.search(() -> false, ParallelSearch.Progress.NONE);
        assertTrue(result.complete());
        assertTrue(result.structureSeeds().isEmpty());
    }

    @Test
    void cancellingStopsTheSearchPromptly() throws InterruptedException {
        // Two structures leave hundreds of low-bit candidates, minutes of work if nothing stops it.
        Random random = new Random(3);
        long seed = random.nextLong() & MASK_48;
        List<RegionStructure.Data<?>> data = attempts(seed, List.of(new DesertPyramid(MCVersion.v1_21), new Igloo(MCVersion.v1_21)), random);
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread canceller = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            cancelled.set(true);
        });

        long start = System.nanoTime();
        StructureLifter.Result result = new StructureLifter(data, List.copyOf(data)).search(cancelled::get, ParallelSearch.Progress.NONE);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        canceller.join();

        assertFalse(result.complete());
        assertTrue(elapsedMillis < 5_000, "took " + elapsedMillis + " ms to stop");
    }
}
