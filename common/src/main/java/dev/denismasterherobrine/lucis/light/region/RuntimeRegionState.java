package dev.denismasterherobrine.lucis.light.region;

import java.util.Map;

public final class RuntimeRegionState {
    private final RegionLightData data;
    private volatile boolean initialized;
    private volatile Object sourceIdentity;
    private Map<Long, ?> sources = Map.of();
    private long epoch;
    private volatile long lastAccessNanos;
    private final java.util.concurrent.atomic.AtomicInteger references = new java.util.concurrent.atomic.AtomicInteger();

    public void retain() {
        references.incrementAndGet();
    }

    public void release() {
        if (references.decrementAndGet() < 0) {
            throw new IllegalStateException("Unbalanced runtime region release");
        }
    }

    public boolean inUse() {
        return references.get() != 0;
    }

    public RuntimeRegionState(RegionLightData data) {
        this.data = data;
        touch();
    }

    public RegionLightData data() {
        return data;
    }

    public boolean initialized() {
        return initialized;
    }

    public boolean initializedFor(Object sourceIdentity) {
        return initialized && this.sourceIdentity == sourceIdentity;
    }

    public void markInitialized(Object sourceIdentity) {
        this.sourceIdentity = sourceIdentity;
        initialized = true;
        touch();
    }

    public synchronized boolean initializedForSources(Map<Long, ?> sources) {
        if (!initialized || this.sources.size() != sources.size()) {
            return false;
        }
        return sources.entrySet().stream().allMatch(entry -> this.sources.get(entry.getKey()) == entry.getValue());
    }

    public synchronized void markSources(Map<Long, ?> sources) {
        this.sources = Map.copyOf(sources);
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized boolean complete(long expectedEpoch, Object identity, Map<Long, ?> sources) {
        if (epoch != expectedEpoch) {
            return false;
        }
        markInitialized(identity);
        markSources(sources);
        return true;
    }

    public synchronized void invalidate() {
        epoch++;
        initialized = false;
        sources = Map.of();
        sourceIdentity = null;
    }

    public void touch() {
        lastAccessNanos = System.nanoTime();
    }

    public long lastAccessNanos() {
        return lastAccessNanos;
    }
}
