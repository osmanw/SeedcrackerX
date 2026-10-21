package kaptainwutax.seedcrackerX.finder.structure;

import kaptainwutax.seedcrackerX.finder.Finder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PieceFinder extends Finder {

    private final BoundingBox boundingBox;
    protected Map<BlockPos, BlockState> structure = new LinkedHashMap<>();
    protected List<BlockPos> searchPositions = new ArrayList<>();

    protected Direction facing;
    protected int width;
    protected int height;
    protected int depth;
    protected Mirror mirror;
    protected Rotation rotation;
    private boolean debug;

    public PieceFinder(Level world, ChunkPos chunkPos, Direction facing, Vec3i size) {
        super(world, chunkPos);

        this.setOrientation(facing);
        this.width = size.getX();
        this.height = size.getY();
        this.depth = size.getZ();

        if (this.facing.getAxis() == Direction.Axis.Z) {
            this.boundingBox = new BoundingBox(
                    0, 0, 0,
                    size.getX() - 1, size.getY() - 1, size.getZ() - 1
            );
        } else {
            this.boundingBox = new BoundingBox(
                    0, 0, 0,
                    size.getZ() - 1, size.getY() - 1, size.getX() - 1
            );
        }
    }

    public Vec3i getLayout() {
        if (this.facing.getAxis() != Direction.Axis.Z) {
            return new Vec3i(this.depth, this.height, this.width);
        }

        return new Vec3i(this.width, this.height, this.depth);
    }

    @Override
    public List<BlockPos> findInChunk() {
        List<BlockPos> result = new ArrayList<>();

        if (this.structure.isEmpty()) {
            return result;
        }

        //FOR DEBUGGING PIECES.
        if (this.debug) {
            Minecraft.getInstance().execute(() -> {
                int y = this.rotation.ordinal() * (this.height + 5) + this.mirror.ordinal() * (this.height + 5) * 2 + 120;

                if (this.chunkPos.x() % 2 == 0 && this.chunkPos.z() % 2 == 0) {
                    this.structure.forEach((pos, state) -> {
                        this.world.setBlock(this.chunkPos.getWorldPosition().offset(pos).offset(0, y, 0), state, 0);
                    });
                }
            });
        }

        if (!this.mayContainStructure()) {
            return result;
        }

        for (BlockPos center : this.searchPositions) {
            boolean found = true;

            for (Map.Entry<BlockPos, BlockState> entry : this.structure.entrySet()) {
                BlockPos pos = this.chunkPos.getWorldPosition().offset(center.offset(entry.getKey()));
                BlockState state = this.world.getBlockState(pos);

                //Blockstate may change when it gets placed in the world, that's why it's using the block here.
                if (entry.getValue() != null && !state.getBlock().equals(entry.getValue().getBlock())) {
                    found = false;
                    break;
                }
            }

            if (found) {
                result.add(this.chunkPos.getWorldPosition().offset(center));
            }
        }

        return result;
    }

    /**
     * False when some block of the piece is missing from the palettes of every chunk section the piece could
     * overlap. Then no search position can match, and the blocks themselves are never read.
     */
    private boolean mayContainStructure() {
        if (this.searchPositions.isEmpty()) return false;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos center : this.searchPositions) {
            minX = Math.min(minX, center.getX());
            minY = Math.min(minY, center.getY());
            minZ = Math.min(minZ, center.getZ());
            maxX = Math.max(maxX, center.getX());
            maxY = Math.max(maxY, center.getY());
            maxZ = Math.max(maxZ, center.getZ());
        }

        int offsetMinX = Integer.MAX_VALUE, offsetMinY = Integer.MAX_VALUE, offsetMinZ = Integer.MAX_VALUE;
        int offsetMaxX = Integer.MIN_VALUE, offsetMaxY = Integer.MIN_VALUE, offsetMaxZ = Integer.MIN_VALUE;
        Set<Block> requiredBlocks = new HashSet<>();
        for (Map.Entry<BlockPos, BlockState> entry : this.structure.entrySet()) {
            BlockPos offset = entry.getKey();
            offsetMinX = Math.min(offsetMinX, offset.getX());
            offsetMinY = Math.min(offsetMinY, offset.getY());
            offsetMinZ = Math.min(offsetMinZ, offset.getZ());
            offsetMaxX = Math.max(offsetMaxX, offset.getX());
            offsetMaxY = Math.max(offsetMaxY, offset.getY());
            offsetMaxZ = Math.max(offsetMaxZ, offset.getZ());
            // Air is in nearly every palette, so it can't rule anything out.
            if (entry.getValue() != null && !entry.getValue().isAir()) {
                requiredBlocks.add(entry.getValue().getBlock());
            }
        }

        BlockPos origin = this.chunkPos.getWorldPosition();
        int fromX = origin.getX() + minX + offsetMinX, toX = origin.getX() + maxX + offsetMaxX;
        int fromY = minY + offsetMinY, toY = maxY + offsetMaxY;
        int fromZ = origin.getZ() + minZ + offsetMinZ, toZ = origin.getZ() + maxZ + offsetMaxZ;

        List<LevelChunkSection> sections = new ArrayList<>();
        for (int chunkX = fromX >> 4; chunkX <= toX >> 4; chunkX++) {
            for (int chunkZ = fromZ >> 4; chunkZ <= toZ >> 4; chunkZ++) {
                ChunkAccess chunk = this.world.getChunk(chunkX, chunkZ);
                LevelChunkSection[] chunkSections = chunk.getSections();
                int fromIndex = Math.max(0, chunk.getSectionIndex(fromY));
                int toIndex = Math.min(chunkSections.length - 1, chunk.getSectionIndex(toY));
                for (int i = fromIndex; i <= toIndex; i++) {
                    sections.add(chunkSections[i]);
                }
            }
        }

        for (Block block : requiredBlocks) {
            boolean present = false;
            for (LevelChunkSection section : sections) {
                if (section.maybeHas(state -> state.is(block))) {
                    present = true;
                    break;
                }
            }
            if (!present) return false;
        }

        return true;
    }

    public void setOrientation(Direction facing) {
        this.facing = facing;

        if (facing == null) {
            this.rotation = Rotation.NONE;
            this.mirror = Mirror.NONE;
        } else {
            switch (facing) {
                case SOUTH:
                    this.mirror = Mirror.LEFT_RIGHT;
                    this.rotation = Rotation.NONE;
                    break;
                case WEST:
                    this.mirror = Mirror.LEFT_RIGHT;
                    this.rotation = Rotation.CLOCKWISE_90;
                    break;
                case EAST:
                    this.mirror = Mirror.NONE;
                    this.rotation = Rotation.CLOCKWISE_90;
                    break;
                default:
                    this.mirror = Mirror.NONE;
                    this.rotation = Rotation.NONE;
            }
        }

    }

    protected int applyXTransform(int x, int z) {
        if (this.facing == null) {
            return x;
        } else {
            return switch (this.facing) {
                case NORTH, SOUTH -> this.boundingBox.minX() + x;
                case WEST -> this.boundingBox.maxX() - z;
                case EAST -> this.boundingBox.minX() + z;
                default -> x;
            };
        }
    }

    protected int applyYTransform(int y) {
        return this.facing == null ? y : y + this.boundingBox.minY();
    }

    protected int applyZTransform(int x, int z) {
        if (this.facing == null) {
            return z;
        } else {
            return switch (this.facing) {
                case NORTH -> this.boundingBox.maxZ() - z;
                case SOUTH -> this.boundingBox.minZ() + z;
                case WEST, EAST -> this.boundingBox.minZ() + x;
                default -> z;
            };
        }
    }

    protected BlockState getBlockAt(int ox, int oy, int oz) {
        int x = this.applyXTransform(ox, oz);
        int y = this.applyYTransform(oy);
        int z = this.applyZTransform(ox, oz);
        BlockPos pos = new BlockPos(x, y, z);

        return !this.boundingBox.isInside(pos) ?
                Blocks.AIR.defaultBlockState() :
                this.structure.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    protected void fillWithOutline(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, BlockState outline, BlockState inside, boolean onlyReplaceAir) {
        for (int y = minY; y <= maxY; ++y) {
            for (int x = minX; x <= maxX; ++x) {
                for (int z = minZ; z <= maxZ; ++z) {
                    if (!onlyReplaceAir || !this.getBlockAt(x, y, z).isAir()) {
                        if (y != minY && y != maxY && x != minX && x != maxX && z != minZ && z != maxZ) {
                            this.addBlock(inside, x, y, z);
                        } else {
                            this.addBlock(outline, x, y, z);
                        }
                    }
                }
            }
        }

    }

    protected void addBlock(BlockState state, int x, int y, int z) {
        BlockPos pos = new BlockPos(
                this.applyXTransform(x, z),
                this.applyYTransform(y),
                this.applyZTransform(x, z)
        );

        if (this.boundingBox.isInside(pos)) {
            if (state == null) {
                this.structure.remove(pos);
                return;
            }

            if (this.mirror != Mirror.NONE) {
                state = state.mirror(this.mirror);
            }

            if (this.rotation != Rotation.NONE) {
                state = state.rotate(this.rotation);
            }


            this.structure.put(pos, state);
        }
    }

    @Override
    public boolean isValidDimension(DimensionType dimension) {
        return true;
    }

    public void setDebug() {
        this.debug = true;
    }

}
