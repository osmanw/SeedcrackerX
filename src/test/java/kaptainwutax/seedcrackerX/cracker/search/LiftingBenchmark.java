package kaptainwutax.seedcrackerX.cracker.search;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.UniformStructure;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Times the lifting search against the implementation it replaced, on the same data, and checks both agree.
 * Run with {@code ./gradlew liftingBenchmark}; pass {@code --skip-legacy} to time only the new search.
 */
public final class LiftingBenchmark {

    private static final MCVersion VERSION = MCVersion.v1_21;

    public static void main(String[] args) {
        boolean skipLegacy = Arrays.asList(args).contains("--skip-legacy");
        Random random = new Random(2026);
        long seed = random.nextLong() & ((1L << 48) - 1);
        List<RegionStructure.Data<?>> data = StructureLifterTest.attempts(seed, StructureLifterTest.typicalStructures(VERSION), random);
        List<Feature.Data<?>> filters = List.copyOf(data);

        double bits = data.stream().mapToDouble(d -> 2 * Math.log(((UniformStructure<?>) d.feature).getOffset()) / Math.log(2)).sum();
        StructureLifter lifter = new StructureLifter(data, filters);
        System.out.printf("cores=%d workers=%d structures=%d liftingBits=%.1f lowBits=%d lowCandidates=%d%n",
                Runtime.getRuntime().availableProcessors(), ParallelSearch.PARALLELISM, data.size(), bits,
                lifter.lowBitCount(), lifter.liftLowBits().length);

        Set<Long> expected = null;
        if (!skipLegacy) {
            long start = System.nanoTime();
            expected = legacyLift(data, filters);
            System.out.printf("legacy: %.2f s, %d seeds%n", (System.nanoTime() - start) / 1e9, expected.size());
        }

        lifter.search(() -> false, ParallelSearch.Progress.NONE); // warm up
        double[] seconds = new double[5];
        Set<Long> actual = null;
        for (int i = 0; i < seconds.length; i++) {
            long start = System.nanoTime();
            StructureLifter.Result result = lifter.search(() -> false, ParallelSearch.Progress.NONE);
            seconds[i] = (System.nanoTime() - start) / 1e9;
            actual = result.structureSeeds();
        }
        Arrays.sort(seconds);
        System.out.printf("new:    %.3f s median (min %.3f, max %.3f), %d seeds%n",
                seconds[seconds.length / 2], seconds[0], seconds[seconds.length - 1], actual.size());

        if (!actual.contains(seed)) throw new AssertionError("new search lost the real seed");
        if (expected != null && !expected.equals(actual)) throw new AssertionError("legacy and new search disagree");
        System.out.println(expected != null ? "results identical, real seed found" : "real seed found");
    }

    /** The lifting from TimeMachine before the rewrite, unchanged except for taking the version as a parameter. */
    static Set<Long> legacyLift(List<RegionStructure.Data<?>> dataList, List<Feature.Data<?>> cache) {
        Stream<Long> lowerBitsStream = LongStream.range(0, 1L << 19).boxed().filter(lowerBits -> {
            ChunkRand rand = new ChunkRand();
            for (RegionStructure.Data<?> data : dataList) {
                rand.setRegionSeed(lowerBits, data.regionX, data.regionZ, data.feature.getSalt(), VERSION);
                if (rand.nextInt(((UniformStructure<?>) data.feature).getOffset()) % 4 != data.offsetX % 4 ||
                        rand.nextInt(((UniformStructure<?>) data.feature).getOffset()) % 4 != data.offsetZ % 4) {
                    return false;
                }
            }
            return true;
        });

        Stream<Long> seedStream = lowerBitsStream.flatMap(lowerBits ->
                LongStream.range(0, 1L << (48 - 19))
                        .boxed()
                        .map(upperBits -> (upperBits << 19) | lowerBits)
        );

        Stream<Long> strutureSeedStream = seedStream.filter(seed -> {
            ChunkRand rand = new ChunkRand();
            for (Feature.Data<?> data : cache) {
                if (!data.testStart(seed, rand)) {
                    return false;
                }
            }
            return true;
        });

        return strutureSeedStream.parallel().collect(Collectors.toSet());
    }
}
