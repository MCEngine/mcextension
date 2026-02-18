package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Schedules asynchronous update checks for an extension when configured.
 */
public final class TriggerAsyncUpdateCheck {
    private TriggerAsyncUpdateCheck() {}

    /**
     * Enqueues an update check on the provided executor when config flag <code>extension.update</code> is true
     * and git info exists.
     *
     * @param plugin           host plugin for config/logging
     * @param executor         executor to run update checks
     * @param descriptor       extension descriptor
     * @param manager          owning manager
     * @param loadedExtensions map of loaded extensions
     * @param classLoaders     map of classloaders
     */
    public static void invoke(JavaPlugin plugin, Executor executor, MCExtensionManager.ExtensionDescriptor descriptor,
                              MCExtensionManager manager,
                              Map<String, MCExtensionManager.LoadedExtension> loadedExtensions,
                              Map<String, java.net.URLClassLoader> classLoaders) {
        boolean updatesEnabled = plugin.getConfig() != null && plugin.getConfig().getBoolean("extension.update", false);
        if (!updatesEnabled || descriptor.gitInfo() == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    HandleUpdate.invoke(plugin, descriptor, manager, loadedExtensions, classLoaders);
                } catch (Exception ex) {
                    plugin.getLogger().severe("Update check failed for " + descriptor.id() + ": " + ex.getMessage());
                }
            });
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not schedule update check for " + descriptor.id() + ": " + ex.getMessage());
        }
    }
}
