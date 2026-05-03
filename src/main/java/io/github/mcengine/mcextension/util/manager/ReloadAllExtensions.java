package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import io.github.mcengine.mcextension.common.MCExtensionManager.LoadedExtension;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Disables all currently loaded extensions, then loads all extensions again.
 */
public final class ReloadAllExtensions {
    /**
     * Static utility class meant to prevent instantiation.
     */
    private ReloadAllExtensions() {}

    /**
     * Executes a full reload cycle by disabling all active extensions, clearing runtime registries,
     * and triggering a new bulk load pass through the owning manager.
     *
     * @param plugin host plugin context used for lifecycle callbacks
     * @param executor executor used to execute lifecycle work off the main thread
     * @param loadedExtensions mutable map containing loaded extension metadata
     * @param classLoaders mutable map containing extension classloaders
     * @param manager manager coordinating lifecycle operations
     */
    public static void invoke(JavaPlugin plugin, Executor executor,
                              Map<String, LoadedExtension> loadedExtensions,
                              Map<String, URLClassLoader> classLoaders,
                              MCExtensionManager manager) {
        manager.disableAllExtensions(plugin, executor);
        manager.loadAllExtensions(plugin, executor);
    }
}
