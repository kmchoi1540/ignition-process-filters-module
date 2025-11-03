package com.kaychoi.ignition.pid.common;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Centralized key-to-UUID registry.
 * - Supports namespaces (e.g., "storeFilterData", "customFilter").
 * - Ensures stable UUID mapping for a given key across the runtime.
 * - Uses TimedObjectManager for automatic cleanup (TTL).
 */
public final class UUIDKeyManager {

    private static final long TTL_MILLIS = 60 * 60 * 1000L;
    private static final long CLEANUP_MILLIS = 10 * 60 * 1000L;

    private static final ConcurrentMap<String, UUID> KEY_TO_UUID = new ConcurrentHashMap<>();
    private static final TimedObjectManager<UUID> MANAGER =
            new TimedObjectManager<>(TTL_MILLIS, CLEANUP_MILLIS, "UUIDKeyManager-Cleanup");

    private UUIDKeyManager() {}

    /** Returns a stable UUID for a (namespace, key) pair. */
    public static UUID getOrCreateUUID(String namespace, String key) {
        String composite = compositeKey(namespace, key);
        return MANAGER.addOrGetUUIDForKey(composite, UUID::randomUUID, KEY_TO_UUID);
    }

    /** Removes both UUID and key association. */
    public static void remove(String namespace, String key) {
        String composite = compositeKey(namespace, key);
        UUID uuid = KEY_TO_UUID.remove(composite);
        if (uuid != null) MANAGER.remove(uuid, KEY_TO_UUID);
    }

    /** Checks whether the mapping still exists and hasn't expired. */
    public static boolean contains(String namespace, String key) {
        String composite = compositeKey(namespace, key);
        UUID uuid = KEY_TO_UUID.get(composite);
        return uuid != null && MANAGER.get(uuid) != null;
    }

    public static void shutdown() { MANAGER.shutdown(); }

    private static String compositeKey(String ns, String key) {
        Objects.requireNonNull(ns, "namespace");
        Objects.requireNonNull(key, "key");
        return ns + ":" + key;
    }
}
