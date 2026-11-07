package dev.denismasterherobrine.lucis.light.region;

import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class OwnedRegionCache {
    private final ConcurrentHashMap<Long, RuntimeRegionState> cache = new ConcurrentHashMap<>();
    private long arrayBytes;

    public RuntimeRegionState getOrCreate(RegionBounds bounds) {
        return getOrCreate(bounds, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    // 创建和淘汰只在服务器线程执行；后台线程仅释放引用。
    public RuntimeRegionState getOrCreate(RegionBounds bounds, int maxEntries, long maxBytes) {
        RuntimeRegionState existing = cache.get(bounds.coreRegionKey());
        if (existing != null && !existing.unloaded() && sameShape(existing.data().bounds, bounds)) {
            LucisBenchmarkSupport.count("lucis.runtime.cache.hit");
            existing.touch();
            return existing;
        }
        if (existing != null) {
            if (existing.inUse()) {
                return null;
            }
            remove(bounds.coreRegionKey(), existing);
            LucisBenchmarkSupport.count("lucis.runtime.cache.shapeChanged");
        } else {
            LucisBenchmarkSupport.count("lucis.runtime.cache.miss");
        }
        long required = arrayBytes(bounds);
        // 一个超大区域允许单独进入，但不会与其他区域叠加突破预算。
        long budget = Math.max(maxBytes, required);
        RegionLightData reusable = null;
        while (cache.size() >= Math.max(1, maxEntries) || arrayBytes > budget - required) {
            RuntimeRegionState evicted = removeOldestIdleEntry();
            if (evicted == null) {
                LucisBenchmarkSupport.count("lucis.runtime.cache.admissionDeferred");
                recordSize();
                return null;
            }
            // 只转交本次准入淘汰的数组，不另设缓存外的常驻池。
            if (reusable == null && sameShape(evicted.data().bounds, bounds)) {
                reusable = evicted.data();
            }
        }
        RuntimeRegionState state = new RuntimeRegionState(new RegionLightData(bounds, reusable));
        cache.put(bounds.coreRegionKey(), state);
        arrayBytes += required;
        if (reusable == null) {
            LucisBenchmarkSupport.count("lucis.runtime.cache.allocatedBytes", required);
        } else {
            LucisBenchmarkSupport.count("lucis.runtime.cache.reused");
            LucisBenchmarkSupport.count("lucis.runtime.cache.reusedBytes", required);
        }
        recordSize();
        return state;
    }

    public RuntimeRegionState get(long regionKey) {
        return cache.get(regionKey);
    }

    public RuntimeRegionState getInitialized(long regionKey) {
        RuntimeRegionState state = cache.get(regionKey);
        if (state != null) {
            state.touch();
        }
        return state != null && state.initialized() ? state : null;
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        for (Map.Entry<Long, RuntimeRegionState> entry : cache.entrySet()) {
            RuntimeRegionState state = entry.getValue();
            RegionBounds bounds = state.data().bounds;
            if (chunkX >= (bounds.minBlockX() >> 4) && chunkX < (bounds.maxBlockXExclusive() >> 4)
                    && chunkZ >= (bounds.minBlockZ() >> 4) && chunkZ < (bounds.maxBlockZExclusive() >> 4)) {
                // 包括 halo 来源；在途任务保留数组所有权，但不能再提交旧结果。
                state.unload();
                if (!state.inUse()) {
                    remove(entry.getKey(), state);
                }
            }
        }
        recordSize();
    }

    public void trimToSize(int maxEntries) {
        trimToBudget(maxEntries, Long.MAX_VALUE);
    }

    public void trimToBudget(int maxEntries, long maxBytes) {
        for (Map.Entry<Long, RuntimeRegionState> entry : cache.entrySet()) {
            if (entry.getValue().unloaded() && !entry.getValue().inUse()) {
                remove(entry.getKey(), entry.getValue());
            }
        }
        while (cache.size() > Math.max(1, maxEntries) || (cache.size() > 1 && arrayBytes > maxBytes)) {
            if (removeOldestIdleEntry() == null) {
                break;
            }
        }
        recordSize();
    }

    private RuntimeRegionState removeOldestIdleEntry() {
        long oldestKey = 0L;
        long oldestAccess = Long.MAX_VALUE;
        RuntimeRegionState oldest = null;
        for (Map.Entry<Long, RuntimeRegionState> entry : cache.entrySet()) {
            RuntimeRegionState state = entry.getValue();
            if (!state.inUse() && state.lastAccessNanos() < oldestAccess) {
                oldestKey = entry.getKey();
                oldestAccess = state.lastAccessNanos();
                oldest = state;
            }
        }
        if (oldest == null) {
            return null;
        }
        remove(oldestKey, oldest);
        LucisBenchmarkSupport.count("lucis.runtime.cache.evicted");
        return oldest;
    }

    private void remove(long key, RuntimeRegionState state) {
        if (cache.remove(key, state)) {
            state.invalidate();
            arrayBytes -= arrayBytes(state.data().bounds);
        }
    }

    private void recordSize() {
        LucisBenchmarkSupport.gauge("lucis.runtime.cache.lastWorldEntries", cache.size());
        LucisBenchmarkSupport.gauge("lucis.runtime.cache.lastWorldArrayBytes", arrayBytes);
        LucisBenchmarkSupport.highWater("lucis.runtime.cache.worldEntriesHighWater", cache.size());
        LucisBenchmarkSupport.highWater("lucis.runtime.cache.worldArrayBytesHighWater", arrayBytes);
    }

    public int size() {
        return cache.size();
    }

    public long arrayBytes() {
        return arrayBytes;
    }

    public void clear() {
        cache.values().forEach(RuntimeRegionState::invalidate);
        cache.clear();
        arrayBytes = 0;
        recordSize();
    }

    private static long arrayBytes(RegionBounds bounds) {
        return 6L * bounds.volume();
    }

    private boolean sameShape(RegionBounds left, RegionBounds right) {
        return left.widthBlocks() == right.widthBlocks()
                && left.depthBlocks() == right.depthBlocks()
                && left.minBuildY() == right.minBuildY()
                && left.maxBuildY() == right.maxBuildY()
                && left.regionChunks() == right.regionChunks()
                && left.haloChunks() == right.haloChunks();
    }
}
