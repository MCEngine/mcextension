package io.github.mcengine.mcextension.util.manager;

import java.io.IOException;
import java.net.URLClassLoader;

/**
 * Safely closes a URLClassLoader while swallowing non-fatal IO errors.
 */
public final class Close {
    private Close() {}

    /**
     * Closes the provided classloader if non-null.
     *
     * @param loader classloader to close
     */
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
