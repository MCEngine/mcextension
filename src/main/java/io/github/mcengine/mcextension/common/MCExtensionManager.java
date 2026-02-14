package io.github.mcengine.mcextension.common;

import io.github.mcengine.mcextension.api.IMCExtension;
import io.github.mcengine.mcextension.common.git.github.MCExtensionGitHub;
import io.github.mcengine.mcextension.common.git.gitlab.MCExtensionGitLab;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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

    private enum LoadResult {
        SUCCESS,
        FAILED,
        WAITING
    }

    private static class GitInfo {
        private final String provider;
        private final String owner;
        private final String repository;

        private GitInfo(String provider, String owner, String repository) {
            this.provider = provider;
            this.owner = owner;
            this.repository = repository;
        }
    }

    private record LoadedExtension(String id, String version, IMCExtension instance, File file, GitInfo gitInfo) {}

    private void finalizePendingUpdates(JavaPlugin plugin, File extensionFolder) {
        File[] pending = extensionFolder.listFiles((dir, name) -> name.endsWith(".update") || name.endsWith(".jar.tmp"));
        if (pending == null || pending.length == 0) {
            return;
        }

        for (File file : pending) {
            String name = file.getName();
            String base = name.endsWith(".update") ? name.substring(0, name.length() - 7)
                    : name.substring(0, name.length() - 8);
            if (!base.endsWith(".jar")) {
                base = base + ".jar";
            }
            File target = new File(extensionFolder, base);
            if (target.exists() && !target.delete()) {
                plugin.getLogger().warning("Could not delete old jar while applying pending update: " + target.getName());
                continue;
            }
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Applied pending update for " + target.getName());
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to apply pending update for " + target.getName() + ": " + e.getMessage());
            }
        }
    }

    private LoadResult loadExtension(JavaPlugin plugin, Executor executor, File jarFile) throws IOException, ReflectiveOperationException {
        ExtensionDescriptor descriptor = readDescriptor(jarFile);
        if (descriptor == null || descriptor.id == null || descriptor.mainClass == null) {
            return LoadResult.FAILED;
        }

        for (String dep : descriptor.extensionDepends) {
            if (!loadedExtensions.containsKey(dep)) {
                return LoadResult.WAITING;
            }
        }

        if (loadedExtensions.containsKey(descriptor.id)) {
            plugin.getLogger().warning("Extension already loaded: " + descriptor.id);
            return LoadResult.FAILED;
        }

        URL[] urls = {jarFile.toURI().toURL()};
        URLClassLoader loader = new URLClassLoader(urls, plugin.getClass().getClassLoader());
        try {
            Class<?> clazz = loader.loadClass(descriptor.mainClass);
            if (!IMCExtension.class.isAssignableFrom(clazz)) {
                plugin.getLogger().severe("Main class does not implement IMCExtension: " + descriptor.mainClass);
                close(loader);
                return LoadResult.FAILED;
            }

            IMCExtension extension = (IMCExtension) clazz.getDeclaredConstructor().newInstance();

            if (!checkLicense(plugin, descriptor.id, extension)) {
                close(loader);
                return LoadResult.FAILED;
            }

            extension.onLoad(plugin, executor);
            loadedExtensions.put(descriptor.id, new LoadedExtension(descriptor.id, descriptor.version, extension, jarFile, descriptor.gitInfo));
            classLoaders.put(descriptor.id, loader);
            plugin.getLogger().info("Loaded Extension: " + descriptor.id + " (v" + descriptor.version + ")");

            triggerAsyncUpdateCheck(plugin, executor, descriptor);
            return LoadResult.SUCCESS;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load extension " + descriptor.id + ": " + e.getMessage());
            close(loader);
            return LoadResult.FAILED;
        }
    }

    private void triggerAsyncUpdateCheck(JavaPlugin plugin, Executor executor, ExtensionDescriptor descriptor) {
        if (descriptor.gitInfo == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    handleUpdate(plugin, descriptor);
                } catch (Exception ex) {
                    plugin.getLogger().severe("Update check failed for " + descriptor.id + ": " + ex.getMessage());
                }
            });
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not schedule update check for " + descriptor.id + ": " + ex.getMessage());
        }
    }

    private void handleUpdate(JavaPlugin plugin, ExtensionDescriptor descriptor) {
        GitInfo git = descriptor.gitInfo;
        boolean updateAvailable;
        String token = resolveToken(plugin, git.provider);
        switch (git.provider.toLowerCase(Locale.ROOT)) {
            case "github" -> updateAvailable = MCExtensionGitHub.checkUpdate(plugin, git.owner, git.repository, descriptor.version, token);
            case "gitlab" -> updateAvailable = MCExtensionGitLab.checkUpdate(plugin, git.owner, git.repository, descriptor.version, token);
            default -> {
                plugin.getLogger().warning("Unknown git provider for extension " + descriptor.id + ": " + git.provider);
                return;
            }
        }

        if (!updateAvailable) {
            return;
        }

        File parentDir = descriptor.file.getParentFile();
        File downloaded = switch (git.provider.toLowerCase(Locale.ROOT)) {
            case "github" -> MCExtensionGitHub.downloadUpdate(plugin, git.owner, git.repository, token, parentDir);
            case "gitlab" -> MCExtensionGitLab.downloadUpdate(plugin, git.owner, git.repository, token, parentDir);
            default -> null;
        };

        if (downloaded == null) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> swapAndReload(plugin, descriptor.id, downloaded));
    }

    private void swapAndReload(JavaPlugin plugin, String id, File downloadedFile) {
        LoadedExtension loaded = loadedExtensions.get(id);
        if (loaded == null) {
            plugin.getLogger().warning("Extension not loaded during swap: " + id);
            return;
        }

        Executor mainThread = command -> Bukkit.getScheduler().runTask(plugin, command);
        disableExtension(plugin, mainThread, id);

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
            loadExtension(plugin, mainThread, target);
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

    private ExtensionDescriptor readDescriptor(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("extension.yml");
            if (entry == null) {
                return null;
            }

            try (InputStream input = jar.getInputStream(entry)) {
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.load(input);
                if (data == null) {
                    return null;
                }

                String id = (String) data.get("name");
                String mainClass = (String) data.get("main");
                String version = String.valueOf(data.getOrDefault("version", "1.0.0"));
                List<String> extDepend = extractStringList(data, "extension", "depend");
                GitInfo gitInfo = extractGitInfo(data.get("git"));

                return new ExtensionDescriptor(id, mainClass, version, extDepend, gitInfo, jarFile);
            }
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractStringList(Map<String, Object> root, String section, String key) {
        if (root == null || !(root.get(section) instanceof Map<?, ?> sectionMap)) {
            return Collections.emptyList();
        }
        Object raw = ((Map<String, Object>) sectionMap).get(key);
        if (raw instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    values.add(String.valueOf(o));
                }
            }
            return values;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private GitInfo extractGitInfo(Object gitBlock) {
        if (!(gitBlock instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        String provider = String.valueOf(map.getOrDefault("provider", "")).trim();
        String owner = String.valueOf(map.getOrDefault("owner", "")).trim();
        String repository = String.valueOf(map.getOrDefault("repository", "")).trim();
        if (provider.isEmpty() || owner.isEmpty() || repository.isEmpty()) {
            return null;
        }
        return new GitInfo(provider, owner, repository);
    }

    private String resolveToken(JavaPlugin plugin, String provider) {
        String envToken = switch (provider.toLowerCase(Locale.ROOT)) {
            case "github" -> System.getenv("USER_GITHUB_TOKEN");
            case "gitlab" -> System.getenv("USER_GITLAB_TOKEN");
            default -> null;
        };
        if (envToken != null && !envToken.isBlank()) {
            return envToken;
        }
        if (plugin.getConfig() != null) {
            String cfg = plugin.getConfig().getString("git.token", null);
            if (cfg != null && !cfg.isBlank()) {
                return cfg;
            }
        }
        return null;
    }

    private boolean checkLicense(JavaPlugin plugin, String id, IMCExtension extension) {
        File extensionFolder = new File(plugin.getDataFolder(), "extensions/libs");
        File extConfigPath = new File(extensionFolder, id + File.separator + "config.yml");
        if (!extConfigPath.exists()) {
            return true;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(extConfigPath);
        String licenseUrl = config.getString("license.url", "");
        String licenseToken = config.getString("license.token", "");

        if (!extension.checkLicense(licenseUrl, licenseToken)) {
            plugin.getLogger().severe("Extension " + id + " failed license verification! Skipping load.");
            return false;
        }
        return true;
    }

    private void closeClassLoader(String id) {
        URLClassLoader loader = classLoaders.remove(id);
        close(loader);
    }

    private void close(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            // keep logging concise and non-fatal
        } finally {
            System.gc();
        }
    }

    private record ExtensionDescriptor(String id, String mainClass, String version, List<String> extensionDepends,
                                       GitInfo gitInfo, File file) {
    }
}
