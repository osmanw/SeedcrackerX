package kaptainwutax.seedcrackerX.finder;

import kaptainwutax.seedcrackerX.finder.decorator.EndPillarsFinder;
import kaptainwutax.seedcrackerX.finder.decorator.ore.EmeraldOreFinder;
import kaptainwutax.seedcrackerX.finder.structure.*;
import kaptainwutax.seedcrackerX.util.HeightContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ReloadFinders {
    public Minecraft client = Minecraft.getInstance();

    public static void reloadHeight(int minY, int maxY) {
        List<BlockPos> chunkPositions = new ArrayList<>(16 * 16 * Math.max(0, maxY - minY));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    chunkPositions.add(new BlockPos(x, y, z));
                }
            }
        }
        Finder.CHUNK_POSITIONS = Collections.unmodifiableList(chunkPositions);
        Finder.heightContext = new HeightContext(minY, maxY);

        EmeraldOreFinder.reloadSearchPositions();
        EndPillarsFinder.BedrockMarkerFinder.reloadSearchPositions();
        AbstractTempleFinder.reloadSearchPositions();
        BuriedTreasureFinder.reloadSearchPositions();
        EndCityFinder.reloadSearchPositions();
        MonumentFinder.reloadSearchPositions();
        OutpostFinder.reloadSearchPositions();
        IglooFinder.reloadSearchPositions();
        TrialChambersFinder.reloadSearchPositions();
    }

    public void reload() {
        int renderdistance = client.options.renderDistance().get();

        int playerChunkX = (int) (Math.round(client.player.getX()) >> 4);
        int playerChunkZ = (int) (Math.round(client.player.getZ()) >> 4);
        for (int i = playerChunkX - renderdistance; i < playerChunkX + renderdistance; i++) {
            for (int j = playerChunkZ - renderdistance; j < playerChunkZ + renderdistance; j++) {
                FinderQueue.get().onChunkData(client.level, new ChunkPos(i, j));
            }
        }
    }
}
