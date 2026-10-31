package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChangeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LucisBlockLightEngineTest {
    @Test
    void haloTorchLightsCoreAcrossChunkEdgeAndRemovalClearsIt() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 1, 48, 48,
                0, 16, 0, 1, 16, 2304, 36864);
        RegionLightData data = new RegionLightData(bounds);
        int torch = data.index(-1, 8, 8);
        int insideCore = data.index(0, 8, 8);
        data.emission[torch] = 14;
        LucisBlockLightEngine engine = new LucisBlockLightEngine();
        engine.compute(data);
        assertEquals(13, data.blockLight[insideCore] & 0xF);
        assertEquals(1, data.blockLight[data.index(12, 8, 8)] & 0xF);

        data.emission[torch] = 0;
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(1);
        changes.addLight(torch, LightMaterial.packLight(0, 14), LightMaterial.packLight(0, 0));
        engine.applyRuntimeChanges(data, changes);
        assertEquals(0, data.blockLight[insideCore] & 0xF);
        assertEquals(0, data.blockLight[data.index(12, 8, 8)] & 0xF);
    }

    @Test
    void fastBatchPropagatesTorchLightBeyondImmediateNeighbors() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
        RegionLightData data = new RegionLightData(bounds);
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(2);
        changes.addLight(data.localIndex(8, 8, 8), LightMaterial.packLight(0, 0), LightMaterial.packLight(0, 14));
        changes.addLight(data.localIndex(2, 2, 2), LightMaterial.packLight(0, 0), LightMaterial.packLight(0, 14));

        new LucisBlockLightEngine().applyRuntimeChangesFast(data, changes);

        assertEquals(14, data.blockLight[data.localIndex(8, 8, 8)] & 0xF);
        assertEquals(13, data.blockLight[data.localIndex(9, 8, 8)] & 0xF);
        assertEquals(9, data.blockLight[data.localIndex(13, 8, 8)] & 0xF);
    }
}
