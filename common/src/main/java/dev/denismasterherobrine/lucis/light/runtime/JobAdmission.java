package dev.denismasterherobrine.lucis.light.runtime;

import java.util.concurrent.atomic.AtomicInteger;

public final class JobAdmission {
    private final int limit;
    private final AtomicInteger pending = new AtomicInteger();

    public JobAdmission(int limit) {
        this.limit = Math.max(1, limit);
    }

    public boolean tryAcquire() {
        int current;
        do {
            current = pending.get();
            if (current >= limit) {
                return false;
            }
        } while (!pending.compareAndSet(current, current + 1));
        return true;
    }

    public void release() {
        if (pending.decrementAndGet() < 0) {
            throw new IllegalStateException("Unbalanced job admission release");
        }
    }

    public int pending() {
        return pending.get();
    }
}
