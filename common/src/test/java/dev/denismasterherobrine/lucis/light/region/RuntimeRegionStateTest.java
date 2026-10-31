package dev.denismasterherobrine.lucis.light.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRegionStateTest {
    @Test
    void lateWorkerCannotRestoreInvalidatedSnapshot() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
        RuntimeRegionState state = new RuntimeRegionState(new RegionLightData(bounds));
        Object core = new Object();
        var sources = java.util.Map.of(0L, core);
        long before = state.epoch();
        state.invalidate();
        assertFalse(state.complete(before, core, sources));
        assertFalse(state.initializedForSources(sources));
        assertTrue(state.complete(state.epoch(), core, sources));
        assertTrue(state.initializedForSources(sources));
    }

    @Test
    void invalidatesWhenHaloChunkLoadsUnloadsOrReloads() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
        RuntimeRegionState state = new RuntimeRegionState(new RegionLightData(bounds));
        Object core = new Object();
        Object neighbor = new Object();
        var sources = java.util.Map.of(0L, core, 1L, neighbor);
        state.markInitialized(core);
        state.markSources(sources);
        assertTrue(state.initializedForSources(sources));
        assertFalse(state.initializedForSources(java.util.Map.of(0L, core)));
        assertFalse(state.initializedForSources(java.util.Map.of(0L, core, 1L, new Object())));
        state.invalidate();
        assertFalse(state.initializedForSources(sources));
    }

    @Test
    void invalidatesInitializationWhenChunkIdentityChanges() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
        RuntimeRegionState state = new RuntimeRegionState(new RegionLightData(bounds));
        Object firstChunk = new Object();
        Object reloadedChunk = new Object();

        assertFalse(state.initializedFor(firstChunk));

        state.markInitialized(firstChunk);
        assertTrue(state.initializedFor(firstChunk));
        assertFalse(state.initializedFor(reloadedChunk));

        state.markInitialized(reloadedChunk);
        assertTrue(state.initializedFor(reloadedChunk));
        assertFalse(state.initializedFor(firstChunk));
    }
}
