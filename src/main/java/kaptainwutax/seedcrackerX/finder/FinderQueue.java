package kaptainwutax.seedcrackerX.finder;

import com.mojang.blaze3d.vertex.PoseStack;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.render.Cuboid;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class FinderQueue {

    private final static FinderQueue INSTANCE = new FinderQueue();
    private static final Logger log = LoggerFactory.getLogger(FinderQueue.class);
    private final ExecutorService SERVICE = Executors.newFixedThreadPool(5, Thread.ofPlatform().daemon().factory());
    /** Bounds the scan backlog, which otherwise grows without limit while flying fast. */
    private static final int MAX_PENDING_CHUNKS = 16384;
    private final Map<Long, Level> pendingChunks = new ConcurrentHashMap<>();
    private final AtomicBoolean warnedFull = new AtomicBoolean();

    private static final RenderStateDataKey<Set<Cuboid>> CUBOID_SET_KEY = RenderStateDataKey.create(() -> "SeedCrackerX cuboid set");

    public FinderControl finderControl = new FinderControl();

    private FinderQueue() {
        this.clear();
    }

    public static void registerEvents() {
        LevelRenderEvents.END_EXTRACTION.register(levelExtractionContext -> FinderQueue.get().extractCuboids(levelExtractionContext.levelState(), levelExtractionContext.camera()));
        LevelRenderEvents.END_MAIN.register(levelRenderContext -> FinderQueue.get().renderCuboids(levelRenderContext.submitNodeCollector(), levelRenderContext.poseStack(), levelRenderContext.levelState()));
    }

    public static FinderQueue get() {
        return INSTANCE;
    }

    public void onChunkData(Level world, ChunkPos chunkPos) {
        if (!Config.get().active) return;

        long key = chunkPos.pack();
        if (this.pendingChunks.size() >= MAX_PENDING_CHUNKS && !this.pendingChunks.containsKey(key)) {
            if (this.warnedFull.compareAndSet(false, true)) {
                log.warn("More than {} chunks are waiting to be scanned, skipping new ones. Use /seedcracker finder reload later.", MAX_PENDING_CHUNKS);
            }
            return;
        }
        // A chunk that is sent again while it still waits is scanned once.
        if (this.pendingChunks.put(key, world) == world) return;

        List<Finder.Type> types = getActiveFinderTypes();
        SERVICE.submit(() -> {
            this.pendingChunks.remove(key, world);
            // Skip chunks the player left behind, or a world they already left.
            if (world != Minecraft.getInstance().level || world.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z()) == null) {
                return;
            }

            for (Finder.Type type : types) {
                try {
                    List<Finder> finders = type.finderBuilder.build(world, chunkPos);

                    finders.forEach(finder -> {
                        if (finder.isValidDimension(world.dimensionType())) {
                            finder.findInChunk();
                            this.finderControl.addFinder(type, finder);
                        }
                    });
                } catch (Exception e) {
                    log.error("{} finder failed on chunk {}", type, chunkPos, e);
                }
            }
        });
    }

    private void extractCuboids(LevelRenderState state, Camera camera) {
        if (Config.get().render == Config.RenderType.OFF) {
            state.setData(CUBOID_SET_KEY, Collections.emptySet());
            return;
        }
        Set<Cuboid> cuboids = new HashSet<>();
        this.finderControl.getActiveFinders().forEach(finder -> {
            if (finder.shouldRender()) {
                finder.cuboids.forEach(cuboid -> cuboids.add(cuboid.offset(camera)));
            }
        });
        state.setData(CUBOID_SET_KEY, cuboids);
    }

    public void renderCuboids(SubmitNodeCollector submitter, PoseStack poseStack, LevelRenderState state) {
        Set<Cuboid> cuboids = state.getData(CUBOID_SET_KEY);
        if (cuboids == null) {
            return;
        }
        cuboids.forEach(cuboid -> cuboid.render(poseStack, submitter));
    }

    public List<Finder.Type> getActiveFinderTypes() {
        return Arrays.stream(Finder.Type.values())
                .filter(type -> type.enabled.get())
                .collect(Collectors.toList());
    }

    public void clear() {
        this.finderControl = new FinderControl();
    }
}
