package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.Executor;

public final class TriggerAsyncUpdateCheck {
    private TriggerAsyncUpdateCheck() {}

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
