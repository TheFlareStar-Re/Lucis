package dev.denismasterherobrine.lucis.light.engine;

import net.minecraft.core.IdMapper;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSectionSnapshotTest {
    @Test
    void uniformSectionResolvesMaterialOnceAndDoesNotReadLiveChanges() {
        Object air = new Object();
        Object stone = new Object();
        var states = states(air, stone);
        AtomicInteger lookups = new AtomicInteger();
        var snapshot = RuntimeSectionSnapshot.capture(states, state -> {
            lookups.incrementAndGet();
            return state == air ? 512 : 15;
        }, (state, index) -> fail("No dynamic materials"));
        states.set(0, 0, 0, stone);
        assertEquals(1, lookups.get());
        assertEquals(512, snapshot.uniform());
        assertEquals(512, snapshot.material(0, 0, 0));
    }

    @Test
    void mixedSectionResolvesStaticMaterialsPerPaletteEntry() {
        Object air = new Object();
        Object torch = new Object();
        var states = states(air, torch);
        states.set(15, 8, 0, torch);
        AtomicInteger lookups = new AtomicInteger();
        var snapshot = RuntimeSectionSnapshot.capture(states, state -> {
            lookups.incrementAndGet();
            return state == torch ? 224 : 512;
        }, (state, index) -> fail("No dynamic materials"));
        states.set(15, 8, 0, air);
        states.set(1, 1, 1, torch);
        assertEquals(2, lookups.get());
        assertEquals(224, snapshot.material(15, 8, 0));
        assertEquals(512, snapshot.material(1, 1, 1));
    }

    @Test
    void dynamicMaterialsAreCapturedAtTheirPositionsBeforeWorkerReads() {
        Object air = new Object();
        Object dynamic = new Object();
        var states = states(air, dynamic);
        states.set(3, 4, 5, dynamic);
        states.set(6, 7, 8, dynamic);
        AtomicInteger calls = new AtomicInteger();
        var snapshot = RuntimeSectionSnapshot.capture(states, state -> state == dynamic ? -1 : 512,
                (state, index) -> {
                    calls.incrementAndGet();
                    return index == ((4 << 8) | (5 << 4) | 3) ? 0x8123 : 15;
                });
        states.set(3, 4, 5, air);
        assertEquals(2, calls.get());
        assertEquals(0x8123, snapshot.material(3, 4, 5));
        assertEquals(15, snapshot.material(6, 7, 8));
        assertEquals(512, snapshot.material(0, 0, 0));
        assertEquals(2, calls.get());
    }

    @Test
    void globalPaletteCapturesEveryUsedState() {
        Object[] values = new Object[300];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Object();
        }
        var states = states(values);
        for (int i = 0; i < values.length; i++) {
            states.set(i & 15, i >> 8, (i >> 4) & 15, values[i]);
        }
        AtomicInteger lookups = new AtomicInteger();
        var snapshot = RuntimeSectionSnapshot.capture(states, state -> {
            lookups.incrementAndGet();
            for (int i = 0; i < values.length; i++) {
                if (state == values[i]) return i;
            }
            throw new AssertionError();
        }, (state, index) -> fail("No dynamic materials"));
        assertEquals(300, lookups.get());
        for (int i = 0; i < values.length; i++) {
            assertEquals(i, snapshot.material(i & 15, i >> 8, (i >> 4) & 15));
        }
    }

    private static PalettedContainer<Object> states(Object... values) {
        IdMapper<Object> registry = new IdMapper<>();
        for (Object value : values) registry.add(value);
        return new PalettedContainer<>(registry, values[0], PalettedContainer.Strategy.SECTION_STATES);
    }
}
