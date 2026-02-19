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

    /**
     * Result status for attempting to load an extension jar.
     */
    public enum LoadResult {
        /** Extension loaded successfully. */
        SUCCESS,
        /** Fatal failure while loading extension. */
        FAILED,
        /** Dependencies not yet loaded; caller should retry later. */
        WAITING
    }

    /**
     * Git provider metadata used for update checks/downloads.
     */
    public static class GitInfo {
        public final String provider;
        public final String owner;
        public final String repository;

        /**
          * @param provider git provider id (e.g., github/gitlab)
          * @param owner    repository owner/org
          * @param repository repository name
          */
        public GitInfo(String provider, String owner, String repository) {
            this.provider = provider;
            this.owner = owner;
            this.repository = repository;
        }
    }

    /**
     * Runtime metadata for a loaded extension.
     *
     * @param id       extension id
     * @param version  extension version
     * @param instance live extension instance
     * @param file     source jar file
     * @param gitInfo  optional git metadata for updates
     */
    public static record LoadedExtension(String id, String version, IMCExtension instance, File file, GitInfo gitInfo) {}

    /**
     * Applies any pending updates staged in the extensions folder (e.g., .update files).
     *
     * @param plugin          host plugin for logging
     * @param extensionFolder extensions/libs directory
     */
    private void finalizePendingUpdates(JavaPlugin plugin, File extensionFolder) {
        FinalizePendingUpdates.invoke(plugin, extensionFolder);
    }

    /**
     * Loads a single extension jar and registers it with this manager.
     *
     * @param plugin   host plugin
     * @param executor executor for extension lifecycle callbacks
     * @param jarFile  extension jar
     * @return load result enum
     * @throws IOException                  when jar access fails
     * @throws ReflectiveOperationException when main class instantiation fails
     */
    public LoadResult loadExtension(JavaPlugin plugin, Executor executor, File jarFile) throws IOException, ReflectiveOperationException {
        return LoadExtension.invoke(plugin, executor, jarFile, loadedExtensions, classLoaders, this);
    }

    /**
     * Disables and reloads a specific extension by id.
     *
     * @param plugin   host plugin
     * @param executor executor for extension lifecycle callbacks
     * @param id       extension id
     * @return true if reload succeeded, false otherwise
     */
    public boolean reloadExtension(JavaPlugin plugin, Executor executor, String id) {
        return ReloadExtension.invoke(plugin, executor, id, loadedExtensions, classLoaders, this);
    }

    /**
     * Disables all extensions and then reloads every extension from disk.
     *
     * @param plugin   host plugin
     * @param executor executor for extension lifecycle callbacks
     */
    public void reloadAllExtensions(JavaPlugin plugin, Executor executor) {
        ReloadAllExtensions.invoke(plugin, executor, loadedExtensions, classLoaders, this);
    }

    /**
     * Closes and removes the classloader associated with an extension id.
     *
     * @param id extension id
     */
    private void closeClassLoader(String id) {
        CloseClassLoader.invoke(id, classLoaders);
    }

    /**
     * Descriptor parsed from extension.yml.
     *
     * @param id               extension id/name
     * @param mainClass        fully qualified main class
     * @param version          extension version
     * @param extensionDepends dependent extension ids
     * @param gitInfo          optional git metadata
     * @param file             source jar
     */
    public static record ExtensionDescriptor(String id, String mainClass, String version, List<String> extensionDepends,
                                       GitInfo gitInfo, File file) {
    }
}
