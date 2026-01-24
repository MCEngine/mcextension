package io.github.mcengine.mcextension.common;

import io.github.mcengine.mcextension.api.IMCExtension;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Manages the loading, lifecycle, and tracking of {@link IMCExtension}s.
 * <p>
 * Each plugin should instantiate its own {@code MCExtensionManager}. This ensures that
 * extensions are isolated per plugin.
 * </p>
 */
public class MCExtensionManager {

    /**
     * The host plugin instance that owns this manager.
     */
    private final JavaPlugin plugin;

    /**
     * The directory where extension JAR files are stored.
     */
    private final File extensionFolder;

    /**
     * The executor used for running extension-related tasks.
     */
    private final Executor executor;

    /**
     * A map tracking loaded extension metadata, where the key is the extension ID
     * and the value is the version string.
     */
    private final Map<String, String> loadedExtensionsInfo = new HashMap<>();
    
    /**
     * A map tracking active extension instances, where the key is the extension ID
     * and the value is the instantiated {@link IMCExtension}.
     */
    private final Map<String, IMCExtension> loadedInstances = new HashMap<>();

    /**
     * Creates a new MCExtensionManager for the given plugin.
     *
     * @param plugin   The host plugin instance.
     * @param executor The executor responsible for handling extension tasks.
     */
    public MCExtensionManager(JavaPlugin plugin, Executor executor) {
        this.plugin = plugin;
        this.executor = executor;
        this.extensionFolder = new File(plugin.getDataFolder(), "extensions");
        
        if (!extensionFolder.exists()) {
            extensionFolder.mkdirs();
        }
    }

    /**
     * Scans the extension folder and loads all valid .jar files with dependency resolution.
     * <p>
     * This method uses a multi-pass approach to ensure that extensions are loaded only
     * after their required dependencies (both base plugins and other extensions) are ready.
     * </p>
     */
    public void loadAllExtensions() {
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
                    LoadResult result = loadExtension(file);
                    if (result == LoadResult.SUCCESS) {
                        iterator.remove();
                        changed = true;
                    } else if (result == LoadResult.FAILED) {
                        iterator.remove();
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to load extension: " + file.getName());
                    e.printStackTrace();
                    iterator.remove();
                }
            }
        }

        if (!pendingFiles.isEmpty()) {
            for (File file : pendingFiles) {
                plugin.getLogger().severe("Could not load " + file.getName() + ": Dependency requirements not met.");
            }
        }
    }

    /**
     * Represents the result of an individual extension load attempt.
     */
    private enum LoadResult { 
        /** Extension was loaded successfully. */
        SUCCESS, 
        /** Extension failed to load due to missing data or errors. */
        FAILED, 
        /** Extension is waiting for its dependencies to be loaded. */
        WAITING 
    }

    /**
     * Disables a specific extension by its ID.
     *
     * @param id The unique ID of the extension to disable.
     * @return true if the extension was found and disabled, false if not found.
     */
    public boolean disableExtension(String id) {
        if (!loadedInstances.containsKey(id)) {
            return false;
        }

        IMCExtension extension = loadedInstances.get(id);
        try {
            extension.onDisable(plugin, executor);
            plugin.getLogger().info("Disabled Extension: " + id);
        } catch (Exception e) {
            plugin.getLogger().severe("Error disabling extension " + id);
            e.printStackTrace();
        } finally {
            loadedInstances.remove(id);
            loadedExtensionsInfo.remove(id);
        }
        return true;
    }

    /**
     * Disables all currently loaded extensions and clears the internal registries.
     */
    public void disableAllExtensions() {
        for (String id : new HashMap<>(loadedInstances).keySet()) {
            disableExtension(id);
        }
        loadedExtensionsInfo.clear();
        loadedInstances.clear();
    }

    /**
     * Logic for loading a single extension JAR.
     * <p>
     * This involves reading the nested extension.yml, verifying base plugin dependencies,
     * checking for other required extensions, and performing reflective instantiation.
     * </p>
     *
     * @param jarFile The JAR file to attempt to load.
     * @return The {@link LoadResult} indicating success, failure, or a dependency-induced wait.
     * @throws IOException If the file cannot be read.
     * @throws ReflectiveOperationException If the main class cannot be found or instantiated.
     */
    private LoadResult loadExtension(File jarFile) throws IOException, ReflectiveOperationException {
        String mainClassName = null;
        String id = null;
        String version = "1.0.0";
        
        List<String> baseDepend = new ArrayList<>();
        List<String> extDepend = new ArrayList<>();

        // 1. Read extension.yml from the JAR
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("extension.yml");
            if (entry == null) return LoadResult.FAILED;

            try (InputStream input = jar.getInputStream(entry)) {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(input);
                if (data != null) {
                    id = (String) data.get("name");
                    mainClassName = (String) data.get("main");
                    version = String.valueOf(data.getOrDefault("version", "1.0.0"));
                    
                    // Navigate Nested Map for 'base'
                    if (data.get("base") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> baseSection = (Map<String, Object>) data.get("base");
                        if (baseSection.get("depend") instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> castList = (List<String>) baseSection.get("depend");
                            baseDepend = castList;
                        }
                    }

                    // Navigate Nested Map for 'extension'
                    if (data.get("extension") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> extSection = (Map<String, Object>) data.get("extension");
                        if (extSection.get("depend") instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> castList = (List<String>) extSection.get("depend");
                            extDepend = castList;
                        }
                    }
                }
            }
        }

        if (id == null || mainClassName == null) return LoadResult.FAILED;

        // 2. Check Base Plugin Dependencies
        for (String dep : baseDepend) {
            if (!Bukkit.getPluginManager().isPluginEnabled(dep)) {
                plugin.getLogger().warning("Skipping " + id + ": Missing base plugin '" + dep + "'");
                return LoadResult.FAILED;
            }
        }

        // 3. Check Extension Dependencies
        for (String dep : extDepend) {
            if (!loadedInstances.containsKey(dep)) return LoadResult.WAITING;
        }

        // 4. Load Classes and Instantiate
        URL[] urls = {jarFile.toURI().toURL()};
        URLClassLoader loader = new URLClassLoader(urls, plugin.getClass().getClassLoader());
        Class<?> clazz = loader.loadClass(mainClassName);
        
        if (!IMCExtension.class.isAssignableFrom(clazz)) return LoadResult.FAILED;
        if (loadedExtensionsInfo.containsKey(id)) return LoadResult.FAILED;

        IMCExtension extension = (IMCExtension) clazz.getDeclaredConstructor().newInstance();

        // 5. License Check (loads from plugins/{main jar}/extensions/{extension name}/config.yml)
        File extConfigPath = new File(extensionFolder, id + File.separator + "config.yml");
        String licenseUrl = "";
        String licenseToken = "";

        if (extConfigPath.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(extConfigPath);
            licenseUrl = config.getString("license.url", "");
            licenseToken = config.getString("license.token", "");
        }

        if (!extension.checkLicense(licenseUrl, licenseToken)) {
            plugin.getLogger().severe("Extension " + id + " failed license verification! Skipping load.");
            return LoadResult.FAILED;
        }
        
        try {
            extension.onLoad(plugin, executor);
            loadedExtensionsInfo.put(id, version);
            loadedInstances.put(id, extension);
            plugin.getLogger().info("Loaded Extension: " + id + " (v" + version + ")");
            return LoadResult.SUCCESS;
        } catch (Exception e) {
            e.printStackTrace();
            return LoadResult.FAILED;
        }
    }

    /**
     * Gets a map of all currently loaded extension IDs and their versions.
     *
     * @return An unmodifiable-style copy of the loaded extensions info map.
     */
    public Map<String, String> getLoadedExtensions() {
        return new HashMap<>(loadedExtensionsInfo);
    }
}
