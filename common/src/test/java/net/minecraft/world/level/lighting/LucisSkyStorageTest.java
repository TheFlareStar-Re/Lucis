package net.minecraft.world.level.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LucisSkyStorageTest {
    @Test
    void publishingWithoutEnablingLeavesDarkSkyPadding() {
        SkyLightSectionStorage storage = publish(false);
        assertFalse(storage.lightOnInSection(SectionPos.asLong(0, 0, 0)));
        assertEquals(15, storage.getLightValue(BlockPos.asLong(8, 8, 8)));
        assertEquals(0, storage.getLightValue(BlockPos.asLong(8, 16, 8)));
    }

    @Test
    void enablingBeforePublishingPreservesSkyAbovePublishedSections() {
        SkyLightSectionStorage storage = publish(true);
        assertTrue(storage.lightOnInSection(SectionPos.asLong(0, 0, 0)));
        assertEquals(15, storage.getLightValue(BlockPos.asLong(8, 8, 8)));
        assertEquals(15, storage.getLightValue(BlockPos.asLong(8, 16, 8)));
    }

    private static SkyLightSectionStorage publish(boolean enabled) {
        SkyLightSectionStorage storage = new SkyLightSectionStorage(new net.minecraft.world.level.chunk.LightChunkGetter() {
            @Override
            public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) {
                return null;
            }

            @Override
            public net.minecraft.world.level.BlockGetter getLevel() {
                return null;
            }
        });
        if (enabled) {
            storage.setLightEnabled(SectionPos.getZeroNode(0, 0), true);
        }
        long section = SectionPos.asLong(0, 0, 0);
        storage.queueSectionData(section, new DataLayer(15));
        storage.updateSectionStatus(section, false);
        storage.markNewInconsistencies(null);
        storage.swapSectionMap();
        return storage;
    }
}
