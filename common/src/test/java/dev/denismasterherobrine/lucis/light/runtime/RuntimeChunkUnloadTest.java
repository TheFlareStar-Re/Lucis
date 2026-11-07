package dev.denismasterherobrine.lucis.light.runtime;

import dev.denismasterherobrine.lucis.light.region.OwnedRegionCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RuntimeRegionState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeChunkUnloadTest {
    @Test
    void unloadingDropsStaleCommitsButPreservesUnrelatedPublicationPins() throws Exception {
        try (LucisRuntimeManager manager = new LucisRuntimeManager()) {
            OwnedRegionCache cache = (OwnedRegionCache) field(manager, "regionCache");
            RuntimeRegionState unloaded = cache.getOrCreate(bounds(0));
            RuntimeRegionState other = cache.getOrCreate(bounds(8));
            unloaded.retain();
            unloaded.retain();
            other.retain();
            @SuppressWarnings("unchecked") Queue<Object> queue = (Queue<Object>) field(manager, "commitQueue");
            Class<?> type = Class.forName(LucisRuntimeManager.class.getName() + "$RuntimeCommit");
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            queue.add(constructor.newInstance(null, null, unloaded, unloaded.epoch()));
            queue.add(constructor.newInstance(null, null, other, other.epoch()));
            manager.onChunkUnloaded(0, 0);
            assertEquals(1, queue.size());
            assertTrue(unloaded.unloaded());
            assertTrue(unloaded.inUse());
            assertTrue(other.inUse());
            // 剩余的是后台任务的 pin，而非已移除提交的 pin。
            unloaded.release();
            assertFalse(unloaded.inUse());
            cache.trimToBudget(128, Long.MAX_VALUE);
            assertNull(cache.get(0));
            assertSame(other, cache.get(bounds(8).coreRegionKey()));
            manager.close();
            assertFalse(other.inUse());
            assertTrue(queue.isEmpty());
            assertEquals(0, cache.size());
            manager.onChunkUnloaded(8, 0);
            assertEquals(0, cache.size());
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static RegionBounds bounds(int x) {
        return new RegionBounds(x, 0, 1, 0, 16, 16, 0, 16, 0, 1, 16, 256, 4096);
    }
}
