package dev.denismasterherobrine.lucis.light.runtime;

import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.engine.LucisRelighter;
import dev.denismasterherobrine.lucis.light.region.OwnedRegionCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionOwnerTable;
import dev.denismasterherobrine.lucis.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class LucisRuntimeManager implements AutoCloseable {
    private static final int MAX_RUNTIME_REGION_SUBMITS_PER_TICK = Integer.getInteger("lucis.runtime.maxSubmitsPerTick", 0);
    private static final int MAX_RUNTIME_PENDING_RECORDS = Integer.getInteger("lucis.runtime.maxPendingRecords", 131_072);
    private static final int FULL_RELIGHT_CHANGE_THRESHOLD = Integer.getInteger("lucis.runtime.fullRelightChangeThreshold", 2048);
    private static final long FULL_RELIGHT_COALESCE_NANOS = Long.getLong("lucis.runtime.fullRelightCoalesceNanos", 5_000_000_000L);
    private static final int RUNTIME_REGION_CHUNKS = Math.max(1, Math.min(Integer.getInteger("lucis.runtimeRegionChunks", 1), 16));

    private final RuntimeUpdateQueue updateQueue = new RuntimeUpdateQueue(MAX_RUNTIME_PENDING_RECORDS, FULL_RELIGHT_CHANGE_THRESHOLD);
    private final RegionOwnerTable ownerTable = new RegionOwnerTable();
    private final OwnedRegionCache regionCache = new OwnedRegionCache();
    private final LucisScheduler scheduler;
    private final boolean ownsScheduler;

    private final ConcurrentLinkedQueue<RuntimeCommit> commitQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> scheduledRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong ownerIds = new AtomicLong();
    private final HashMap<Long, RuntimeRegionBatch> drainedBatches = new HashMap<>();
    private final HashMap<Long, RuntimeRegionBatch> pendingBatchesByRegion = new HashMap<>();
    private final Object pendingBatchesLock = new Object();
    private final HashMap<Long, RuntimeRegionBatch> waitingForNeighbors = new HashMap<>();
    private final ConcurrentHashMap<Long, Long> fullRelightCoalesceUntil = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public LucisRuntimeManager() {
        this(new LucisScheduler(runtimeWorkerCount()), true);
    }

    public LucisRuntimeManager(LucisScheduler scheduler) {
        this(scheduler, false);
    }

    private LucisRuntimeManager(LucisScheduler scheduler, boolean ownsScheduler) {
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
    }

    public static LucisScheduler createScheduler() {
        return new LucisScheduler(runtimeWorkerCount());
    }

    public void invalidate(int x, int z) {
        for (long key : affectedRegionKeys(x >> 4, z >> 4, RUNTIME_REGION_CHUNKS)) {
            RuntimeRegionState state = regionCache.get(key);
            if (state != null) {
                state.invalidate();
                updateQueue.enqueueFullRelight(key, 1);
            }
        }
    }

    public boolean enqueue(BlockChangeRecord record) {
        return record != null && enqueue(record.x(), record.y(), record.z(), record.oldState(), record.newState());
    }

    public boolean enqueue(int x, int y, int z, BlockState oldState, BlockState newState) {
        if (closed) {
            return false;
        }
        boolean accepted = true;
        for (long regionKey : affectedRegionKeys(x >> 4, z >> 4, RUNTIME_REGION_CHUNKS)) {
            accepted &= enqueueRegion(regionKey, x, y, z, oldState, newState);
        }
        return accepted;
    }

    static List<Long> affectedRegionKeys(int chunkX, int chunkZ, int regionChunks) {
        List<Long> keys = new ArrayList<>(9);
        int minX = Math.floorDiv(chunkX - 1, regionChunks) * regionChunks;
        int maxX = Math.floorDiv(chunkX + 1, regionChunks) * regionChunks;
        int minZ = Math.floorDiv(chunkZ - 1, regionChunks) * regionChunks;
        int maxZ = Math.floorDiv(chunkZ + 1, regionChunks) * regionChunks;
        for (int z = minZ; z <= maxZ; z += regionChunks) {
            for (int x = minX; x <= maxX; x += regionChunks) {
                keys.add(RegionBounds.regionKey(x, z));
            }
        }
        return keys;
    }

    private boolean enqueueRegion(long regionKey, int x, int y, int z, BlockState oldState, BlockState newState) {
        if (isFullRelightCoalescing(regionKey)) {
            updateQueue.enqueueFullRelight(regionKey, 1);
            markFullRelightCoalescing(regionKey);
            return true;
        }
        boolean accepted = updateQueue.enqueue(regionKey, x, y, z, oldState, newState);
        if (accepted && updateQueue.hasFullRelight(regionKey)) {
            markFullRelightCoalescing(regionKey);
        }
        if (!accepted) {
            updateQueue.enqueueFullRelight(regionKey, 1);
        }
        return true;
    }

    public boolean enqueueFullRelight(long regionKey, long originalChangeCount) {
        if (closed) {
            return false;
        }
        updateQueue.enqueueFullRelight(regionKey, Math.max(1, originalChangeCount));
        markFullRelightCoalescing(regionKey);
        return true;
    }

    public boolean canAcceptMoreWork() {
        return !closed && updateQueue.hasCapacity();
    }

    public void tick(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LucisRelighter relighter,
                     int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            return;
        }
        scheduleQueuedRegions(getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
        flushCommits(lightEngine);
        if (scheduledRegions.isEmpty()) {
            regionCache.trimToSize(Math.max(1, LucisConfig.maxCachedRegions / 9));
        }
    }

    public boolean hasPendingWork() {
        return !updateQueue.isEmpty()
                || !commitQueue.isEmpty()
                || !scheduledRegions.isEmpty()
                || !pendingBatchesEmpty()
                || scheduler.hasPendingWork();
    }

    private void scheduleQueuedRegions(LightChunkGetter getter, LucisRelighter relighter,
                                       int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        resumeLoadedRegions(getter, regionChunks, haloChunks);
        if (updateQueue.isEmpty() && pendingBatchesEmpty()) {
            return;
        }

        drainedBatches.clear();
        int drained = updateQueue.drainTo(drainedBatches);
        LucisBenchmarkSupport.count("lucis.runtime.drain.records", drained);
        int scheduled = 0;
        int maxSubmits = runtimeSubmitBudget();
        ArrayList<ScheduledRegionBatch> selected = new ArrayList<>(Math.min(maxSubmits, drainedBatches.size() + 1));
        synchronized (pendingBatchesLock) {
            for (Map.Entry<Long, RuntimeRegionBatch> entry : drainedBatches.entrySet()) {
                pendingBatchesByRegion.merge(entry.getKey(), entry.getValue(), LucisRuntimeManager::mergeBatches);
            }
            drainedBatches.clear();
            LucisBenchmarkSupport.count("lucis.runtime.drain.regions", pendingBatchesByRegion.size());

            Iterator<Map.Entry<Long, RuntimeRegionBatch>> iterator = pendingBatchesByRegion.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, RuntimeRegionBatch> entry = iterator.next();
                if (scheduled >= maxSubmits) {
                    LucisBenchmarkSupport.count("lucis.runtime.requeued.batchLimit");
                    break;
                }
                if (scheduledRegions.contains(entry.getKey())) {
                    continue;
                }
                RuntimeRegionBatch batch = entry.getValue();
                iterator.remove();
                selected.add(new ScheduledRegionBatch(entry.getKey(), batch));
                scheduled++;
            }
        }

        for (ScheduledRegionBatch batch : selected) {
            scheduleRegion(batch.regionKey(), batch.batch(), getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
        }
    }

    private void resumeLoadedRegions(LightChunkGetter getter, int regionChunks, int haloChunks) {
        Iterator<Map.Entry<Long, RuntimeRegionBatch>> iterator = waitingForNeighbors.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, RuntimeRegionBatch> entry = iterator.next();
            long key = entry.getKey();
            ChunkPos anchor = new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (getter.getChunkForLighting(anchor.x, anchor.z) == null) {
                iterator.remove();
                continue;
            }
            RegionBounds bounds = RegionBounds.around(anchor, getter.getLevel(), regionChunks, haloChunks);
            Map<Long, LightChunk> sources = captureSourceChunks(getter, bounds);
            int sourceWidth = regionChunks + haloChunks * 2;
            if (sources.size() == sourceWidth * sourceWidth
                    && sources.values().stream().allMatch(chunk -> chunk instanceof net.minecraft.world.level.chunk.ChunkAccess access
                    && access.isLightCorrect())) {
                synchronized (pendingBatchesLock) {
                    pendingBatchesByRegion.merge(key, entry.getValue(), LucisRuntimeManager::mergeBatches);
                }
                iterator.remove();
            }
        }
    }

    private boolean pendingBatchesEmpty() {
        synchronized (pendingBatchesLock) {
            return pendingBatchesByRegion.isEmpty();
        }
    }

    private void scheduleRegion(long regionKey, RuntimeRegionBatch batch, LightChunkGetter getter,
                                LucisRelighter relighter, int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            requeue(regionKey, batch);
            return;
        }

        ChunkPos anchor = new ChunkPos(ChunkPos.getX(regionKey), ChunkPos.getZ(regionKey));
        if (dev.denismasterherobrine.lucis.compat.LucisCompat.isSablePlotChunk(getter, anchor.x, anchor.z)) {
            return;
        }
        LightChunk coreChunk = getter.getChunkForLighting(anchor.x, anchor.z);
        if (coreChunk == null) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.missingChunk");
            RuntimeRegionState previous = regionCache.get(regionKey);
            if (previous != null) {
                previous.invalidate();
            }
            return;
        }
        if (!scheduledRegions.add(regionKey)) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.alreadyScheduled");
            requeue(regionKey, batch);
            return;
        }

        long ownerId = ownerIds.incrementAndGet();
        if (!ownerTable.tryAcquire(regionKey, ownerId)) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.ownerBusy");
            scheduledRegions.remove(regionKey);
            requeue(regionKey, batch);
            return;
        }

        RegionBounds bounds = RegionBounds.around(anchor, getter.getLevel(), regionChunks, haloChunks);
        HashMap<Long, LightChunk> expectedChunks = captureExpectedChunks(getter, bounds);
        RuntimeRegionState ownedState = regionCache.getOrCreate(bounds);
        ownedState.touch();
        LucisBenchmarkSupport.count("lucis.runtime.jobs.runtime.submit");
        LucisBenchmarkSupport.count(batch.fullRelight() ? "lucis.runtime.jobs.fullRelight" : "lucis.runtime.jobs.incremental");
        LucisBenchmarkSupport.count("lucis.runtime.jobs.changeRecords", batch.queuedChangeCount());
        HashMap<Long, LightChunk> sources = captureSourceChunks(getter, bounds);
        int sourceWidth = regionChunks + haloChunks * 2;
        if (sources.size() != sourceWidth * sourceWidth
                || sources.values().stream().anyMatch(chunk -> !(chunk instanceof net.minecraft.world.level.chunk.ChunkAccess access)
                || !access.isLightCorrect())) {
            ownedState.invalidate();
            // 缺少邻区块时交还原版，避免把缺失区块当空气发布。
            for (BlockChangeRecord change : batch.changes()) {
                lightCheckFallback(getter, change);
            }
            waitingForNeighbors.merge(regionKey, RuntimeRegionBatch.fullRelight(batch.originalChangeCount()),
                    LucisRuntimeManager::mergeBatches);
            ownerTable.release(regionKey, ownerId);
            scheduledRegions.remove(regionKey);
            return;
        }
        waitingForNeighbors.remove(regionKey);
        LucisRelighter.PreparedRuntimeRegion prepared;
        try {
            prepared = relighter.prepareRuntimeRegion(getter, ownedState, coreChunk, batch, sources);
        } catch (Throwable throwable) {
            ownedState.invalidate();
            ownerTable.release(regionKey, ownerId);
            scheduledRegions.remove(regionKey);
            requeue(regionKey, RuntimeRegionBatch.fullRelight(batch.originalChangeCount()));
            throw throwable;
        }
        boolean submitted = scheduler.submit(new LucisJob(() -> {
            long jobStartedAt = LucisBenchmarkSupport.start();
            try {
                List<LucisRelightResult> results = relighter.relightPreparedRuntimeRegion(prepared, enableSky, enableBlock);
                if (closed) {
                    return;
                }
                if (ownedState.epoch() != prepared.epoch()) {
                    requeue(regionKey, RuntimeRegionBatch.fullRelight(batch.originalChangeCount()));
                    return;
                }
                LucisBenchmarkSupport.count("lucis.runtime.jobs.results", results.size());
                for (LucisRelightResult result : results) {
                    LucisBenchmarkSupport.count("lucis.runtime.jobs.sections", result.sections().size());
                    LightChunk expectedChunk = expectedChunks.get(ChunkPos.asLong(result.chunkPos().x, result.chunkPos().z));
                    if (expectedChunk == null) {
                        LucisBenchmarkSupport.count("lucis.runtime.commit.skippedMissingExpectedChunk");
                        continue;
                    }
                    commitQueue.add(new RuntimeCommit(result, expectedChunk, ownedState, prepared.epoch()));
                }
            } catch (Throwable throwable) {
                ownedState.invalidate();
                requeue(regionKey, RuntimeRegionBatch.fullRelight(batch.originalChangeCount()));
                throw throwable;
            } finally {
                LucisBenchmarkSupport.recordSince("lucis.stage.runtime.job.runtime", jobStartedAt);
                ownerTable.release(regionKey, ownerId);
                scheduledRegions.remove(regionKey);
            }
        }));
        if (!submitted) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.submitClosed");
            ownerTable.release(regionKey, ownerId);
            scheduledRegions.remove(regionKey);
            requeue(regionKey, batch);
        }
    }

    private void lightCheckFallback(LightChunkGetter getter, BlockChangeRecord change) {
        dev.denismasterherobrine.lucis.light.engine.LucisEngineController controller =
                dev.denismasterherobrine.lucis.light.engine.LucisServices.controller();
        controller.beginWorldgenWrite();
        try {
            ((net.minecraft.world.level.Level) getter.getLevel()).getLightEngine()
                    .checkBlock(new net.minecraft.core.BlockPos(change.x(), change.y(), change.z()));
        } finally {
            controller.endWorldgenWrite();
        }
    }

    private void requeue(long regionKey, RuntimeRegionBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        if (batch.fullRelight()) {
            updateQueue.enqueueFullRelight(regionKey, batch.originalChangeCount());
            markFullRelightCoalescing(regionKey);
        } else {
            if (updateQueue.enqueueAll(regionKey, batch.changes()) < batch.changes().size()) {
                updateQueue.enqueueFullRelight(regionKey, batch.originalChangeCount());
            }
        }
    }

    private HashMap<Long, LightChunk> captureSourceChunks(LightChunkGetter getter, RegionBounds bounds) {
        HashMap<Long, LightChunk> chunks = new HashMap<>();
        for (int z = bounds.minBlockZ() >> 4; z < bounds.maxBlockZExclusive() >> 4; z++) {
            for (int x = bounds.minBlockX() >> 4; x < bounds.maxBlockXExclusive() >> 4; x++) {
                LightChunk chunk = getter.getChunkForLighting(x, z);
                if (chunk != null) {
                    chunks.put(ChunkPos.asLong(x, z), chunk);
                }
            }
        }
        return chunks;
    }

    private HashMap<Long, LightChunk> captureExpectedChunks(LightChunkGetter getter, RegionBounds bounds) {
        int regionChunks = bounds.regionChunks();
        HashMap<Long, LightChunk> expectedChunks = new HashMap<>(regionChunks * regionChunks);
        int maxChunkX = bounds.originChunkX() + regionChunks;
        int maxChunkZ = bounds.originChunkZ() + regionChunks;
        for (int chunkZ = bounds.originChunkZ(); chunkZ < maxChunkZ; chunkZ++) {
            for (int chunkX = bounds.originChunkX(); chunkX < maxChunkX; chunkX++) {
                LightChunk chunk = getter.getChunkForLighting(chunkX, chunkZ);
                if (chunk != null) {
                    expectedChunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
                }
            }
        }
        return expectedChunks;
    }

    private boolean isFullRelightCoalescing(long regionKey) {
        Long until = fullRelightCoalesceUntil.get(regionKey);
        if (until == null) {
            return false;
        }
        if (System.nanoTime() <= until) {
            return true;
        }
        fullRelightCoalesceUntil.remove(regionKey, until);
        return false;
    }

    private void markFullRelightCoalescing(long regionKey) {
        fullRelightCoalesceUntil.put(regionKey, System.nanoTime() + FULL_RELIGHT_COALESCE_NANOS);
    }

    private static RuntimeRegionBatch mergeBatches(RuntimeRegionBatch existing, RuntimeRegionBatch incoming) {
        if (existing.fullRelight() || incoming.fullRelight()) {
            return RuntimeRegionBatch.fullRelight(existing.originalChangeCount() + incoming.originalChangeCount());
        }
        ArrayList<BlockChangeRecord> merged = new ArrayList<>(existing.queuedChangeCount() + incoming.queuedChangeCount());
        merged.addAll(existing.changes());
        merged.addAll(incoming.changes());
        return new RuntimeRegionBatch(merged, false, existing.originalChangeCount() + incoming.originalChangeCount());
    }

    private void flushCommits(ThreadedLevelLightEngine lightEngine) {
        LucisLightPublisher publisher = (LucisLightPublisher) lightEngine;
        boolean any = false;
        RuntimeCommit commit;
        while ((commit = commitQueue.poll()) != null) {
            LucisRelightResult result = commit.result();
            any = true;
            LucisBenchmarkSupport.count("lucis.runtime.commit.results");
            LucisBenchmarkSupport.count("lucis.runtime.commit.sections", result.sections().size());
            RuntimeCommit current = commit;
            publisher.lucis$publish(result, current.expectedChunk(),
                    () -> !closed && current.state().epoch() == current.epoch());
        }
        if (any) {
            lightEngine.tryScheduleUpdate();
        }
    }

    private long regionKey(int chunkX, int chunkZ, int regionChunks) {
        int originChunkX = Math.floorDiv(chunkX, regionChunks) * regionChunks;
        int originChunkZ = Math.floorDiv(chunkZ, regionChunks) * regionChunks;
        return RegionBounds.regionKey(originChunkX, originChunkZ);
    }

    private static int runtimeWorkerCount() {
        int configured = Integer.getInteger("lucis.runtimeWorkers", 0);
        if (configured > 0) {
            return configured;
        }
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(available / 2, 4));
    }

    private static int runtimeSubmitBudget() {
        int configured = MAX_RUNTIME_REGION_SUBMITS_PER_TICK;
        if (configured > 0) {
            return Math.max(1, Math.min(LucisConfig.maxBatchChunks, configured));
        }
        return Math.max(1, LucisConfig.maxBatchChunks);
    }

    @Override
    public void close() {
        closed = true;
        if (ownsScheduler) {
            scheduler.close();
        }
        updateQueue.clear();
        commitQueue.clear();
        synchronized (pendingBatchesLock) {
            pendingBatchesByRegion.clear();
        }
        scheduledRegions.clear();
        ownerTable.clear();
        regionCache.clear();
        fullRelightCoalesceUntil.clear();
    }

    private record ScheduledRegionBatch(long regionKey, RuntimeRegionBatch batch) {
    }

    private record RuntimeCommit(LucisRelightResult result, LightChunk expectedChunk, RuntimeRegionState state, long epoch) {
    }
}
