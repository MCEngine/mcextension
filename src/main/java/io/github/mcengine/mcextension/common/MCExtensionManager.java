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
 * Central orchestration component for extension discovery, dependency resolution,
 * lifecycle dispatch, and update finalization.
 * <p>
 * A dedicated manager instance is expected per host plugin so that runtime state
 * (loaded extensions, isolated classloaders, and reload boundaries) stays scoped to
 * that plugin instance and does not leak across multiple plugin containers.
 * </p>
 * <p>
 * Lifecycle callbacks are intentionally routed through an {@link Executor} supplied
 * by the host plugin, allowing costly operations (I/O, remote license verification,
 * and extension bootstrap/teardown routines) to execute away from the server main
 * thread to protect tick stability under high extension counts.
 * </p>
 */
public class MCExtensionManager {

    /**
     * Mutable registry of loaded extensions keyed by stable extension ID.
     * <p>
     * This structure is the source of truth for dependency checks, reload selection,
     * and version visibility. It is kept instance-local to maintain deterministic
     * lifecycle isolation between different host plugins.
     * </p>
     */
    private final Map<String, LoadedExtension> loadedExtensions = new HashMap<>();

    /**
     * Tracks extension-scoped classloaders for explicit resource cleanup.
     * <p>
     * Closing these loaders during disable/reload prevents jar file locks and avoids
     * memory retention from stale classes after update cycles.
     * </p>
     */
    private final Map<String, URLClassLoader> classLoaders = new HashMap<>();

    /**
     * Upper bound for concurrently loaded extensions.
     * <p>
     * A value of {@code -1} disables this safeguard. Any non-negative value acts as
     * back-pressure to limit memory growth and lifecycle workload during bulk loads.
     * </p>
     */
    private final int maxExtensions;

    /**
     * Creates a new manager with an optional extension count limit.
     *
     * @param maxExtensions maximum number of extensions that may be loaded;
     *                      use {@code -1} to disable the limit
     */
    public MCExtensionManager(int maxExtensions) {
        this.maxExtensions = maxExtensions;
    }

    /**
     * Performs the full cold-start extension loading pipeline for the host plugin.
     * <p>
     * The pipeline includes: pending update finalization, descriptor discovery from
     * extension jars, batch license validation, dependency-aware multi-pass loading,
     * and enforcement of the configured maximum extension count.
     * </p>
     * <p>
     * The provided {@link Executor} is propagated into extension lifecycle routines so
     * I/O-heavy and initialization tasks can remain off the main server thread.
     * </p>
     *
     * @param plugin   host plugin context used for filesystem root and structured logging
     * @param executor executor used to dispatch extension lifecycle work safely off-thread
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

        List<File> pendingFiles = new ArrayList<>();
        Map<File, ExtensionDescriptor> descriptorByFile = new HashMap<>();
        List<ExtensionDescriptor> pendingDescriptors = new ArrayList<>();
        for (File file : files) {
            ExtensionDescriptor descriptor = LoadExtension.readDescriptor(file);
            if (descriptor == null || descriptor.id() == null) {
                plugin.getLogger().severe("Skipping invalid extension descriptor in " + file.getName());
                continue;
            }

            pendingFiles.add(file);
            descriptorByFile.put(file, descriptor);
            pendingDescriptors.add(descriptor);
        }

        Map<String, Boolean> licenseResults = BatchCheckLicense.invokeAsync(plugin, pendingDescriptors);
        boolean changed = true;

        plugin.getLogger().info("Found " + files.length + " extension(s). Resolving dependencies...");

        while (changed && !pendingFiles.isEmpty()) {
            // Check limit before processing the current batch
            if (this.maxExtensions != -1 && loadedExtensions.size() >= this.maxExtensions) {
                plugin.getLogger().warning("Maximum extension limit (" + this.maxExtensions + ") reached. Stopping further loading.");
                break;
            }

            changed = false;
            Iterator<File> iterator = pendingFiles.iterator();

            while (iterator.hasNext()) {
                // Additional check inside the loop to ensure we don't exceed the limit mid-batch
                if (this.maxExtensions != -1 && loadedExtensions.size() >= this.maxExtensions) {
                    plugin.getLogger().warning("Maximum extension limit (" + this.maxExtensions + ") reached. Stopping further loading.");
                    break;
                }

                File file = iterator.next();
                ExtensionDescriptor descriptor = descriptorByFile.get(file);

                if (descriptor != null) {
                    Boolean licenseValid = licenseResults.get(descriptor.id());
                    if (Boolean.FALSE.equals(licenseValid)) {
                        plugin.getLogger().severe("Extension " + descriptor.id() + " failed batch license verification! Skipping load.");
                        iterator.remove();
                        continue;
                    }
                }
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
                if (this.maxExtensions != -1 && loadedExtensions.size() >= this.maxExtensions) {
                    plugin.getLogger().warning("Skipped " + file.getName() + " due to maximum extension limit.");
                } else {
                    plugin.getLogger().severe("Could not load " + file.getName() + ": extension dependencies not met.");
                }
            }
        }
    }

    /**
     * Disables a single loaded extension and tears down its runtime resources.
     * <p>
     * If the ID is present, this method removes the extension from the active registry,
     * invokes {@link IMCExtension#onDisable(JavaPlugin, Executor)}, and always attempts
     * classloader shutdown to prevent file and class metadata leaks even when disable
     * callbacks throw.
     * </p>
     *
     * @param plugin   host plugin context used by the extension disable callback
     * @param executor executor passed to disable hooks for non-main-thread tasks
     * @param id       unique extension ID to disable
     * @return {@code true} when an extension with the provided ID existed and was processed;
     *         {@code false} when no loaded extension matched the ID
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
     * Disables all currently loaded extensions and resets manager runtime state.
     * <p>
     * A defensive snapshot of IDs is used to avoid concurrent modification while each
     * disable operation mutates internal maps.
     * </p>
     *
     * @param plugin   host plugin context forwarded to each extension disable call
     * @param executor executor forwarded to extension lifecycle teardown routines
     */
    public void disableAllExtensions(JavaPlugin plugin, Executor executor) {
        for (String id : new HashMap<>(loadedExtensions).keySet()) {
            disableExtension(plugin, executor, id);
        }
        loadedExtensions.clear();
        classLoaders.clear();
    }

    /**
     * Produces a read-only snapshot model of loaded extension versions.
     *
     * @return newly allocated map where key is extension ID and value is extension version
     */
    public Map<String, String> getLoadedExtensions() {
        Map<String, String> info = new HashMap<>();
        for (Map.Entry<String, LoadedExtension> entry : loadedExtensions.entrySet()) {
            info.put(entry.getKey(), entry.getValue().version());
        }
        return info;
    }

    /**
     * Load outcome classification used by dependency-resolution loops.
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
     * Git source metadata used to resolve remote update channels for an extension.
     */
    public static class GitInfo {
        /**
         * Logical provider identifier (for example {@code github} or {@code gitlab}).
         */
        public final String provider;
        /**
         * Repository owner namespace used by the selected provider.
         */
        public final String owner;
        /**
         * Repository name used by the selected provider.
         */
        public final String repository;

        /**
         * Creates immutable git source metadata for update fetch flows.
         *
         * @param provider git provider ID (for example {@code github}/{@code gitlab})
         * @param owner repository owner or organization slug
         * @param repository repository name slug
         */
        public GitInfo(String provider, String owner, String repository) {
            this.provider = provider;
            this.owner = owner;
            this.repository = repository;
        }
    }

    /**
     * Immutable runtime descriptor for an active extension instance.
     *
     * @param id extension identifier used as registry key
     * @param version semantic or plugin-defined extension version string
     * @param instance live extension implementation bound to the host plugin
     * @param file source extension jar on disk
     * @param gitInfo optional remote git metadata used for update checks
     */
    public static record LoadedExtension(String id, String version, IMCExtension instance, File file, GitInfo gitInfo) {}

    /**
     * Finalizes pending update artifacts before descriptor scanning begins.
     *
     * @param plugin host plugin context used for logging and error reporting
     * @param extensionFolder extension root directory containing staged update files
     */
    private void finalizePendingUpdates(JavaPlugin plugin, File extensionFolder) {
        FinalizePendingUpdates.invoke(plugin, extensionFolder);
    }

    /**
     * Loads a single extension jar into this manager instance.
     * <p>
     * This method guards manual/single-file loading with the same extension limit policy
     * used by bulk loading so operational constraints remain consistent.
     * </p>
     *
     * @param plugin host plugin context
     * @param executor executor used to run extension lifecycle work off the main thread
     * @param jarFile extension jar to parse, validate, and initialize
     * @return {@link LoadResult#SUCCESS} when loading completed, {@link LoadResult#FAILED}
     *         for hard failure, or {@link LoadResult#WAITING} when dependencies are unresolved
     * @throws IOException when jar reading or descriptor parsing fails at I/O level
     * @throws ReflectiveOperationException when extension main class loading/instantiation fails
     */
    public LoadResult loadExtension(JavaPlugin plugin, Executor executor, File jarFile) throws IOException, ReflectiveOperationException {
        // Enforce the max extensions limit here as well to protect manual loading
        if (this.maxExtensions != -1 && loadedExtensions.size() >= this.maxExtensions) {
            plugin.getLogger().warning("Cannot load " + jarFile.getName() + ": maximum extension limit (" + this.maxExtensions + ") has been reached.");
            return LoadResult.FAILED;
        }
        return LoadExtension.invoke(plugin, executor, jarFile, loadedExtensions, classLoaders, this);
    }

    /**
     * Reloads a single extension by ID through disable-then-load orchestration.
     *
     * @param plugin host plugin context
     * @param executor executor used for lifecycle callback execution
     * @param id extension ID to reload
     * @return {@code true} when the extension is successfully reloaded; otherwise {@code false}
     */
    public boolean reloadExtension(JavaPlugin plugin, Executor executor, String id) {
        return ReloadExtension.invoke(plugin, executor, id, loadedExtensions, classLoaders, this);
    }

    /**
     * Reloads all extensions by performing global disable followed by full load pass.
     *
     * @param plugin host plugin context
     * @param executor executor used to offload extension lifecycle and I/O-adjacent tasks
     */
    public void reloadAllExtensions(JavaPlugin plugin, Executor executor) {
        ReloadAllExtensions.invoke(plugin, executor, loadedExtensions, classLoaders, this);
    }

    /**
     * Closes and unregisters the classloader mapped to an extension ID.
     *
     * @param id extension ID whose classloader should be released
     */
    private void closeClassLoader(String id) {
        CloseClassLoader.invoke(id, classLoaders);
    }

    /**
     * Parsed extension descriptor payload produced from extension metadata resources.
     *
     * @param id extension ID/name used for registry and dependency resolution
     * @param mainClass fully qualified class name implementing {@link IMCExtension}
     * @param version extension version string
     * @param extensionDepends dependency IDs that must be loaded first
     * @param gitInfo optional git provider metadata for update workflows
     * @param file source jar file containing this descriptor
     */
    public static record ExtensionDescriptor(String id, String mainClass, String version, List<String> extensionDepends,
                                             GitInfo gitInfo, File file) {
    }
}
