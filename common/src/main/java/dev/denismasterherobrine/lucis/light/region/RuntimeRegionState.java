package dev.denismasterherobrine.lucis.light.region;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public final class RuntimeRegionState {
    private final RegionLightData data;
    private volatile boolean initialized;
    private volatile boolean unloaded;
    // 缓存只校验来源身份，不能延长已卸载区块的生命周期。
    private volatile WeakReference<Object> sourceIdentity;
    private Map<Long, WeakReference<Object>> sources = Map.of();
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
        WeakReference<Object> identity = this.sourceIdentity;
        return initialized && sourceIdentity != null && identity != null && identity.refersTo(sourceIdentity);
    }

    public synchronized void markInitialized(Object sourceIdentity) {
        this.sourceIdentity = new WeakReference<>(sourceIdentity);
        initialized = true;
        touch();
    }

    public synchronized boolean initializedForSources(Map<Long, ?> sources) {
        if (!initialized || this.sources.size() != sources.size()) {
            return false;
        }
        for (Map.Entry<Long, ?> entry : sources.entrySet()) {
            WeakReference<Object> reference = this.sources.get(entry.getKey());
            if (entry.getValue() == null || reference == null || !reference.refersTo(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public synchronized void markSources(Map<Long, ?> sources) {
        Map<Long, WeakReference<Object>> identities = new HashMap<>(sources.size());
        sources.forEach((key, value) -> identities.put(key, new WeakReference<>(value)));
        this.sources = identities;
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized boolean complete(long expectedEpoch, Object identity, Map<Long, ?> sources) {
        if (unloaded || epoch != expectedEpoch) {
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

    public synchronized void unload() {
        unloaded = true;
        invalidate();
    }

    public boolean unloaded() {
        return unloaded;
    }

    public void touch() {
        lastAccessNanos = System.nanoTime();
    }

    public long lastAccessNanos() {
        return lastAccessNanos;
    }
}
