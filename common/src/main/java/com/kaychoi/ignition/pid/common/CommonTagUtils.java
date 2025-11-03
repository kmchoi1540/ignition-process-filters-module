package com.kaychoi.ignition.pid.common;

import com.inductiveautomation.ignition.common.config.BoundPropertySet;
import com.inductiveautomation.ignition.common.config.Property;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
import com.inductiveautomation.ignition.common.tags.config.types.OpcTagTypeProperties;
import com.inductiveautomation.ignition.common.tags.model.TagManager;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.common.tags.status.TagDiagnostics;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Utility helpers for tag operations that are safe to use on both Designer and Gateway scope
 * (they only depend on "common" APIs).
 *
 * Responsibilities:
 * - Parse a TagPath from a string safely.
 * - Check tag existence using TagManager.getDiagnosticsAsync(TagPath).
 * - Extract OPC-related properties from a tag's BoundPropertySet (valueSource/opcServer/opcItemPath).
 *
 * Notes:
 * - All async methods are fail-safe (exceptions are caught and mapped to safe defaults).
 * - No gateway-only classes are referenced here to keep this module "common".
 */
public class CommonTagUtils {

    private final TagManager tagManager;

    public CommonTagUtils(TagManager tagManager) {
        this.tagManager = tagManager;
    }

    /**
     * Asynchronously checks whether a tag exists by asking diagnostics from the TagManager.
     * If any error occurs during the async call, returns false.
     *
     * @param tagPathStr Full tag path, e.g. "[Provider]Folder/Tag".
     * @return future that completes with true if tag exists; false otherwise.
     */
    public boolean tagExists(String tagPathStr) {
        TagPath tagPath = parseTag(tagPathStr);
        if (tagPath == null) {
            return false;
        }
        try {
            return tagManager.getDiagnosticsAsync(tagPath)
                    .thenApply(TagDiagnostics::isTagFound)
                    .exceptionally(e -> false)
                    .join();
        } catch (CompletionException e) {
            return false;
        }
    }

    /**
     * Parses a TagPath from string safely using Ignition's TagPathParser.
     * Returns null if parsing fails.
     *
     * @param tagPathStr raw tag path string.
     * @return TagPath or null if invalid.
     */
    public TagPath parseTag(String tagPathStr) {
        if (tagPathStr == null) return null;
        try {
            return TagPathParser.parseSafe(tagPathStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes a single non-OPC tag asynchronously. Any failure is logged and ignored.
     * This method deliberately does not throw, to be safe in high-frequency control loops.
     *
     * @param tagPath target tag path (memory/value-driven tags).
     * @param value   value to write.
     * @return future completing when the async write call completes.
     */
    public CompletableFuture<Void> writeMemoryTag(TagPath tagPath, Object value) {
        try {
            return tagManager
                    .writeAsync(Collections.singletonList(tagPath), Collections.singletonList(value))
                    .thenAccept(results -> { /* swallow results (best-effort write) */ })
                    .exceptionally(ex -> {
                        System.err.println("[CommonTagUtils] Memory write failed: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception ex) {
            System.err.println("[CommonTagUtils] Memory write exception: " + ex.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Extracts OPC-related properties from a tag configuration (BoundPropertySet).
     * Returned map keys:
     *  - "valueSource" : String ("opc", "memory", etc.)
     *  - "isOpcTag"    : Boolean
     *  - "opcItemPath" : String (empty if not OPC tag)
     *  - "opcServer"   : String (empty if not OPC tag)
     *
     * @param config BoundPropertySet for the tag.
     * @return immutable map with properties populated.
     * @throws IllegalArgumentException if config is null.
     */
    public static Map<String, Object> getOpcProperties(BoundPropertySet config) {
        if (config == null) throw new IllegalArgumentException("Config must not be null");

        Map<String, Object> result = new HashMap<>();
        String valueSource = getString(config, WellKnownTagProps.ValueSource).orElse("memory");
        result.put("valueSource", valueSource);

        boolean isOpc = "opc".equalsIgnoreCase(valueSource);
        result.put("isOpcTag", isOpc);

        String opcItemPath = "";
        String opcServer = "";
        if (isOpc) {
            opcItemPath = getString(config, OpcTagTypeProperties.OPCItemPath).orElse("");
            opcServer = getString(config, OpcTagTypeProperties.OPCServer).orElse("");
        }
        result.put("opcItemPath", opcItemPath);
        result.put("opcServer", opcServer);

        return Collections.unmodifiableMap(result);
    }

    private static Optional<String> getString(BoundPropertySet config, Property<?> prop) {
        if (prop == null || !config.contains(prop)) return Optional.empty();
        Object v = config.getOrDefault(prop);
        return v == null ? Optional.empty() : Optional.of(v.toString());
    }
}
