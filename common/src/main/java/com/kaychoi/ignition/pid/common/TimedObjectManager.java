package com.kaychoi.ignition.pid.common;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Generic timed object manager.
 * Keeps objects alive for a given TTL and automatically removes expired ones.
 * Thread-safe and daemonized for Ignition module/gateway environments.
 *
 * @param <T> Type of managed object.
 */
public class TimedObjectManager<T> {

    private final long ttlMillis;
    private final ConcurrentMap<UUID, TimedObject<T>> map = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    /**
     * @param ttlMillis              Time-to-live (ms) for each object.
     * @param cleanupIntervalMillis  Interval between cleanup runs (ms).
     * @param threadName             Daemon thread name for logging clarity.
     */
    public TimedObjectManager(long ttlMillis, long cleanupIntervalMillis, String threadName) {
        this.ttlMillis = ttlMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::cleanupExpiredEntries,
                cleanupIntervalMillis, cleanupIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Legacy key-based variant (kept for backward compatibility). */
    public UUID addOrGetUUIDForKey(String key,
                                   Supplier<T> objectSupplier,
                                   ConcurrentMap<String, UUID> keyToUUIDMap) {
        UUID uuid = keyToUUIDMap.computeIfAbsent(key, k -> UUID.randomUUID());
        map.computeIfAbsent(uuid, id -> new TimedObject<>(objectSupplier.get()));
        touch(uuid);
        return uuid;
    }

    /** Direct UUID-based creation or retrieval. */
    public T getOrCreate(UUID uuid, Supplier<T> supplier) {
        map.computeIfAbsent(uuid, id -> new TimedObject<>(supplier.get()));
        touch(uuid);
        return map.get(uuid).object;
    }

    /** Direct insertion (optional API for explicit updates). */
    public void put(UUID uuid, T obj) {
        map.put(uuid, new TimedObject<>(obj));
    }

    /** Get object by UUID without touching TTL timer. */
    public T get(UUID uuid) {
        TimedObject<T> timed = map.get(uuid);
        return timed == null ? null : timed.object;
    }

    /** Update last-access timestamp manually. */
    public void touch(UUID uuid) {
        TimedObject<T> timed = map.get(uuid);
        if (timed != null) timed.touch();
    }

    /** Remove a specific UUID and optional key map entry. */
    public void remove(UUID uuid, ConcurrentMap<String, UUID> keyToUUIDMap) {
        map.remove(uuid);
        if (keyToUUIDMap != null) {
            keyToUUIDMap.values().removeIf(u -> u.equals(uuid));
        }
    }

    /** Background cleanup task. */
    private void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> now - e.getValue().lastAccessTime > ttlMillis);
    }

    /** Graceful shutdown of cleanup thread. */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    /** Internal structure wrapping the object and its last-access timestamp. */
    private static class TimedObject<T> {
        final T object;
        volatile long lastAccessTime;
        TimedObject(T object) { this.object = object; touch(); }
        void touch() { lastAccessTime = System.currentTimeMillis(); }
    }
}
