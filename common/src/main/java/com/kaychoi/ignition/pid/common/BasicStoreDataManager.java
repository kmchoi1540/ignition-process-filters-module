package com.kaychoi.ignition.pid.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

/**
 * Basic store data function managing StoreData buffers with TTL.
 */
public class BasicStoreDataManager {

    private final TimedObjectManager<StoreData> timedObjectManager;
    private final ConcurrentMap<String, UUID> keyToUUIDMap;

    /**
     * Constructs with external TimedObjectManager and key-UUID map.
     *
     * @param timedObjectManager manager that handles TTL and cleanup for StoreData.
     * @param keyToUUIDMap external concurrent map to maintain key-UUID mappings.
     */
    public BasicStoreDataManager(TimedObjectManager<StoreData> timedObjectManager,
                                 ConcurrentMap<String, UUID> keyToUUIDMap) {
        this.timedObjectManager = timedObjectManager;
        this.keyToUUIDMap = keyToUUIDMap;
    }

    /**
     * Adds input to buffer identified by key.
     *
     * @param enable whether to keep buffer state or reset
     * @param size maximum buffer size
     * @param input input value to add
     * @param key unique key for buffer identification
     * @return current buffer contents as list
     */
    public List<Double> addInput(boolean enable, int size, double input, String key) {
        UUID uuid = timedObjectManager.addOrGetUUIDForKey(key, () -> new StoreData(false), keyToUUIDMap);
        StoreData storeData = timedObjectManager.get(uuid);
        if (storeData == null) {
            // Should not happen, but fallback
            storeData = new StoreData(false);
            StoreData finalStoreData = storeData;
            timedObjectManager.addOrGetUUIDForKey(key, () -> finalStoreData, keyToUUIDMap);
        }
        return storeData.addInput(enable, size, input);
    }

    /**
     * Retrieves a defensive copy of the buffer contents for the given key.
     *
     * @param key unique key for buffer identification
     * @return list of stored values, empty list if key not found
     */
    public List<Double> getHistory(String key) {
        UUID uuid = keyToUUIDMap.get(key);
        if (uuid == null) return Collections.emptyList();

        StoreData storeData = timedObjectManager.get(uuid);
        if (storeData == null) return Collections.emptyList();

        List<Double> list = new ArrayList<>(storeData.size());
        for (Double d : storeData) {
            list.add(d);
        }
        return list;
    }

    /**
     * Remove buffer and UUID mapping by key.
     *
     * @param key unique key for buffer identification
     */
    public void removeByKey(String key) {
        UUID uuid = keyToUUIDMap.remove(key);
        if (uuid != null) {
            timedObjectManager.remove(uuid, keyToUUIDMap);
        }
    }

    /**
     * Remove all buffers and clear UUID mappings.
     */
    public void removeAll() {
        for (String key : keyToUUIDMap.keySet()) {
            removeByKey(key);
        }
    }
}
