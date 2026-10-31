package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeRegionSnapshot {
    private final List<SectionSnapshot> sections;

    private RuntimeRegionSnapshot(List<SectionSnapshot> sections) {
        this.sections = sections;
    }

    public static RuntimeRegionSnapshot capture(BlockGetter level, RegionBounds bounds,
                                                 Map<Long, LightChunk> sources, LightMaterialCache cache) {
        List<SectionSnapshot> snapshots = new ArrayList<>();
        IdentityHashMap<BlockState, Integer> materials = new IdentityHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Map.Entry<Long, LightChunk> source : sources.entrySet()) {
            ChunkAccess chunk = (ChunkAccess) source.getValue();
            int x = ChunkPos.getX(source.getKey()) << 4;
            int z = ChunkPos.getZ(source.getKey()) << 4;
            for (int i = 0; i < chunk.getSections().length; i++) {
                int y = (chunk.getMinSection() + i) << 4;
                if (y >= bounds.maxBuildY() || y + 16 <= bounds.minBuildY()) {
                    continue;
                }
                var states = chunk.getSections()[i].getStates();
                pos.set(x, y, z);
                RuntimeSectionSnapshot<BlockState> snapshot = RuntimeSectionSnapshot.capture(states,
                        state -> materials.computeIfAbsent(state, key -> key.useShapeForLightOcclusion()
                                ? -1 : cache.lookupLight(level, key, pos)),
                        (state, index) -> {
                            int dx = index & 15;
                            int dy = index >> 8;
                            int dz = (index >> 4) & 15;
                            pos.set(x + dx, y + dy, z + dz);
                            return cache.lookupLight(level, state, pos);
                        });
                snapshots.add(new SectionSnapshot(x, y, z, snapshot));
            }
        }
        return new RuntimeRegionSnapshot(snapshots);
    }

    public void populate(RegionLightData data) {
        data.beginFullPopulate();
        for (SectionSnapshot section : sections) {
            populateSection(data, section);
        }
    }

    private static void populateSection(RegionLightData data, SectionSnapshot section) {
        for (int dy = 0; dy < 16; dy++) {
            int y = section.y() + dy;
            if (y < data.bounds.minBuildY() || y >= data.bounds.maxBuildY()) {
                continue;
            }
            for (int dz = 0; dz < 16; dz++) {
                int row = data.index(section.x(), y, section.z() + dz);
                if (section.snapshot().uniform() >= 0) {
                    fillRow(data, row, section.snapshot().uniform());
                    continue;
                }
                for (int dx = 0; dx < 16; dx++) {
                    int material = section.snapshot().material(dx, dy, dz);
                    data.materials[row + dx] = (short) material;
                    data.opacity[row + dx] = LightMaterial.opacity(material);
                    data.emission[row + dx] = LightMaterial.emission(material);
                }
            }
        }
    }

    static void fillRow(RegionLightData data, int row, int material) {
        Arrays.fill(data.materials, row, row + 16, (short) material);
        Arrays.fill(data.opacity, row, row + 16, LightMaterial.opacity(material));
        Arrays.fill(data.emission, row, row + 16, LightMaterial.emission(material));
    }

    private record SectionSnapshot(int x, int y, int z, RuntimeSectionSnapshot<BlockState> snapshot) {
    }
}
