package io.github.mcengine.mcextension.common;

import io.github.mcengine.mcextension.api.IMCExtension;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
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

    private final JavaPlugin plugin;
    private final File extensionFolder;
    private final Executor executor;

    // Tracks loaded extension metadata: {ID : Version}
    private final Map<String, String> loadedExtensionsInfo = new HashMap<>();
    
    // Tracks loaded extension instances: {ID : Instance}
    private final Map<String, IMCExtension> loadedInstances = new HashMap<>();

    /**
     * Creates a new MCExtensionManager for the given plugin.
     * <p>
     * Extensions will be loaded from: {@code plugins/{PluginName}/extensions/}
     * </p>
     *
     * @param plugin   The host plugin instance.
     * @param executor The executor responsible for handling extension tasks (e.g., Async/Folia scheduler).
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
     * Scans the extension folder and loads all valid .jar files.
     */
    public void loadAllExtensions() {
        File[] files = extensionFolder.listFiles((dir, name) -> name.endsWith(".jar"));
        
        if (files == null || files.length == 0) {
            plugin.getLogger().info("No extensions found in " + extensionFolder.getPath());
            return;
        }

        plugin.getLogger().info("Found " + files.length + " extension(s). Loading...");

        for (File file : files) {
            try {
                loadExtension(file);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load extension: " + file.getName());
                e.printStackTrace();
            }
        }
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
            // Remove from registry regardless of success/failure to ensure clean state
            loadedInstances.remove(id);
            loadedExtensionsInfo.remove(id);
        }
        return true;
    }

    /**
     * Disables all loaded extensions and clears the registry.
     */
    public void disableAllExtensions() {
        // Create a copy of values to avoid ConcurrentModificationException during iteration
        for (IMCExtension extension : new HashMap<>(loadedInstances).values()) {
            try {
                extension.onDisable(plugin, executor);
            } catch (Exception e) {
                plugin.getLogger().severe("Error disabling extension " + extension.getId());
                e.printStackTrace();
            }
        }
        loadedExtensionsInfo.clear();
        loadedInstances.clear();
    }

    /**
     * Loads a specific extension JAR file.
     *
     * @param jarFile The JAR file to load.
     * @throws IOException If file access fails.
     * @throws ReflectiveOperationException If any reflection error occurs (ClassNotFound, NoSuchMethod, etc.).
     */
    private void loadExtension(File jarFile) throws IOException, ReflectiveOperationException {
        // 1. Setup ClassLoader (Parent is the plugin's loader so extension can see Bukkit/Plugin API)
        URL[] urls = {jarFile.toURI().toURL()};
        URLClassLoader loader = new URLClassLoader(urls, plugin.getClass().getClassLoader());

        // 2. Scan JAR to find the class implementing IMCExtension
        Class<?> mainClass = null;

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;

                // Convert path to class name (e.g., com/example/Main.class -> com.example.Main)
                String className = entry.getName().replace('/', '.').replace(".class", "");

                try {
                    // Load the class strictly to check compatibility
                    Class<?> clazz = loader.loadClass(className);
                    
                    // Enforce usage of IMCExtension interface
                    if (IMCExtension.class.isAssignableFrom(clazz) && !clazz.isInterface()) {
                        mainClass = clazz;
                        break; // Found the entry point
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // Skip classes that fail to load (e.g., optional dependencies missing)
                }
            }
        }

        if (mainClass == null) {
            plugin.getLogger().warning("Skipping " + jarFile.getName() + ": No class implementing IMCExtension found.");
            return;
        }

        // 3. Instantiate the Extension
        // Updated to use Constructor.newInstance() instead of deprecated Class.newInstance()
        IMCExtension extension = (IMCExtension) mainClass.getDeclaredConstructor().newInstance();
        String id = extension.getId();
        String version = extension.getVersion();

        // 4. Duplicate ID Check
        if (loadedExtensionsInfo.containsKey(id)) {
            plugin.getLogger().warning("Skipping " + jarFile.getName() + ": Duplicate Extension ID '" + id + "' already loaded.");
            return;
        }

        // 5. Lifecycle: Load
        try {
            extension.onLoad(plugin, executor);
            
            // 6. Register
            loadedExtensionsInfo.put(id, version);
            loadedInstances.put(id, extension);
            
            plugin.getLogger().info("Loaded Extension: " + id + " (v" + version + ")");
        } catch (Exception e) {
            plugin.getLogger().severe("Error occurred while initializing extension: " + id);
            e.printStackTrace();
        }
    }
    
    /**
     * Gets a map of loaded extension IDs and their versions.
     * * @return Map of {ID : Version}
     */
    public Map<String, String> getLoadedExtensions() {
        return new HashMap<>(loadedExtensionsInfo);
    }
}
