package dev.denismasterherobrine.lucis.light.region;

import dev.denismasterherobrine.lucis.light.engine.LucisBlockLightEngine;
import dev.denismasterherobrine.lucis.light.engine.LucisSkyLightEngine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

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
        RuntimeRegionState replacement = cache.getOrCreate(bounds(1), 1, Long.MAX_VALUE);
        assertNotNull(replacement);
        assertSame(state.data().blockLight, replacement.data().blockLight);
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

    @Test
    void transfersArraysButResetsCoordinatesContentsAndState() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RuntimeRegionState original = cache.getOrCreate(bounds(0));
        Object source = new Object();
        long epoch = original.epoch();
        original.complete(epoch, source, Map.of(0L, source));
        RegionLightData old = original.data();
        Arrays.fill(old.opacity, (byte) 15);
        Arrays.fill(old.emission, (byte) 14);
        Arrays.fill(old.materials, (short) 123);
        Arrays.fill(old.blockLight, (byte) 13);
        Arrays.fill(old.skyLight, (byte) 12);
        old.markCoreBlockSectionsDirty();
        old.markCoreSkySectionsDirty();

        RuntimeRegionState replacement = cache.getOrCreate(bounds(-7), 1, Long.MAX_VALUE);
        RegionLightData data = replacement.data();
        assertNotSame(original, replacement);
        assertNotSame(old, data);
        assertSame(old.opacity, data.opacity);
        assertSame(old.emission, data.emission);
        assertSame(old.materials, data.materials);
        assertSame(old.blockLight, data.blockLight);
        assertSame(old.skyLight, data.skyLight);
        assertEquals(bounds(-7), data.bounds);
        assertEquals(0, data.index(-112, 0, 0));
        assertEquals(-7, data.sectionPosFromLinear(0).x());
        assertFalse(replacement.initializedFor(source));
        assertFalse(replacement.initializedForSources(Map.of(0L, source)));
        assertFalse(replacement.inUse());
        assertEquals(0, replacement.epoch());
        assertFalse(original.complete(epoch, source, Map.of(0L, source)));
        assertArrayEquals(new byte[data.bounds.volume()], data.opacity);
        assertArrayEquals(new byte[data.bounds.volume()], data.emission);
        assertArrayEquals(new short[data.bounds.volume()], data.materials);
        assertArrayEquals(new byte[data.bounds.volume()], data.blockLight);
        assertArrayEquals(new byte[data.bounds.volume()], data.skyLight);
        assertTrue(data.dirtyBlockSections.isEmpty());
        assertTrue(data.dirtySkySections.isEmpty());
        assertNotSame(old.dirtyBlockSections, data.dirtyBlockSections);
        assertNotSame(old.dirtySkySections, data.dirtySkySections);
    }

    @Test
    void repeatedChurnKeepsOnlyBudgetedArraySets() {
        OwnedRegionCache cache = new OwnedRegionCache();
        Set<Object> arrays = Collections.newSetFromMap(new IdentityHashMap<>());
        long bytes = 6L * bounds(0).volume();
        for (int x = 0; x < 1000; x++) {
            RegionLightData data = cache.getOrCreate(bounds(x), 128, 3 * bytes).data();
            Collections.addAll(arrays, data.opacity, data.emission, data.materials, data.blockLight, data.skyLight);
            assertTrue(cache.arrayBytes() <= 3 * bytes);
        }
        assertEquals(15, arrays.size());
        assertEquals(3, cache.size());
        cache.clear();
        RegionLightData afterClear = cache.getOrCreate(bounds(0), 128, 3 * bytes).data();
        assertFalse(arrays.contains(afterClear.blockLight));
        assertEquals(bytes, cache.arrayBytes());
    }

    @Test
    void incompatibleShapeDoesNotBorrowEqualCapacityArrays() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RuntimeRegionState original = cache.getOrCreate(bounds(0));
        RegionBounds shiftedHeight = new RegionBounds(1, 0, 1, 0, 16, 16,
                -16, 0, -1, 1, 16, 256, 4096);
        RuntimeRegionState replacement = cache.getOrCreate(shiftedHeight, 1, Long.MAX_VALUE);
        assertNotSame(original.data().opacity, replacement.data().opacity);
        assertNotSame(original.data().materials, replacement.data().materials);
        assertNotSame(original.data().blockLight, replacement.data().blockLight);
        assertEquals(6L * shiftedHeight.volume(), cache.arrayBytes());
    }

    @Test
    void reusedRegionComputesSameLightAsFreshRegion() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RegionLightData old = cache.getOrCreate(bounds(0)).data();
        old.emission[old.localIndex(1, 8, 8)] = 14;
        LucisBlockLightEngine block = new LucisBlockLightEngine();
        LucisSkyLightEngine sky = new LucisSkyLightEngine();
        block.compute(old);
        sky.compute(old);

        RegionLightData reused = cache.getOrCreate(bounds(5), 1, Long.MAX_VALUE).data();
        RegionLightData fresh = new RegionLightData(bounds(5));
        for (RegionLightData data : new RegionLightData[]{reused, fresh}) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    data.opacity[data.localIndex(x, 12, z)] = 15;
                }
            }
            data.emission[data.localIndex(12, 3, 8)] = 10;
            block.compute(data);
            sky.compute(data);
        }
        assertArrayEquals(fresh.blockLight, reused.blockLight);
        assertArrayEquals(fresh.skyLight, reused.skyLight);
        assertEquals(fresh.dirtyBlockSections, reused.dirtyBlockSections);
        assertEquals(fresh.dirtySkySections, reused.dirtySkySections);
    }

    private static RegionBounds bounds(int chunkX) {
        return new RegionBounds(chunkX, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
    }
}
