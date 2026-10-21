package kaptainwutax.seedcrackerX.cracker.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelSearchTest {

    @Test
    void visitsEveryCandidateExactlyOnce() {
        int total = 1_000_003;
        AtomicIntegerArray visits = new AtomicIntegerArray(total);
        AtomicLong lastProgress = new AtomicLong();

        boolean complete = ParallelSearch.forEachChunk(total, 4096, () -> false, (from, to) -> {
            for (long i = from; i < to; i++) visits.incrementAndGet((int) i);
        }, (done, all) -> lastProgress.accumulateAndGet(done, Math::max));

        assertTrue(complete);
        assertEquals(total, lastProgress.get());
        for (int i = 0; i < total; i++) {
            assertEquals(1, visits.get(i), "candidate " + i);
        }
    }

    @Test
    void failingChunkStopsTheSearchAndPoolStaysUsable() {
        AtomicLong chunksRun = new AtomicLong();

        RuntimeException e = assertThrows(RuntimeException.class, () ->
                ParallelSearch.forEachChunk(1L << 40, 1L << 10, () -> false, (from, to) -> {
                    chunksRun.incrementAndGet();
                    if (from == 5L << 10) throw new IllegalStateException("boom");
                }, ParallelSearch.Progress.NONE));

        assertTrue(e.getCause() instanceof IllegalStateException);
        // Without the stop flag the other workers would grind through 2^30 chunks.
        assertTrue(chunksRun.get() < 1_000_000, "kept running after the failure: " + chunksRun.get());

        AtomicLong sum = new AtomicLong();
        assertTrue(ParallelSearch.forEachChunk(1000, 7, () -> false, (from, to) -> {
            for (long i = from; i < to; i++) sum.addAndGet(i);
        }, ParallelSearch.Progress.NONE));
        assertEquals(999 * 1000 / 2, sum.get());
    }
}
