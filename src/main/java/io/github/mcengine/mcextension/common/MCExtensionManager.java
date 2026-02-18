package io.github.mcengine.mcextension.common;

import io.github.mcengine.mcextension.api.IMCExtension;
import io.github.mcengine.mcextension.util.manager.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Manages the loading, lifecycle, updates, and tracking of {@link IMCExtension}s.
 * <p>
 * Each plugin should instantiate its own {@code MCExtensionManager}. This ensures that
 * extensions are isolated per plugin.
 * </p>
 */
public class MCExtensionManager {

    private final Map<String, LoadedExtension> loadedExtensions = new HashMap<>();
    private final Map<String, URLClassLoader> classLoaders = new HashMap<>();

    public MCExtensionManager() {
    }

    /**
     * Scans the extension folder, completes any pending update renames, and loads all valid .jar files
     * with extension-only dependency resolution.
     *
     * @param plugin   The host plugin instance.
     * @param executor The executor responsible for handling extension tasks.
     */
    public void loadAllExtensions(JavaPlugin plugin, Executor executor) {
        File extensionFolder = new File(plugin.getDataFolder(), "extensions/libs");
        if (!extensionFolder.exists() && !extensionFolder.mkdirs()) {
            plugin.getLogger().severe("Failed to create extension folder: " + extensionFolder.getAbsolutePath());
            return;
        }

        finalizePendingUpdates(plugin, extensionFolder);

        File[] files = extensionFolder.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null || files.length == 0) {
            plugin.getLogger().info("No extensions found in " + extensionFolder.getPath());
            return;
        }

        List<File> pendingFiles = new ArrayList<>(Arrays.asList(files));
        boolean changed = true;

        plugin.getLogger().info("Found " + files.length + " extension(s). Resolving dependencies...");

        while (changed && !pendingFiles.isEmpty()) {
            changed = false;
            Iterator<File> iterator = pendingFiles.iterator();

            while (iterator.hasNext()) {
                File file = iterator.next();
                try {
                    LoadResult result = loadExtension(plugin, executor, file);
                    if (result == LoadResult.SUCCESS) {
                        iterator.remove();
                        changed = true;
                    } else if (result == LoadResult.FAILED) {
                        iterator.remove();
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to load extension: " + file.getName());
                    plugin.getLogger().severe(e.getMessage());
                    iterator.remove();
                }
            }
        }

        if (!pendingFiles.isEmpty()) {
            for (File file : pendingFiles) {
                plugin.getLogger().severe("Could not load " + file.getName() + ": extension dependencies not met.");
            }
        }
    }

    /**
     * Disables a specific extension by its ID and releases its classloader.
     *
     * @param plugin   The host plugin instance.
     * @param executor The executor responsible for handling extension tasks.
     * @param id       The unique ID of the extension to disable.
     * @return true if the extension was found and disabled, false otherwise.
     */
    public boolean disableExtension(JavaPlugin plugin, Executor executor, String id) {
        LoadedExtension loaded = loadedExtensions.remove(id);
        if (loaded == null) {
            return false;
        }

        try {
            loaded.instance().onDisable(plugin, executor);
            plugin.getLogger().info("Disabled Extension: " + id);
        } catch (Exception e) {
            plugin.getLogger().severe("Error disabling extension " + id + ": " + e.getMessage());
        } finally {
            closeClassLoader(id);
        }
        return true;
    }

    /**
     * Disables all currently loaded extensions and clears the internal registries.
     *
     * @param plugin   The host plugin instance.
     * @param executor The executor responsible for handling extension tasks.
     */
    public void disableAllExtensions(JavaPlugin plugin, Executor executor) {
        for (String id : new HashMap<>(loadedExtensions).keySet()) {
            disableExtension(plugin, executor, id);
        }
        loadedExtensions.clear();
        classLoaders.clear();
    }

    /**
     * Gets a copy of all currently loaded extension IDs and their versions.
     *
     * @return map of id -> version
     */
    public Map<String, String> getLoadedExtensions() {
        Map<String, String> info = new HashMap<>();
        for (Map.Entry<String, LoadedExtension> entry : loadedExtensions.entrySet()) {
            info.put(entry.getKey(), entry.getValue().version());
        }
        return info;
    }

    public enum LoadResult {
        SUCCESS,
        FAILED,
        WAITING
    }

    public static class GitInfo {
        public final String provider;
        public final String owner;
        public final String repository;

        public GitInfo(String provider, String owner, String repository) {
            this.provider = provider;
            this.owner = owner;
            this.repository = repository;
        }
    }

    public static record LoadedExtension(String id, String version, IMCExtension instance, File file, GitInfo gitInfo) {}

    private void finalizePendingUpdates(JavaPlugin plugin, File extensionFolder) {
        FinalizePendingUpdates.invoke(plugin, extensionFolder);
    }

    public LoadResult loadExtension(JavaPlugin plugin, Executor executor, File jarFile) throws IOException, ReflectiveOperationException {
        return LoadExtension.invoke(plugin, executor, jarFile, loadedExtensions, classLoaders, this);
    }

    private void closeClassLoader(String id) {
        CloseClassLoader.invoke(id, classLoaders);
    }

    public static record ExtensionDescriptor(String id, String mainClass, String version, List<String> extensionDepends,
                                       GitInfo gitInfo, File file) {
    }
}
