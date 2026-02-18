package io.github.mcengine.mcextension.util.manager;

import java.io.IOException;
import java.net.URLClassLoader;

public final class Close {
    private Close() {}

    public static void invoke(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException ignored) {
            // keep logging concise and non-fatal
        } finally {
            System.gc();
        }
    }
}
