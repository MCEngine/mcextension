package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import io.github.mcengine.mcextension.common.git.github.MCExtensionGitHub;
import io.github.mcengine.mcextension.common.git.gitlab.MCExtensionGitLab;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Handles update checks and download/replace flow for an extension.
 */
public final class HandleUpdate {
    private HandleUpdate() {}

    /**
     * Checks for updates via git provider, downloads latest jar when available, and schedules hot swap.
     *
     * @param plugin           host plugin for logging/scheduling
     * @param descriptor       extension descriptor
     * @param manager          owning manager
     * @param loadedExtensions map of loaded extensions
     * @param classLoaders     map of classloaders
     */
    public static void invoke(JavaPlugin plugin, MCExtensionManager.ExtensionDescriptor descriptor,
                              MCExtensionManager manager,
                              Map<String, MCExtensionManager.LoadedExtension> loadedExtensions,
                              Map<String, URLClassLoader> classLoaders) {
        MCExtensionManager.GitInfo git = descriptor.gitInfo();
        if (git == null) {
            return;
        }
        boolean updateAvailable;
        String token = ResolveToken.invoke(plugin, git.provider);
        switch (git.provider.toLowerCase(Locale.ROOT)) {
            case "github" -> updateAvailable = MCExtensionGitHub.checkUpdate(plugin, git.owner, git.repository, descriptor.version(), token);
            case "gitlab" -> updateAvailable = MCExtensionGitLab.checkUpdate(plugin, git.owner, git.repository, descriptor.version(), token);
            default -> {
                plugin.getLogger().warning("Unknown git provider for extension " + descriptor.id() + ": " + git.provider);
                return;
            }
        }

        if (!updateAvailable) {
            return;
        }

        File parentDir = descriptor.file().getParentFile();
        File downloaded = switch (git.provider.toLowerCase(Locale.ROOT)) {
            case "github" -> MCExtensionGitHub.downloadUpdate(plugin, git.owner, git.repository, token, parentDir);
            case "gitlab" -> MCExtensionGitLab.downloadUpdate(plugin, git.owner, git.repository, token, parentDir);
            default -> null;
        };

        if (downloaded == null) {
            return;
        }

        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> swapAndReload(plugin, descriptor.id(), downloaded, loadedExtensions, classLoaders, manager));
    }

    /**
     * Swaps the downloaded jar into place and reloads the extension on the main thread.
     */
    private static void swapAndReload(JavaPlugin plugin, String id, File downloadedFile,
                                      Map<String, MCExtensionManager.LoadedExtension> loadedExtensions,
                                      Map<String, URLClassLoader> classLoaders,
                                      MCExtensionManager manager) {
        MCExtensionManager.LoadedExtension loaded = loadedExtensions.get(id);
        if (loaded == null) {
            plugin.getLogger().warning("Extension not loaded during swap: " + id);
            return;
        }

        Executor mainThread = command -> org.bukkit.Bukkit.getScheduler().runTask(plugin, command);
        manager.disableExtension(plugin, mainThread, id);

        File oldFile = loaded.file();
        File target = downloadedFile;
        File backup = null;
        try {
            if (oldFile.exists() && !oldFile.equals(downloadedFile)) {
                backup = new File(oldFile.getParentFile(), oldFile.getName() + ".bak");
                try {
                    Files.move(oldFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveEx) {
                    if (!oldFile.delete()) {
                        plugin.getLogger().severe("Could not backup or delete old jar for " + id + "; aborting update.");
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed preparing swap for " + id + ": " + ex.getMessage());
            return;
        }

        try {
            plugin.getLogger().info("Updated jar swapped for " + id + ". Reloading...");
            manager.loadExtension(plugin, mainThread, target);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to hot-swap extension " + id + ": " + e.getMessage());
        } finally {
            if (backup != null && backup.exists()) {
                if (!backup.delete()) {
                    plugin.getLogger().warning("Could not delete backup jar for " + id + " at " + backup.getName());
                }
            }
        }
    }
}
