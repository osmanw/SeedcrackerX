package kaptainwutax.seedcrackerX.cracker.search;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Runs a brute force over a range of candidates on a shared pool of worker threads.
 * Workers pull fixed-size chunks from a shared cursor, so every core stays busy until the range is exhausted.
 */
public final class ParallelSearch {

    /** One core is left free so the game keeps running smoothly while a search is going on. */
    public static final int PARALLELISM = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private static final ExecutorService POOL = Executors.newFixedThreadPool(PARALLELISM,
            Thread.ofPlatform().daemon().name("SeedCrackerX-search-", 0).factory());

    private ParallelSearch() {
    }

    @FunctionalInterface
    public interface RangeTask {
        /** Searches the candidates in [from, to). Called concurrently from several threads. */
        void run(long from, long to);
    }

    @FunctionalInterface
    public interface Progress {
        Progress NONE = (done, total) -> {
        };

        /** Called concurrently from several threads after each chunk. */
        void onProgress(long done, long total);
    }

    /**
     * Runs {@code task} over [0, total) in chunks of {@code chunkSize}.
     *
     * @return true if the whole range was searched, false if it was cancelled or the calling thread was interrupted
     * @throws RuntimeException wrapping the first failure of any chunk; the other workers stop at their next chunk
     */
    public static boolean forEachChunk(long total, long chunkSize, BooleanSupplier cancelled, RangeTask task, Progress progress) {
        if (total <= 0) return true;
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");

        AtomicLong cursor = new AtomicLong();
        AtomicLong done = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        long chunks = (total + chunkSize - 1) / chunkSize;
        int workers = (int) Math.min(PARALLELISM, chunks);
        List<Future<?>> futures = new ArrayList<>(workers);

        for (int i = 0; i < workers; i++) {
            futures.add(POOL.submit(() -> {
                try {
                    long start;
                    while (!stop.get() && !cancelled.getAsBoolean() && (start = cursor.getAndAdd(chunkSize)) < total) {
                        long end = Math.min(start + chunkSize, total);
                        task.run(start, end);
                        progress.onProgress(done.addAndGet(end - start), total);
                    }
                } catch (RuntimeException | Error e) {
                    stop.set(true);
                    throw e;
                }
                return null;
            }));
        }

        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (ExecutionException e) {
            stop.set(true);
            futures.forEach(f -> f.cancel(true));
            throw new RuntimeException("Seed search failed", e.getCause());
        } catch (InterruptedException e) {
            stop.set(true);
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            return false;
        }

        return done.get() == total;
    }
}
