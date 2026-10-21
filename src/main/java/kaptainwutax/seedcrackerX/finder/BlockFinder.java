package kaptainwutax.seedcrackerX.finder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class BlockFinder extends Finder {

    private final Set<BlockState> targetBlockStates = new HashSet<>();
    protected List<BlockPos> searchPositions = new ArrayList<>();

    public BlockFinder(Level world, ChunkPos chunkPos, Block block) {
        super(world, chunkPos);
        this.targetBlockStates.addAll(block.getStateDefinition().getPossibleStates());
    }

    public BlockFinder(Level world, ChunkPos chunkPos, BlockState... blockStates) {
        super(world, chunkPos);
        this.targetBlockStates.addAll(Arrays.stream(blockStates).toList());
    }

    @Override
    public List<BlockPos> findInChunk() {
        List<BlockPos> result = new ArrayList<>();
        ChunkAccess chunk = this.world.getChunk(this.chunkPos.getWorldPosition());

        // A section whose palette lacks every target state can't contain one, so its blocks are never read.
        LevelChunkSection[] sections = chunk.getSections();
        boolean[] mayContainTarget = new boolean[sections.length];
        boolean anySection = false;
        for (int i = 0; i < sections.length; i++) {
            mayContainTarget[i] = sections[i].maybeHas(this.targetBlockStates::contains);
            anySection |= mayContainTarget[i];
        }
        if (!anySection) return result;

        for (BlockPos blockPos : this.searchPositions) {
            int sectionIndex = chunk.getSectionIndex(blockPos.getY());
            if (sectionIndex < 0 || sectionIndex >= sections.length || !mayContainTarget[sectionIndex]) continue;

            BlockState currentState = chunk.getBlockState(blockPos);

            if (this.targetBlockStates.contains(currentState)) {
                result.add(this.chunkPos.getWorldPosition().offset(blockPos));
            }
        }

        return result;
    }

}
