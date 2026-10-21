package kaptainwutax.seedcrackerX.cracker.storage;

import com.seedfinding.mcbiome.source.OverworldBiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.rand.seed.PillarSeed;
import com.seedfinding.mccore.rand.seed.StructureSeed;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.OldStructure;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Shipwreck;
import com.seedfinding.mcseed.lcg.LCG;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.cracker.BiomeData;
import kaptainwutax.seedcrackerX.cracker.decorator.Decorator;
import kaptainwutax.seedcrackerX.cracker.search.HashedSeedSearch;
import kaptainwutax.seedcrackerX.cracker.search.ParallelSearch;
import kaptainwutax.seedcrackerX.cracker.search.StructureLifter;
import kaptainwutax.seedcrackerX.util.Database;
import kaptainwutax.seedcrackerX.util.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class TimeMachine {
    private static final Logger logger = LoggerFactory.getLogger("timeMachine");

    /** Runs the cracking steps. Shared by all time machines so clearing the data doesn't leak threads. */
    public static final ExecutorService SERVICE = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory());
    /** Checking the hashed seed costs 2^16 SHA-256 hashes per structure seed. */
    private static final int MAX_HASHED_SEED_CANDIDATES = 1 << 17;

    private final LCG inverseLCG = LCG.JAVA.combine(-2);
    public volatile boolean isRunning = false;
    public volatile boolean shouldTerminate = false;
    public List<Integer> pillarSeeds = null;
    public volatile Set<Long> structureSeeds = ConcurrentHashMap.newKeySet();
    public final Set<Long> worldSeeds = ConcurrentHashMap.newKeySet();
    protected DataStorage dataStorage;

    public TimeMachine(DataStorage dataStorage) {
        this.dataStorage = dataStorage;
    }

    /** Replaces the structure seed candidates with a set that stays safe to read while the cracker runs. */
    public void setStructureSeeds(Collection<Long> seeds) {
        Set<Long> copy = ConcurrentHashMap.newKeySet(seeds.size());
        copy.addAll(seeds);
        this.structureSeeds = copy;
    }

    public void poke(Phase phase) {
        if (this.worldSeeds.size() == 1) return;
        this.isRunning = true;

        while (phase != null && !this.shouldTerminate) {
            if (phase != Phase.BIOMES && pokeStructureReduce()) {
                phase = Phase.BIOMES;
                continue;

            } else if (phase == Phase.STRUCTURES) {
                if (!pokeStructures()) break;

            } else if (phase == Phase.LIFTING) {
                if (!pokeStructures() && !pokeLifting()) break;

            } else if (phase == Phase.PILLARS) {
                if (!this.pokePillars()) break;

            } else if (phase == Phase.BIOMES) {
                if (!this.pokeBiomes()) break;
            }

            phase = phase.nextPhase();
        }
        if (this.worldSeeds.size() == 1 && !this.shouldTerminate) {
            long seed = worldSeeds.stream().findFirst().get();
            SeedCracker.entrypoints.forEach(entrypoint -> entrypoint.pushWorldSeed(seed));
            Minecraft client = Minecraft.getInstance();
            if (Config.get().databaseSubmits && client.getConnection().getOnlinePlayers().size() > 10 &&
                    !client.getConnection().getConnection().isMemoryConnection()) {
                Component text = Database.joinFakeServerForAuth();
                if (text == null) {
                    Database.handleDatabaseCall(seed);
                } else {
                    Log.error(text.getString());
                }
            }
        }
    }

    protected boolean pokePillars() {
        if (this.pillarSeeds != null || this.dataStorage.pillarData == null) return false;
        this.pillarSeeds = new ArrayList<>();

        Log.debug("====================================");
        Log.warn("tmachine.lookingForPillarSeed");

        for (int pillarSeed = 0; pillarSeed < 1 << 16 && !this.shouldTerminate; pillarSeed++) {
            if (this.dataStorage.pillarData.test(pillarSeed)) {
                Log.printSeed("tmachine.foundPillarSeed", pillarSeed);
                this.pillarSeeds.add(pillarSeed);
            }
        }

        if (!this.pillarSeeds.isEmpty()) {
            Log.warn("tmachine.pillarSeedSearchFinished");
        } else {
            Log.error("finishedSearchNoResult");
        }

        return true;
    }

    protected boolean pokeLifting() {
        if (!this.structureSeeds.isEmpty() || this.dataStorage.getLiftingBits() < 40F) return false;
        List<RegionStructure.Data<?>> dataList = new ArrayList<>();

        for (DataStorage.Entry<Feature.Data<?>> e : this.dataStorage.baseSeedData) {
            if (e.data.feature instanceof OldStructure || e.data.feature instanceof Shipwreck) {
                dataList.add((RegionStructure.Data<?>) e.data);
            }
        }

        StructureLifter lifter = new StructureLifter(dataList, this.structureFilters());
        if (!lifter.canLift()) {
            Log.error("tmachine.cannotLift");
            return false;
        }

        Log.warn("tmachine.startLifting", dataList.size());
        StructureLifter.Result result = lifter.search(() -> this.shouldTerminate, ParallelSearch.Progress.NONE);
        if (!result.complete()) return false;

        this.setStructureSeeds(result.structureSeeds());

        if (!this.structureSeeds.isEmpty()) {
            Log.warn("tmachine.structureSeedSearchFinished");
        } else {
            Log.error("finishedSearchNoResult");
        }

        return !this.structureSeeds.isEmpty();
    }


    protected boolean pokeStructures() {
        if (this.pillarSeeds == null || !this.structureSeeds.isEmpty() ||
                this.dataStorage.getBaseBits() < this.dataStorage.getWantedBits()) return false;

        List<Feature.Data<?>> cache = this.structureFilters();
        Set<Long> seeds = this.structureSeeds;

        for (int pillarSeed : this.pillarSeeds) {
            Log.debug("====================================");
            Log.warn("tmachine.lookingForStructureSeeds", pillarSeed);

            boolean complete = ParallelSearch.forEachChunk(1L << 32, 1L << 22, () -> this.shouldTerminate, (from, to) -> {
                ChunkRand rand = new ChunkRand();

                for (long partialWorldSeed = from; partialWorldSeed < to; partialWorldSeed++) {
                    long seed = this.timeMachine(partialWorldSeed, pillarSeed);

                    boolean matches = true;

                    for (Feature.Data<?> baseSeedDatum : cache) {
                        if (!baseSeedDatum.testStart(seed, rand)) {
                            matches = false;
                            break;
                        }
                    }

                    if (matches) {
                        seeds.add(seed);
                        Log.printSeed("foundStructureSeed", seed);
                    }
                }
            }, progressLog());

            if (!complete) {
                return false;
            }
        }

        if (!this.structureSeeds.isEmpty()) {
            Log.warn("tmachine.structureSeedSearchFinished");
        } else {
            Log.error("finishedSearchNoResult");
        }

        return true;
    }

    protected boolean pokeBiomes() {
        if (this.structureSeeds.isEmpty() || this.worldSeeds.size() == 1) return false;
        // The hashed seed is still cheap with many candidates; the decorator and biome searches aren't.
        boolean fewCandidates = this.structureSeeds.size() <= 1000;
        boolean hasHashedSeed = this.dataStorage.hashedSeedData != null && this.dataStorage.hashedSeedData.getHashedSeed() != 0;
        if (!fewCandidates && !hasHashedSeed) return false;

        Log.debug("====================================");

        worldSeeds.clear();
        dataStorage.baseSeedData.dump();
        if (fewCandidates && Config.get().getVersion().isNewerOrEqualTo(MCVersion.v1_18) && dataStorage.getDecoratorBits() > 32F) {
            Log.warn("tmachine.decoratorWorldSeedSearch");
            WorldgenRandom rand = new WorldgenRandom(new XoroshiroRandomSource(0));


            for (long structureSeed : this.structureSeeds) {
                for (long upperBits = 0; upperBits < 1 << 16 && !this.shouldTerminate; upperBits++) {
                    long worldSeed = (upperBits << 48) | structureSeed;

                    boolean matches = true;

                    for (DataStorage.Entry<Feature.Data<?>> e : this.dataStorage.baseSeedData.getBaseSet()) {
                        if (e.data.feature instanceof Decorator && !((Decorator.Data<?>) e.data).testStart(worldSeed, rand)) {
                            matches = false;
                            break;
                        }
                    }

                    if (matches) {
                        this.worldSeeds.add(worldSeed);
                        if (this.worldSeeds.size() < 10) {
                            Log.printSeed("tmachine.foundWorldSeed", worldSeed);
                            if (this.worldSeeds.size() == 9) {
                                Log.warn("tmachine.printSeedsInConsole");
                            }
                        } else {
                            logger.info("Found world seed " + worldSeed);
                        }
                    }

                    if (this.shouldTerminate) {
                        return false;
                    }
                }
            }
            if (!this.worldSeeds.isEmpty()) {
                Log.warn("tmachine.worldSeedSearchFinished");
                return true;
            } else {
                Log.warn("finishedSearchNoResult");
            }

        }

        if (hasHashedSeed && this.structureSeeds.size() > MAX_HASHED_SEED_CANDIDATES) {
            Log.warn("tmachine.tooManyForHashedSeed", this.structureSeeds.size());
        } else if (hasHashedSeed) {
            Log.warn("tmachine.hashedSeedWorldSeedSearch");
            long[] candidates = this.structureSeeds.stream().mapToLong(Long::longValue).toArray();
            HashedSeedSearch.Result result = HashedSeedSearch.search(candidates, this.dataStorage.hashedSeedData.getHashedSeed(),
                    () -> this.shouldTerminate, candidates.length > 1000 ? progressLog() : ParallelSearch.Progress.NONE);

            if (!result.complete()) {
                return false;
            }

            result.worldSeeds().forEach(worldSeed -> {
                this.worldSeeds.add(worldSeed);
                Log.printSeed("tmachine.foundWorldSeed", worldSeed);
            });

            if (!this.worldSeeds.isEmpty()) {
                Log.warn("tmachine.worldSeedSearchFinished");
                return true;
            } else {
                this.dataStorage.hashedSeedData = null;
                Log.error("tmachine.noResultsRevertingToBiomes");
            }
        }

        if (!fewCandidates) return false;

        this.dataStorage.biomeSeedData.dump();
        if (this.dataStorage.notEnoughBiomeData()) {
            Log.error("tmachine.moreBiomesNeeded");
            return false;
        }

        Log.warn("tmachine.biomeWorldSeedSearch", this.dataStorage.biomeSeedData.size());
        Log.warn("tmachine.fuzzyBiomeSearch");
        MCVersion version = Config.get().getVersion();
        for (long structureSeed : this.structureSeeds) {
            for (long worldSeed : StructureSeed.toRandomWorldSeeds(structureSeed)) {
                OverworldBiomeSource source = new OverworldBiomeSource(version, worldSeed);

                boolean matches = true;

                for (DataStorage.Entry<BiomeData> e : this.dataStorage.biomeSeedData) {
                    if (!e.data.test(source)) {
                        matches = false;
                        break;
                    }
                }

                if (matches) {
                    this.worldSeeds.add(worldSeed);
                    Log.printSeed("tmachine.foundWorldSeed", worldSeed);
                }
                if (this.shouldTerminate) {
                    return false;
                }
            }
        }

        if (!this.worldSeeds.isEmpty()) return true;
        if (this.structureSeeds.size() > 10) return false;
        Log.warn("tmachine.deepBiomeSearch");
        for (long structureSeed : this.structureSeeds) {
            for (long upperBits = 0; upperBits < 1 << 16 && !this.shouldTerminate; upperBits++) {
                long worldSeed = (upperBits << 48) | structureSeed;

                OverworldBiomeSource source = new OverworldBiomeSource(version, worldSeed);

                boolean matches = true;

                for (DataStorage.Entry<BiomeData> e : this.dataStorage.biomeSeedData) {
                    if (!e.data.test(source)) {
                        matches = false;
                        break;
                    }
                }

                if (matches) {
                    this.worldSeeds.add(worldSeed);
                    if (this.worldSeeds.size() < 10) {
                        Log.printSeed("tmachine.foundWorldSeed", worldSeed);
                        if (this.worldSeeds.size() == 9) {
                            Log.warn("tmachine.printSeedsInConsole");
                        }
                    } else {
                        logger.info("Found world seed " + worldSeed);
                    }
                }

                if (this.shouldTerminate) {
                    return false;
                }
            }
        }

        dispSearchEnd();

        if (!this.worldSeeds.isEmpty()) return true;

        Log.error("tmachine.deleteBiomeInformation");
        this.dataStorage.biomeSeedData.getBaseSet().clear();

        Log.warn("tmachine.randomSeedSearch");
        for (long structureSeed : this.structureSeeds) {
            StructureSeed.toRandomWorldSeeds(structureSeed).forEach(s ->
                    Log.printSeed("tmachine.foundWorldSeed", s));

        }

        return true;
    }

    protected boolean pokeStructureReduce() {
        if (shouldTerminate) return false;
        if (!this.worldSeeds.isEmpty() || this.structureSeeds.size() < 2) return false;
        if (Config.get().getVersion().isOlderThan(MCVersion.v1_13)) return false;

        Set<Long> result = new HashSet<>();
        Log.debug("====================================");
        Log.warn("tmachine.reduceSeeds", this.structureSeeds.size());

        if (this.pillarSeeds != null) {
            structureSeeds.forEach(seed -> {
                if (this.pillarSeeds.contains((int) PillarSeed.fromStructureSeed(seed))) {
                    result.add(seed);
                }
            });
        }

        if (result.size() != 1) {
            this.dataStorage.baseSeedData.dump();
            this.dataStorage.baseSeedData.getBaseSet().removeIf(dataEntry -> !dataEntry.data.feature.getVersion().equals(Config.get().getVersion()));
            List<Feature.Data<?>> cache = this.structureFilters();
            ChunkRand rand = new ChunkRand();

            for (Long seed : this.structureSeeds) {
                boolean matches = true;

                for (Feature.Data<?> baseSeedDatum : cache) {
                    if (!baseSeedDatum.testStart(seed, rand)) {
                        matches = false;
                        break;
                    }
                }

                if (matches) {
                    result.add(seed);
                }
            }
        }

        if (!result.isEmpty() && this.structureSeeds.size() > result.size()) {
            if (result.size() < 10) {
                result.forEach(seed -> Log.printSeed("foundStructureSeed", seed));
            } else {
                Log.warn("tmachine.succeedReducing", result.size());
            }

            this.setStructureSeeds(result);
            return true;
        } else {
            Log.warn("tmachine.failedReducing");
        }
        return false;
    }

    /** Data every structure seed has to agree with. */
    private List<Feature.Data<?>> structureFilters() {
        List<Feature.Data<?>> cache = new ArrayList<>();

        for (DataStorage.Entry<Feature.Data<?>> entry : this.dataStorage.baseSeedData) {
            if (!(entry.data.feature instanceof Decorator) || entry.data.feature.getVersion().isOlderThan(MCVersion.v1_18)) {
                //todo remove this when libs are updated
                if (!(entry.data.feature instanceof PillagerOutpost)) {
                    cache.add(entry.data);
                }
            }
        }

        return cache;
    }

    /** Logs progress in steps of 10%. */
    private static ParallelSearch.Progress progressLog() {
        AtomicInteger lastStep = new AtomicInteger();

        return (done, total) -> {
            int step = (int) (done * 10 / total);
            int last = lastStep.get();
            if (step > last && lastStep.compareAndSet(last, step)) {
                Log.debug(Log.translate("tmachine.progress") + ": " + step * 10 + "%");
            }
        };
    }

    private void dispSearchEnd() {
        if (!this.worldSeeds.isEmpty()) {
            Log.warn("tmachine.worldSeedSearchFinished");
        } else {
            Log.error("finishedSearchNoResult");
        }
    }

    public long timeMachine(long partialWorldSeed, int pillarSeed) {
        long currentSeed = 0L;
        currentSeed |= (partialWorldSeed & 0xFFFF0000L) << 16;
        currentSeed |= (long) pillarSeed << 16;
        currentSeed |= partialWorldSeed & 0xFFFFL;

        currentSeed = this.inverseLCG.nextSeed(currentSeed);
        currentSeed ^= LCG.JAVA.multiplier;
        return currentSeed;
    }

    public enum Phase {
        BIOMES(null), STRUCURE_REDUCE(BIOMES), STRUCTURES(BIOMES), LIFTING(STRUCURE_REDUCE), PILLARS(STRUCTURES);

        private final Phase nextPhase;

        Phase(Phase nextPhase) {
            this.nextPhase = nextPhase;
        }

        public Phase nextPhase() {
            return this.nextPhase;
        }
    }

}
