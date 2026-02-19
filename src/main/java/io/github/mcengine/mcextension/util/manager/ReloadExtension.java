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
    private ReloadExtension() {}

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
