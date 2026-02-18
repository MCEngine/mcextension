package io.github.mcengine.mcextension.util.manager;

import java.net.URLClassLoader;
import java.util.Map;

/**
 * Removes and closes a classloader associated with an extension id.
 */
public final class CloseClassLoader {
    private CloseClassLoader() {}

    /**
     * Removes the classloader from the map and closes it.
     *
     * @param id           extension id
     * @param classLoaders map of id -> loader
     */
    public static void invoke(String id, Map<String, URLClassLoader> classLoaders) {
        URLClassLoader loader = classLoaders.remove(id);
        Close.invoke(loader);
    }
}
