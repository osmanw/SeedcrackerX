package kaptainwutax.seedcrackerX.gametest;

import com.mojang.datafixers.util.Pair;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.cracker.storage.DataStorage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates a world with a known seed, visits the liftable structures nearest to a few points like a player would,
 * and checks that the mod ends up with exactly that world seed.
 */
public class CrackWorldSeedGameTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX gametest");
    private static final long WORLD_SEED = 2026102117420316L;
    private static final List<ResourceKey<Structure>> STRUCTURES = List.of(BuiltinStructures.DESERT_PYRAMID,
            BuiltinStructures.JUNGLE_TEMPLE, BuiltinStructures.SWAMP_HUT, BuiltinStructures.IGLOO, BuiltinStructures.SHIPWRECK);
    private static final List<BlockPos> SEARCH_ORIGINS = List.of(BlockPos.ZERO, new BlockPos(3000, 0, 0), new BlockPos(0, 0, 3000));
    private static final int CRACK_TIMEOUT_TICKS = 20 * 300;

    @Override
    public void runTest(ClientGameTestContext context) {
        // Consistent settings create a flat world without structures.
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed(Long.toString(WORLD_SEED)))
                .create()) {
            world.getServer().runCommand("gamemode spectator @a");
            List<BlockPos> structures = world.getServer().computeOnServer(CrackWorldSeedGameTest::locateStructures);
            LOGGER.info("Visiting {} structures: {}", structures.size(), structures);
            if (structures.size() < 5) {
                throw new AssertionError("Only found " + structures.size() + " structures to visit");
            }

            long start = System.nanoTime();
            for (BlockPos pos : structures) {
                world.getServer().runCommand("tp @a " + pos.getX() + " 140 " + pos.getZ());
                world.getConnection().waitForChunksDownload();
                context.waitTicks(40);
            }

            try {
                context.waitFor(client -> SeedCracker.get().getDataStorage().getTimeMachine().worldSeeds.size() == 1, CRACK_TIMEOUT_TICKS);
            } catch (RuntimeException | AssertionError e) {
                throw new AssertionError("No world seed after " + CRACK_TIMEOUT_TICKS + " ticks: " + liftingBits() + " lifting bits, "
                        + SeedCracker.get().getDataStorage().getTimeMachine().structureSeeds.size() + " structure seeds", e);
            }

            Set<Long> worldSeeds = SeedCracker.get().getDataStorage().getTimeMachine().worldSeeds;
            LOGGER.info("Cracked {} in {} s with {} lifting bits", worldSeeds, (System.nanoTime() - start) / 1_000_000_000.0, liftingBits());
            if (!worldSeeds.equals(Set.of(WORLD_SEED))) {
                throw new AssertionError("Expected world seed " + WORLD_SEED + ", got " + worldSeeds);
            }
        }
    }

    private static double liftingBits() {
        DataStorage dataStorage = SeedCracker.get().getDataStorage();
        return dataStorage.getLiftingBits();
    }

    private static List<BlockPos> locateStructures(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Set<BlockPos> found = new LinkedHashSet<>();

        for (ResourceKey<Structure> key : STRUCTURES) {
            Holder<Structure> structure = server.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
            for (BlockPos origin : SEARCH_ORIGINS) {
                Pair<BlockPos, Holder<Structure>> nearest = level.getChunkSource().getGenerator()
                        .findNearestMapStructure(level, HolderSet.direct(structure), origin, 64, false);
                if (nearest != null) {
                    found.add(nearest.getFirst());
                }
            }
        }

        return new ArrayList<>(found);
    }
}
