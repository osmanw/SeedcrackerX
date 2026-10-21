package kaptainwutax.seedcrackerX.cracker.search;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.UniformStructure;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Recovers 48-bit structure seeds from the chunk positions of uniformly spread structures ("lifting").
 *
 * <p>A structure's offset inside its region is {@code nextInt(bound)} of a Java LCG seeded with
 * {@code (structureSeed + regionSeed) ^ multiplier}. When {@code 2^k} divides a bound that isn't a power of two,
 * the lowest {@code k} bits of the offset are bits {@code 17 .. 16+k} of the LCG state, and those only depend on
 * the lowest {@code 17+k} bits of the structure seed. The low bits are therefore recovered one bit at a time,
 * and only the remaining upper bits are brute forced, in parallel.
 */
public final class StructureLifter {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK_48 = (1L << 48) - 1;
    /** next(31) drops the lowest 17 bits of the LCG state. */
    private static final int DROPPED_BITS = 17;
    private static final long MAX_CHUNK = 1L << 22;

    private final long[] baseSeeds;
    private final int[] bounds;
    private final int[] offsetsX;
    private final int[] offsetsZ;
    private final int[] liftBits;
    /** Indices into the arrays above, most selective first, for the upper-bit brute force. */
    private final int[] checkOrder;
    private final int maxLiftBits;
    private final List<Feature.Data<?>> filters;

    /**
     * @param structures positions of uniformly spread structures; these drive the lifting
     * @param filters    every structure seed that is returned passes {@code testStart} for all of these
     */
    public StructureLifter(List<? extends RegionStructure.Data<?>> structures, List<Feature.Data<?>> filters) {
        int count = structures.size();
        this.baseSeeds = new long[count];
        this.bounds = new int[count];
        this.offsetsX = new int[count];
        this.offsetsZ = new int[count];
        this.liftBits = new int[count];

        for (int i = 0; i < count; i++) {
            RegionStructure.Data<?> data = structures.get(i);
            if (!(data.feature instanceof UniformStructure<?> structure)) {
                throw new IllegalArgumentException(data.feature.getName() + " is not uniformly spread");
            }
            this.baseSeeds[i] = data.baseRegionSeed;
            this.bounds[i] = structure.getOffset();
            this.offsetsX[i] = data.offsetX;
            this.offsetsZ[i] = data.offsetZ;
            this.liftBits[i] = liftableBits(structure.getOffset());
        }

        this.maxLiftBits = Arrays.stream(this.liftBits).max().orElse(0);
        this.checkOrder = IntStream.range(0, count).boxed()
                .sorted(Comparator.comparingDouble(i -> (double) (1 << this.liftBits[i]) / this.bounds[i]))
                .mapToInt(Integer::intValue)
                .toArray();
        this.filters = List.copyOf(filters);
    }

    /** How many low bits of a {@code nextInt(bound)} result only depend on the low bits of the seed. */
    static int liftableBits(int bound) {
        // Power-of-two bounds take the high bits of the state instead.
        if ((bound & -bound) == bound) return 0;
        return Integer.numberOfTrailingZeros(bound);
    }

    public boolean canLift() {
        return this.maxLiftBits > 0;
    }

    /** Number of low structure seed bits that lifting recovers. */
    public int lowBitCount() {
        return this.canLift() ? DROPPED_BITS + this.maxLiftBits : 0;
    }

    /** All values of the low {@link #lowBitCount()} bits that are consistent with every structure. */
    public long[] liftLowBits() {
        if (!this.canLift()) return new long[0];

        long[] candidates = LongStream.range(0, 1L << (DROPPED_BITS + 1))
                .filter(low -> this.matchesLowBits(low, 1))
                .toArray();

        for (int k = 2; k <= this.maxLiftBits; k++) {
            long newBit = 1L << (DROPPED_BITS + k - 1);
            int level = k;
            candidates = Arrays.stream(candidates)
                    .flatMap(low -> LongStream.of(low, low | newBit))
                    .filter(low -> this.matchesLowBits(low, level))
                    .toArray();
        }

        return candidates;
    }

    /** Checks the lowest {@code k} offset bits of every structure that has at least {@code k} liftable bits. */
    private boolean matchesLowBits(long lowSeed, int k) {
        long mask = (1L << (DROPPED_BITS + k)) - 1;
        int offsetMask = (1 << k) - 1;

        for (int i = 0; i < this.baseSeeds.length; i++) {
            if (this.liftBits[i] < k) continue;

            long state = ((this.baseSeeds[i] + lowSeed) ^ MULTIPLIER) & mask;
            state = (state * MULTIPLIER + ADDEND) & mask;
            if (((int) (state >>> DROPPED_BITS) & offsetMask) != (this.offsetsX[i] & offsetMask)) return false;
            state = (state * MULTIPLIER + ADDEND) & mask;
            if (((int) (state >>> DROPPED_BITS) & offsetMask) != (this.offsetsZ[i] & offsetMask)) return false;
        }

        return true;
    }

    /**
     * Lifts the low bits, then brute forces the upper bits of every low-bit candidate.
     *
     * @return the matching structure seeds, and whether the search ran to the end
     */
    public Result search(BooleanSupplier cancelled, ParallelSearch.Progress progress) {
        Set<Long> seeds = ConcurrentHashMap.newKeySet();
        if (!this.canLift()) return new Result(seeds, true);

        long[] lowCandidates = this.liftLowBits();
        int lowBits = this.lowBitCount();
        int upperBits = 48 - lowBits;
        long upperCount = 1L << upperBits;
        // Both are powers of two, so a chunk never spans two low-bit candidates.
        long chunkSize = Math.min(MAX_CHUNK, upperCount);

        boolean complete = ParallelSearch.forEachChunk(lowCandidates.length * upperCount, chunkSize, cancelled, (from, to) -> {
            long low = lowCandidates[(int) (from >>> upperBits)];
            long upperStart = from & (upperCount - 1);
            long upperEnd = upperStart + (to - from);
            ChunkRand rand = new ChunkRand();

            for (long upper = upperStart; upper < upperEnd; upper++) {
                long seed = (upper << lowBits) | low;
                if (this.matchesAll(seed) && this.passesFilters(seed, rand)) {
                    seeds.add(seed);
                }
            }
        }, progress);

        return new Result(seeds, complete);
    }

    private boolean matchesAll(long seed) {
        for (int i : this.checkOrder) {
            if (!matches(seed, this.baseSeeds[i], this.bounds[i], this.offsetsX[i], this.offsetsZ[i])) return false;
        }
        return true;
    }

    private boolean passesFilters(long seed, ChunkRand rand) {
        for (Feature.Data<?> filter : this.filters) {
            if (!filter.testStart(seed, rand)) return false;
        }
        return true;
    }

    /** Same as {@code UniformStructure.canStart}, with Java's {@code nextInt} inlined. */
    static boolean matches(long seed, long baseSeed, int bound, int offsetX, int offsetZ) {
        long state = ((baseSeed + seed) ^ MULTIPLIER) & MASK_48;

        if ((bound & -bound) == bound) {
            state = (state * MULTIPLIER + ADDEND) & MASK_48;
            if ((int) ((bound * (state >>> DROPPED_BITS)) >> 31) != offsetX) return false;
            state = (state * MULTIPLIER + ADDEND) & MASK_48;
            return (int) ((bound * (state >>> DROPPED_BITS)) >> 31) == offsetZ;
        }

        int bits;
        int value;
        do {
            state = (state * MULTIPLIER + ADDEND) & MASK_48;
            bits = (int) (state >>> DROPPED_BITS);
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0);
        if (value != offsetX) return false;

        do {
            state = (state * MULTIPLIER + ADDEND) & MASK_48;
            bits = (int) (state >>> DROPPED_BITS);
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0);
        return value == offsetZ;
    }

    public record Result(Set<Long> structureSeeds, boolean complete) {
    }
}
