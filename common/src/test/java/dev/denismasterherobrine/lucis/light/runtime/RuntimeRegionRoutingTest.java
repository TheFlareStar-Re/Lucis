package dev.denismasterherobrine.lucis.light.runtime;

import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeRegionRoutingTest {
    @Test
    void routesBorderChangesToAllNeighboringCores() {
        List<Long> keys = LucisRuntimeManager.affectedRegionKeys(0, 0, 1);
        assertEquals(9, new HashSet<>(keys).size());
        assertTrue(keys.contains(RegionBounds.regionKey(-1, -1)));
        assertTrue(keys.contains(RegionBounds.regionKey(1, 1)));
    }

    @Test
    void largerRegionsUseFloorDivisionAtNegativeCoordinates() {
        assertEquals(new HashSet<>(List.of(
                RegionBounds.regionKey(-4, -4), RegionBounds.regionKey(0, -4),
                RegionBounds.regionKey(-4, 0), RegionBounds.regionKey(0, 0))),
                new HashSet<>(LucisRuntimeManager.affectedRegionKeys(-1, -1, 4)));
        assertEquals(List.of(RegionBounds.regionKey(0, 0)),
                LucisRuntimeManager.affectedRegionKeys(2, 2, 4));
    }

    @Test
    void concurrentDrainDoesNotLoseAcceptedUpdates() throws Exception {
        RuntimeUpdateQueue queue = new RuntimeUpdateQueue(100_000, 100_000);
        int updates = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var producer = executor.submit(() -> {
                start.await();
                for (int i = 0; i < updates; i++) {
                    assertTrue(queue.enqueue(0, i, 0, 0, null, null));
                }
                return null;
            });
            start.countDown();
            int drained = 0;
            Map<Long, RuntimeRegionBatch> batches = new HashMap<>();
            while (!producer.isDone()) {
                batches.clear();
                queue.drainTo(batches);
                drained += batches.values().stream().mapToInt(RuntimeRegionBatch::queuedChangeCount).sum();
            }
            producer.get();
            batches.clear();
            queue.drainTo(batches);
            drained += batches.values().stream().mapToInt(RuntimeRegionBatch::queuedChangeCount).sum();
            assertEquals(updates, drained);
            assertTrue(queue.isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }
}
