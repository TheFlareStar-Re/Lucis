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
        if (existing != null && sameShape(existing.data().bounds, bounds)) {
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
        while (cache.size() >= Math.max(1, maxEntries) || arrayBytes > budget - required) {
            if (!removeOldestIdleEntry()) {
                LucisBenchmarkSupport.count("lucis.runtime.cache.admissionDeferred");
                return null;
            }
        }
        RuntimeRegionState state = new RuntimeRegionState(new RegionLightData(bounds));
        cache.put(bounds.coreRegionKey(), state);
        arrayBytes += required;
        LucisBenchmarkSupport.count("lucis.runtime.cache.allocatedBytes", required);
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

    public void trimToSize(int maxEntries) {
        trimToBudget(maxEntries, Long.MAX_VALUE);
    }

    public void trimToBudget(int maxEntries, long maxBytes) {
        while (cache.size() > Math.max(1, maxEntries) || (cache.size() > 1 && arrayBytes > maxBytes)) {
            if (!removeOldestIdleEntry()) {
                break;
            }
        }
        recordSize();
    }

    private boolean removeOldestIdleEntry() {
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
            return false;
        }
        remove(oldestKey, oldest);
        LucisBenchmarkSupport.count("lucis.runtime.cache.evicted");
        return true;
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
