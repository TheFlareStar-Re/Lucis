package dev.denismasterherobrine.lucis.light.region;

import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnloadedChunkRetentionTest {
    private static RegionBounds bounds(int x, int halo) {
        int width = (1 + 2 * halo) * 16;
        return new RegionBounds(x, 0, 1, halo, width, width,
                0, 16, 0, 1, 16, width * width, width * width * 16);
    }

    @Test
    void cacheMetadataOnlyKeepsWeakIdentitiesAndRejectsClearedSources() throws Exception {
        RuntimeRegionState state = new RuntimeRegionState(new RegionLightData(bounds(0, 0)));
        Object core = new Object();
        Object halo = new Object();
        Map<Long, Object> sources = Map.of(0L, core, 1L, halo);
        assertTrue(state.complete(state.epoch(), core, sources));
        assertTrue(state.initializedFor(core));
        assertTrue(state.initializedForSources(sources));

        Field identityField = RuntimeRegionState.class.getDeclaredField("sourceIdentity");
        identityField.setAccessible(true);
        WeakReference<?> identity = assertInstanceOf(WeakReference.class, identityField.get(state));
        Field sourcesField = RuntimeRegionState.class.getDeclaredField("sources");
        sourcesField.setAccessible(true);
        Map<?, ?> identities = (Map<?, ?>) sourcesField.get(state);
        for (Object value : identities.values()) assertInstanceOf(WeakReference.class, value);
        ((WeakReference<?>) identities.get(1L)).clear();
        assertFalse(state.initializedForSources(sources));
        identity.clear();
        assertFalse(state.initializedFor(core));
        assertFalse(state.initializedFor(null));
        state.invalidate();
        assertNull(identityField.get(state));
        assertTrue(((Map<?, ?>) sourcesField.get(state)).isEmpty());
        Reference.reachabilityFence(core);
        Reference.reachabilityFence(halo);
    }

    @Test
    void haloUnloadRemovesOnlyIntersectingIdleRegionsAndBalancesBytes() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RuntimeRegionState affected = cache.getOrCreate(bounds(-2, 1));
        RuntimeRegionState unaffected = cache.getOrCreate(bounds(10, 1));
        cache.onChunkUnloaded(-3, -1);
        assertNull(cache.get(bounds(-2, 1).coreRegionKey()));
        assertTrue(affected.unloaded());
        assertSame(unaffected, cache.get(bounds(10, 1).coreRegionKey()));
        assertEquals(6L * bounds(10, 1).volume(), cache.arrayBytes());
        cache.onChunkUnloaded(-3, -1);
        assertEquals(1, cache.size());
        // 最大边界是排他的，不能误删相邻但不重叠的区域。
        cache.onChunkUnloaded(12, 0);
        assertEquals(1, cache.size());
    }

    @Test
    void unloadedInFlightRegionCannotPublishOrReuseArraysUntilReleased() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RuntimeRegionState state = cache.getOrCreate(bounds(0, 1));
        Object chunk = new Object();
        long epoch = state.epoch();
        state.complete(epoch, chunk, Map.of(0L, chunk));
        state.retain();
        cache.onChunkUnloaded(1, 0);
        assertTrue(state.inUse());
        assertFalse(state.initializedFor(chunk));
        assertFalse(state.complete(epoch, chunk, Map.of(0L, chunk)));
        assertFalse(state.complete(state.epoch(), chunk, Map.of(0L, chunk)));
        assertNull(cache.getOrCreate(bounds(0, 1), 128, Long.MAX_VALUE));
        cache.trimToBudget(128, Long.MAX_VALUE);
        assertEquals(1, cache.size());
        state.release();
        cache.trimToBudget(128, Long.MAX_VALUE);
        assertEquals(0, cache.size());
        assertEquals(0, cache.arrayBytes());
        RuntimeRegionState reloaded = cache.getOrCreate(bounds(0, 1));
        assertNotSame(state, reloaded);
        assertFalse(reloaded.initialized());
        assertTrue(reloaded.complete(reloaded.epoch(), chunk, Map.of(0L, chunk)));
        Reference.reachabilityFence(chunk);
    }
}
