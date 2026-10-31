package dev.denismasterherobrine.lucis.light.engine;

import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

final class RuntimeSectionSnapshot<T> {
    private final PalettedContainer<T> states;
    private final IdentityHashMap<T, Integer> materials;
    private final short[] dynamic;
    private final int uniform;

    private RuntimeSectionSnapshot(PalettedContainer<T> states, IdentityHashMap<T, Integer> materials,
                                   short[] dynamic, int uniform) {
        this.states = states;
        this.materials = materials;
        this.dynamic = dynamic;
        this.uniform = uniform;
    }

    static <T> RuntimeSectionSnapshot<T> capture(PalettedContainer<T> liveStates,
                                                ToIntFunction<T> staticMaterial,
                                                DynamicMaterial<T> dynamicMaterial) {
        PalettedContainer<T> states = liveStates.copy();
        IdentityHashMap<T, Integer> materials = new IdentityHashMap<>();
        Consumer<T> collect = state -> materials.put(state, staticMaterial.applyAsInt(state));
        // 局部调色板仅访问条目；全局调色板才枚举实际使用的状态。
        if (states.maybeHas(state -> {
            collect.accept(state);
            return false;
        })) {
            for (int index = 0; index < 4096; index++) {
                T state = states.get(index & 15, index >> 8, (index >> 4) & 15);
                if (!materials.containsKey(state)) {
                    collect.accept(state);
                }
            }
        }
        int uniform = materials.size() == 1 ? materials.values().iterator().next() : -1;
        short[] dynamic = null;
        if (materials.containsValue(-1)) {
            dynamic = new short[4096];
            for (int index = 0; index < 4096; index++) {
                T state = states.get(index & 15, index >> 8, (index >> 4) & 15);
                if (materials.get(state) == -1) {
                    dynamic[index] = (short) dynamicMaterial.material(state, index);
                }
            }
        }
        return new RuntimeSectionSnapshot<>(uniform < 0 ? states : null, materials, dynamic, uniform);
    }

    @FunctionalInterface
    interface DynamicMaterial<T> {
        int material(T state, int index);
    }

    int uniform() {
        return uniform;
    }

    int material(int x, int y, int z) {
        if (uniform >= 0) {
            return uniform;
        }
        int material = materials.get(states.get(x, y, z));
        return material < 0 ? dynamic[(y << 8) | (z << 4) | x] & 0xFFFF : material;
    }
}
