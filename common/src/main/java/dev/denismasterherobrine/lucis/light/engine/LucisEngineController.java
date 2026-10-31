package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.compat.LucisCompat;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucis.light.runtime.LucisRuntimeManager;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class LucisEngineController {
    private static final int RUNTIME_REGION_CHUNKS = Math.max(1, Math.min(Integer.getInteger("lucis.runtimeRegionChunks", 1), 16));
    private static final int RUNTIME_HALO_CHUNKS = 1;

    private final LightMaterialCache materialCache = new LightMaterialCache();
    private final LucisRelighter relighter = new LucisRelighter(materialCache, new LucisRegionExtractor(materialCache));
    private final Map<Level, LucisRuntimeManager> runtimeManagers = new IdentityHashMap<>();
    private final dev.denismasterherobrine.lucis.light.runtime.LucisScheduler runtimeScheduler = LucisRuntimeManager.createScheduler();
    private final ExecutorService worldgenWorkers = Executors.newFixedThreadPool(worldgenWorkerCount(), new LucisWorldgenThreadFactory());
    private final AtomicInteger pendingWorldgenTasks = new AtomicInteger();
    private final ThreadLocal<Integer> worldgenWriteDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<RuntimeBulkScope> runtimeBulkScope = new ThreadLocal<>();

    public boolean enabled() {
        return LucisConfig.enabled;
    }

    public boolean shouldHandleWorldgen(LightChunkGetter getter, ChunkAccess chunk) {
        return enabled() && LucisConfig.enableWorldgen && chunk != null && !isSablePlotChunk(getter, chunk.getPos());
    }

    public LucisRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        return relighter.relightChunk(getter, chunk, LucisConfig.enableSky, LucisConfig.enableBlock, 1, 1);
    }

    public CompletableFuture<LucisRelightResult> relightChunkAsync(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        ChunkPos chunkPos = chunk.getPos();
        pendingWorldgenTasks.incrementAndGet();
        RegionLightData data;
        try {
            long extractStartedAt = LucisBenchmarkSupport.start();
            data = relighter.extractChunkData(getter, chunk, 1, 1);
            LucisBenchmarkSupport.recordSince("lucis.stage.worldgen.extract", extractStartedAt);
        } catch (Throwable throwable) {
            pendingWorldgenTasks.decrementAndGet();
            throw throwable;
        }

        long submittedAt = LucisBenchmarkSupport.start();
        return CompletableFuture.supplyAsync(() -> {
            long startedAt = LucisBenchmarkSupport.start();
            if (startedAt != 0L && submittedAt != 0L) {
                LucisBenchmarkSupport.record("lucis.light_chunk.worker_wait", startedAt - submittedAt);
            }
            LucisRelightResult result = relighter.relightPreparedChunk(chunkPos, data,
                    LucisConfig.enableSky, LucisConfig.enableBlock);
            LucisBenchmarkSupport.recordSince("lucis.light_chunk.worker_compute", startedAt);
            return result;
        }, worldgenWorkers).whenComplete((result, throwable) -> pendingWorldgenTasks.decrementAndGet());
    }

    public LucisRelightResult relightAtBlock(LightChunkGetter getter, BlockPos pos) {
        if (!shouldHandleBlockChange(getter, pos)) {
            return new LucisRelightResult(new ChunkPos(pos), java.util.List.of());
        }

        LightChunk chunk = getter.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk instanceof ChunkAccess chunkAccess) {
            return relightChunk(getter, chunkAccess, true);
        }
        return new LucisRelightResult(new ChunkPos(pos), java.util.List.of());
    }

    public boolean shouldHandleBlockChange(BlockPos pos) {
        return enabled()
                && LucisConfig.enableRuntime
                && pos != null
                && !isWorldgenWriteSuppressed()
                && LucisConfig.enableSky
                && LucisConfig.enableBlock;
    }

    public boolean shouldHandleBlockChange(LightChunkGetter getter, BlockPos pos) {
        return getter.getLevel() instanceof Level level && shouldHandleBlockChange(level, pos)
                && !LucisCompat.isSablePlotChunk(getter,
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public boolean shouldHandleBlockChange(Level level, BlockPos pos) {
        return level != null && shouldHandleBlockChange(pos)
                && !LucisCompat.isSablePlotChunk(level,
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public boolean hasRelevantRuntimeMaterialChange(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!enabled() || !LucisConfig.enableRuntime || level == null || pos == null || oldState == newState) {
            return false;
        }
        int oldMaterial = materialCache.lookupLight(level, oldState, pos);
        int newMaterial = materialCache.lookupLight(level, newState, pos);
        return !LightMaterial.hasSameRuntimeProperties(oldMaterial, newMaterial);
    }

    private synchronized LucisRuntimeManager runtimeManager(Level level) {
        return runtimeManagers.computeIfAbsent(level, ignored -> new LucisRuntimeManager(runtimeScheduler));
    }

    public boolean enqueueLightCheck(LightChunkGetter getter, BlockPos pos) {
        if (!(getter.getLevel() instanceof Level level)) {
            return false;
        }
        if (!shouldHandleBlockChange(getter, pos) || !hasRuntimeNeighbors(getter, pos)) {
            invalidateRuntime(level, pos);
            return false;
        }
        LightChunk chunk = getter.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk) || !((ChunkAccess) chunk).isLightCorrect()) {
            invalidateRuntime(level, pos);
            return false;
        }
        return enqueueBlockChange(level, pos, null, chunk.getBlockState(pos));
    }

    private boolean hasRuntimeNeighbors(LightChunkGetter getter, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
            for (int x = chunkX - 1; x <= chunkX + 1; x++) {
                LightChunk chunk = getter.getChunkForLighting(x, z);
                if (!(chunk instanceof ChunkAccess access) || !access.isLightCorrect()) {
                    return false;
                }
            }
        }
        return true;
    }

    private synchronized void invalidateRuntime(Level level, BlockPos pos) {
        LucisRuntimeManager manager = runtimeManagers.get(level);
        if (manager != null) {
            manager.invalidate(pos.getX(), pos.getZ());
        }
    }

    public boolean enqueueBlockChange(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        RuntimeBulkScope bulkScope = runtimeBulkScope.get();
        if (bulkScope != null && bulkScope.boundsRegistered && enabled() && LucisConfig.enableRuntime) {
            return true;
        }
        if (!shouldHandleBlockChange(level, pos)) {
            return false;
        }
        if (bulkScope != null) {
            if (!bulkScope.boundsRegistered) {
                bulkScope.record(level, pos);
                LucisBenchmarkSupport.count("lucis.runtime.bulk.suppressed_block_change");
            }
            return true;
        }
        long startedAt = LucisBenchmarkSupport.start();
        if (!runtimeManager(level).enqueue(pos.getX(), pos.getY(), pos.getZ(), oldState, newState)) {
            LucisBenchmarkSupport.recordSince("lucis.enqueue_block_change", startedAt);
            return false;
        }
        LucisBenchmarkSupport.recordSince("lucis.enqueue_block_change", startedAt);
        return true;
    }

    public void beginRuntimeBulkWrite() {
        RuntimeBulkScope scope = runtimeBulkScope.get();
        if (scope == null) {
            runtimeBulkScope.set(new RuntimeBulkScope());
            return;
        }
        scope.depth++;
    }

    public void recordRuntimeBulkBounds(Level level, int minX, int minZ, int maxXExclusive, int maxZExclusive, long estimatedChanges) {
        RuntimeBulkScope scope = runtimeBulkScope.get();
        if (scope == null || level == null || !enabled() || !LucisConfig.enableRuntime) {
            return;
        }
        int minChunkX = Math.floorDiv(minX, 16) - RUNTIME_HALO_CHUNKS;
        int maxChunkX = Math.floorDiv(maxXExclusive - 1, 16) + RUNTIME_HALO_CHUNKS;
        int minChunkZ = Math.floorDiv(minZ, 16) - RUNTIME_HALO_CHUNKS;
        int maxChunkZ = Math.floorDiv(maxZExclusive - 1, 16) + RUNTIME_HALO_CHUNKS;
        long changesPerRegion = Math.max(1L, estimatedChanges / Math.max(1,
                regionCount(minChunkX, maxChunkX, minChunkZ, maxChunkZ)));
        int minRegionChunkX = Math.floorDiv(minChunkX, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        int maxRegionChunkX = Math.floorDiv(maxChunkX, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        int minRegionChunkZ = Math.floorDiv(minChunkZ, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        int maxRegionChunkZ = Math.floorDiv(maxChunkZ, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
        for (int regionChunkZ = minRegionChunkZ; regionChunkZ <= maxRegionChunkZ; regionChunkZ += RUNTIME_REGION_CHUNKS) {
            for (int regionChunkX = minRegionChunkX; regionChunkX <= maxRegionChunkX; regionChunkX += RUNTIME_REGION_CHUNKS) {
                if (!LucisCompat.isSablePlotChunk(level, regionChunkX, regionChunkZ)) {
                    scope.recordRegion(level, RegionBounds.regionKey(regionChunkX, regionChunkZ), changesPerRegion);
                }
            }
        }
        scope.boundsRegistered = true;
    }

    public boolean endRuntimeBulkWrite() {
        RuntimeBulkScope scope = runtimeBulkScope.get();
        if (scope == null) {
            return false;
        }
        if (--scope.depth > 0) {
            return true;
        }
        runtimeBulkScope.remove();
        boolean accepted = true;
        for (Map.Entry<Level, Map<Long, Long>> world : scope.regionChangeCounts.entrySet()) {
            LucisRuntimeManager manager = runtimeManager(world.getKey());
            for (Map.Entry<Long, Long> entry : world.getValue().entrySet()) {
                if (!manager.enqueueFullRelight(entry.getKey(), entry.getValue())) {
                    accepted = false;
                }
            }
        }
        LucisBenchmarkSupport.count("lucis.runtime.bulk.regions",
                scope.regionChangeCounts.values().stream().mapToInt(Map::size).sum());
        LucisBenchmarkSupport.count("lucis.runtime.bulk.estimated_changes", scope.originalChangeCount);
        if (scope.boundsRegistered) {
            LucisBenchmarkSupport.count("lucis.runtime.bulk.suppressed_block_change", scope.originalChangeCount);
        }
        return accepted && !scope.regionChangeCounts.isEmpty();
    }

    public void tickRuntime(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter) {
        if (!enabled() || !LucisConfig.enableRuntime) {
            return;
        }
        runtimeManager((Level) getter.getLevel()).tick(lightEngine, getter, relighter, RUNTIME_REGION_CHUNKS, RUNTIME_HALO_CHUNKS,
                LucisConfig.enableSky, LucisConfig.enableBlock);
    }

    public List<LucisRelightResult> relightRegion(LightChunkGetter getter, ChunkPos anchorChunk) {
        return relighter.relightRegion(getter, anchorChunk, LucisConfig.enableSky, LucisConfig.enableBlock,
                LucisConfig.regionChunks, LucisConfig.haloChunks);
    }

    public synchronized void shutdown() {
        runtimeManagers.values().forEach(LucisRuntimeManager::close);
        runtimeManagers.clear();
        runtimeScheduler.close();
        worldgenWorkers.shutdownNow();
    }

    public synchronized boolean hasPendingRuntimeWork() {
        return runtimeManagers.values().stream().anyMatch(LucisRuntimeManager::hasPendingWork);
    }

    public boolean hasPendingWorldgenWork() {
        return pendingWorldgenTasks.get() > 0;
    }

    public void beginWorldgenWrite() {
        worldgenWriteDepth.set(worldgenWriteDepth.get() + 1);
    }

    public void endWorldgenWrite() {
        int depth = worldgenWriteDepth.get() - 1;
        if (depth <= 0) {
            worldgenWriteDepth.remove();
        } else {
            worldgenWriteDepth.set(depth);
        }
    }

    private boolean isWorldgenWriteActive() {
        return worldgenWriteDepth.get() > 0;
    }

    private boolean isWorldgenWriteSuppressed() {
        return isWorldgenWriteActive();
    }

    private static int regionCount(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        int minRegionX = Math.floorDiv(minChunkX, RUNTIME_REGION_CHUNKS);
        int maxRegionX = Math.floorDiv(maxChunkX, RUNTIME_REGION_CHUNKS);
        int minRegionZ = Math.floorDiv(minChunkZ, RUNTIME_REGION_CHUNKS);
        int maxRegionZ = Math.floorDiv(maxChunkZ, RUNTIME_REGION_CHUNKS);
        return (maxRegionX - minRegionX + 1) * (maxRegionZ - minRegionZ + 1);
    }

    private static final class RuntimeBulkScope {
        private final Map<Level, Map<Long, Long>> regionChangeCounts = new IdentityHashMap<>();
        private int depth = 1;
        private long originalChangeCount;
        private boolean boundsRegistered;

        private void record(Level level, BlockPos pos) {
            int minChunkX = (pos.getX() >> 4) - RUNTIME_HALO_CHUNKS;
            int maxChunkX = (pos.getX() >> 4) + RUNTIME_HALO_CHUNKS;
            int minChunkZ = (pos.getZ() >> 4) - RUNTIME_HALO_CHUNKS;
            int maxChunkZ = (pos.getZ() >> 4) + RUNTIME_HALO_CHUNKS;
            for (int z = Math.floorDiv(minChunkZ, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
                 z <= maxChunkZ; z += RUNTIME_REGION_CHUNKS) {
                for (int x = Math.floorDiv(minChunkX, RUNTIME_REGION_CHUNKS) * RUNTIME_REGION_CHUNKS;
                     x <= maxChunkX; x += RUNTIME_REGION_CHUNKS) {
                    recordRegion(level, RegionBounds.regionKey(x, z), 1);
                }
            }
        }

        private void recordRegion(Level level, long regionKey, long changes) {
            long count = Math.max(1L, changes);
            regionChangeCounts.computeIfAbsent(level, ignored -> new HashMap<>()).merge(regionKey, count, Long::sum);
            originalChangeCount += count;
        }
    }

    private boolean isSablePlotChunk(LightChunkGetter getter, ChunkPos chunkPos) {
        return LucisCompat.isSablePlotChunk(getter, chunkPos);
    }

    private static int worldgenWorkerCount() {
        int available = Runtime.getRuntime().availableProcessors();
        int configured = Integer.getInteger("lucis.worldgenWorkers", 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Math.min(available - 1, 6));
    }

    private static final class LucisWorldgenThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lucis-worldgen-light-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
