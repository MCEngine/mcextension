package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.api.IMCExtension;
import io.github.mcengine.mcextension.common.MCExtensionManager;
import io.github.mcengine.mcextension.common.MCExtensionManager.LoadResult;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.yaml.snakeyaml.Yaml;

public final class LoadExtension {
    private LoadExtension() {}

    public static LoadResult invoke(JavaPlugin plugin, Executor executor, File jarFile,
                                    Map<String, MCExtensionManager.LoadedExtension> loadedExtensions,
                                    Map<String, URLClassLoader> classLoaders,
                                    MCExtensionManager manager) throws IOException, ReflectiveOperationException {
        MCExtensionManager.ExtensionDescriptor descriptor = readDescriptor(jarFile);
        if (descriptor == null || descriptor.id() == null || descriptor.mainClass() == null) {
            return LoadResult.FAILED;
        }

        for (String dep : descriptor.extensionDepends()) {
            if (!loadedExtensions.containsKey(dep)) {
                return LoadResult.WAITING;
            }
        }

        if (loadedExtensions.containsKey(descriptor.id())) {
            plugin.getLogger().warning("Extension already loaded: " + descriptor.id());
            return LoadResult.FAILED;
        }

        URL[] urls = {jarFile.toURI().toURL()};
        URLClassLoader loader = new URLClassLoader(urls, plugin.getClass().getClassLoader());
        try {
            Class<?> clazz = loader.loadClass(descriptor.mainClass());
            if (!IMCExtension.class.isAssignableFrom(clazz)) {
                plugin.getLogger().severe("Main class does not implement IMCExtension: " + descriptor.mainClass());
                Close.invoke(loader);
                return LoadResult.FAILED;
            }

            IMCExtension extension = (IMCExtension) clazz.getDeclaredConstructor().newInstance();

            if (!CheckLicense.invoke(plugin, descriptor.id(), extension)) {
                Close.invoke(loader);
                return LoadResult.FAILED;
            }

            extension.onLoad(plugin, executor);
            loadedExtensions.put(descriptor.id(), new MCExtensionManager.LoadedExtension(descriptor.id(), descriptor.version(), extension, jarFile, descriptor.gitInfo()));
            classLoaders.put(descriptor.id(), loader);
            plugin.getLogger().info("Loaded Extension: " + descriptor.id() + " (v" + descriptor.version() + ")");

            TriggerAsyncUpdateCheck.invoke(plugin, executor, descriptor, manager, loadedExtensions, classLoaders);
            return LoadResult.SUCCESS;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load extension " + descriptor.id() + ": " + e.getMessage());
            Close.invoke(loader);
            return LoadResult.FAILED;
        }
    }

    private static MCExtensionManager.ExtensionDescriptor readDescriptor(File jarFile) {
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
                List<String> extDepend = ExtractStringList.invoke(data, "extension", "depend");
                MCExtensionManager.GitInfo gitInfo = ExtractGitInfo.invoke(data.get("git"));

                return new MCExtensionManager.ExtensionDescriptor(id, mainClass, version, extDepend, gitInfo, jarFile);
            }
        } catch (IOException e) {
            return null;
        }
    }
}
