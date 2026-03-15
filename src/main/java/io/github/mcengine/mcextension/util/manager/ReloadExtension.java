package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import io.github.mcengine.mcextension.common.MCExtensionManager.LoadResult;
import io.github.mcengine.mcextension.common.MCExtensionManager.LoadedExtension;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Disables and reloads a specific extension by id.
 */
public final class ReloadExtension {
    /**
     * Static utility class meant to prevent instantiation.
     */
    private ReloadExtension() {}

    /**
     * Performs a targeted reload for a loaded extension.
     * <p>
     * The method captures the current jar path, delegates disable to the manager,
     * and attempts a fresh load from the same artifact to preserve update/recovery
     * semantics for one extension without restarting the whole ecosystem.
     * </p>
     *
     * @param plugin host plugin used for logging and lifecycle context
     * @param executor executor used for extension lifecycle callbacks off main thread
     * @param id extension ID to reload
     * @param loadedExtensions active extension registry used to resolve the target jar
     * @param classLoaders classloader registry managed by the parent manager
     * @param manager manager responsible for disable/load orchestration
     * @return {@code true} when reload completes successfully; otherwise {@code false}
     */
    public static boolean invoke(JavaPlugin plugin, Executor executor, String id,
                                 Map<String, LoadedExtension> loadedExtensions,
                                 Map<String, URLClassLoader> classLoaders,
                                 MCExtensionManager manager) {
        LoadedExtension existing = loadedExtensions.get(id);
        if (existing == null) {
            plugin.getLogger().warning("Extension not loaded: " + id);
            return false;
        }

        File jar = existing.file();
        manager.disableExtension(plugin, executor, id);

        try {
            LoadResult result = manager.loadExtension(plugin, executor, jar);
            if (result == LoadResult.SUCCESS) {
                plugin.getLogger().info("Reloaded Extension: " + id);
                return true;
            }
            plugin.getLogger().severe("Failed to reload extension " + id + ": " + result);
            return false;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload extension " + id + ": " + e.getMessage());
            return false;
        }
    }
}
