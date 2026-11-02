package dev.denismasterherobrine.lucis.light.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnedRegionCacheTest {
    @Test
    void evictsIdleRegionsWhileAnotherWorkerRemainsActive() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RuntimeRegionState active = cache.getOrCreate(bounds(0));
        active.retain();
        for (int x = 1; x < 100; x++) {
            assertNotNull(cache.getOrCreate(bounds(x), 3, Long.MAX_VALUE));
            assertTrue(cache.size() <= 3);
            assertSame(active, cache.get(0));
        }
        active.release();
    }

    @Test
    void pendingPublicationPinsArraysAfterWorkerFinishes() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RuntimeRegionState state = cache.getOrCreate(bounds(0));
        state.retain();
        state.retain();
        state.release();
        assertNull(cache.getOrCreate(bounds(1), 1, Long.MAX_VALUE));
        assertSame(state, cache.get(0));
        state.release();
        assertNotNull(cache.getOrCreate(bounds(1), 1, Long.MAX_VALUE));
        assertNull(cache.get(0));
        assertEquals(1, state.epoch());
    }

    @Test
    void enforcesByteBudgetAndKeepsCacheHits() {
        OwnedRegionCache cache = new OwnedRegionCache();
        long bytes = 6L * bounds(0).volume();
        RuntimeRegionState first = cache.getOrCreate(bounds(0), 128, 2 * bytes);
        assertSame(first, cache.getOrCreate(bounds(0), 128, 2 * bytes));
        cache.getOrCreate(bounds(1), 128, 2 * bytes);
        cache.getOrCreate(bounds(2), 128, 2 * bytes);
        assertEquals(2, cache.size());
        assertEquals(2 * bytes, cache.arrayBytes());
    }

    @Test
    void shapeReplacementWaitsForPublicationAndClearInvalidatesResults() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RuntimeRegionState original = cache.getOrCreate(bounds(0));
        original.retain();
        RegionBounds taller = new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 32, 0, 2, 32, 256, 8192);
        assertNull(cache.getOrCreate(taller, 10, Long.MAX_VALUE));
        original.release();
        RuntimeRegionState replacement = cache.getOrCreate(taller, 10, Long.MAX_VALUE);
        assertNotSame(original, replacement);
        assertEquals(1, original.epoch());
        replacement.retain();
        cache.clear();
        assertEquals(1, replacement.epoch());
        replacement.release();
        assertEquals(0, cache.arrayBytes());
    }

    private static RegionBounds bounds(int chunkX) {
        return new RegionBounds(chunkX, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
    }
}
