package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.api.IMCExtension;
import io.github.mcengine.mcextension.common.MCExtensionManager;
import io.github.mcengine.mcextension.common.MCExtensionManager.LoadResult;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads an extension jar, validates dependencies/license, and registers it with the manager.
 */
public final class LoadExtension {
    /**
     * Static utility class meant to prevent instantiation.
     */
    private LoadExtension() {}

    /**
     * Loads the given jar, instantiates its {@code IMCExtension}, registers loaders, and triggers update check.
     *
     * @param plugin          host plugin used for logging and config
     * @param executor        executor for extension tasks
     * @param jarFile         extension jar
     * @param loadedExtensions registry of loaded extensions
     * @param classLoaders    registry of classloaders
     * @param manager         owning manager
     * @return load result indicating success/failure/waiting for deps
     * @throws IOException when jar access or descriptor parsing fails
     * @throws ReflectiveOperationException when reflective class loading or instantiation fails
     */
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
            Object rawInstance = clazz.getDeclaredConstructor().newInstance();

            IMCExtension extension;
            if (IMCExtension.class.isAssignableFrom(clazz)) {
                extension = (IMCExtension) rawInstance;
            } else {
                extension = adaptRelocatedIMCExtension(rawInstance, plugin);
                if (extension == null) {
                    plugin.getLogger().severe("Main class does not implement IMCExtension (even relocated): " + descriptor.mainClass());
                    Close.invoke(loader);
                    return LoadResult.FAILED;
                }
            }

            if (extension == null) {
                plugin.getLogger().severe("Failed to create IMCExtension instance: " + descriptor.mainClass());
                Close.invoke(loader);
                return LoadResult.FAILED;
            }

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

    /**
     * Allows extensions that shaded or relocated {@code IMCExtension} to be adapted via reflection.
     * This method bridges binary compatibility by matching the relocated methods (onLoad/onDisable/checkUpdate/checkLicense)
     * and wrapping them in our {@link IMCExtension} contract so the manager can interact uniformly.
     *
     * @param instance raw extension instance whose API may have been relocated
     * @param plugin   host plugin for logging
     * @return adapter implementing IMCExtension, or null when the adapted contract still does not fit
     */
    private static IMCExtension adaptRelocatedIMCExtension(Object instance, JavaPlugin plugin) {
        Class<?> clazz = instance.getClass();

        Method onLoad = findCompatibleMethod(clazz, "onLoad", JavaPlugin.class, Executor.class);
        Method onDisable = findCompatibleMethod(clazz, "onDisable", JavaPlugin.class, Executor.class);
        Method checkUpdate = findCompatibleBooleanMethod(clazz, "checkUpdate", String.class, String.class);
        Method checkLicense = findCompatibleBooleanMethod(clazz, "checkLicense", String.class, String.class);

        if (onLoad == null && onDisable == null) {
            return null;
        }

        plugin.getLogger().info("Adapting relocated IMCExtension for class: " + clazz.getName());

        return new IMCExtension() {
            @Override
            public void onLoad(JavaPlugin plugin, Executor executor) {
                if (onLoad != null) {
                    try {
                        onLoad.invoke(instance, plugin, executor);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Failed to invoke relocated onLoad: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onDisable(JavaPlugin plugin, Executor executor) {
                if (onDisable != null) {
                    try {
                        onDisable.invoke(instance, plugin, executor);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Failed to invoke relocated onDisable: " + e.getMessage());
                    }
                }
            }

            @Override
            public boolean checkUpdate(String url, String token) {
                if (checkUpdate != null) {
                    try {
                        Object result = checkUpdate.invoke(instance, url, token);
                        if (result instanceof Boolean bool) {
                            return bool;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().severe("Failed to invoke relocated checkUpdate: " + e.getMessage());
                    }
                }
                return IMCExtension.super.checkUpdate(url, token);
            }

            @Override
            public boolean checkLicense(String url, String token) {
                if (checkLicense != null) {
                    try {
                        Object result = checkLicense.invoke(instance, url, token);
                        if (result instanceof Boolean bool) {
                            return bool;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().severe("Failed to invoke relocated checkLicense: " + e.getMessage());
                    }
                }
                return IMCExtension.super.checkLicense(url, token);
            }
        };
    }

    /**
     * Searches for a method by name and parameter types, returning the first matching reflection handle.
     *
     * @param clazz          candidate class to search
     * @param name           method name to match
     * @param expectedParams expected parameter type sequence
     * @return compatible method or null when none matches
     */
    private static Method findCompatibleMethod(Class<?> clazz, String name, Class<?>... expectedParams) {
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != expectedParams.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < params.length; i++) {
                if (!expectedParams[i].isAssignableFrom(params[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    /**
     * Finds a compatible boolean-returning method with the specified signature.
     *
     * @param clazz          target class to inspect
     * @param name           method name to match
     * @param expectedParams expected params to match
     * @return method returning boolean or null when none matches
     */
    private static Method findCompatibleBooleanMethod(Class<?> clazz, String name, Class<?>... expectedParams) {
        Method method = findCompatibleMethod(clazz, name, expectedParams);
        if (method == null) {
            return null;
        }
        Class<?> returnType = method.getReturnType();
        if (returnType.equals(boolean.class) || returnType.equals(Boolean.class)) {
            return method;
        }
        return null;
    }

    /**
     * Reads extension.yml from the jar to build an {@link MCExtensionManager.ExtensionDescriptor}.
     *
     * @param jarFile extension jar
     * @return descriptor or null when missing/invalid
     */
    public static MCExtensionManager.ExtensionDescriptor readDescriptor(File jarFile) {
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
