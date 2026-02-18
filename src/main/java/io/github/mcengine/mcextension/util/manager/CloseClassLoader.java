package io.github.mcengine.mcextension.util.manager;

import java.net.URLClassLoader;
import java.util.Map;

public final class CloseClassLoader {
    private CloseClassLoader() {}

    public static void invoke(String id, Map<String, URLClassLoader> classLoaders) {
        URLClassLoader loader = classLoaders.remove(id);
        Close.invoke(loader);
    }
}
