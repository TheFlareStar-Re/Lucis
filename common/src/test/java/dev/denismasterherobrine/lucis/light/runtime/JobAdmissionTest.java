package dev.denismasterherobrine.lucis.light.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class JobAdmissionTest {
    @Test
    void concurrentAdmissionNeverExceedsLimitAndReleasesCapacity() throws Exception {
        JobAdmission admission = new JobAdmission(3);
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        ArrayList<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return admission.tryAcquire();
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) accepted++;
            }
            assertEquals(3, accepted);
            assertEquals(3, admission.pending());
            assertFalse(admission.tryAcquire());
            admission.release();
            assertTrue(admission.tryAcquire());
            for (int i = 0; i < 3; i++) admission.release();
            assertEquals(0, admission.pending());
        } finally {
            executor.shutdownNow();
        }
    }
}
