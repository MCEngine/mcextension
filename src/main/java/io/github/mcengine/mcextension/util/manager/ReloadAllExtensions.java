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
    private ReloadAllExtensions() {}

    public static void invoke(JavaPlugin plugin, Executor executor,
                              Map<String, LoadedExtension> loadedExtensions,
                              Map<String, URLClassLoader> classLoaders,
                              MCExtensionManager manager) {
        manager.disableAllExtensions(plugin, executor);
        loadedExtensions.clear();
        classLoaders.clear();
        manager.loadAllExtensions(plugin, executor);
    }
}
