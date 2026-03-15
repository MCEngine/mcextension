package io.github.mcengine.mcextension.common;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Simple logger wrapper that prefixes messages with plugin and extension names to keep
 * logging consistent across every extension.
 */
public class MCExtensionLogger {
    /** Host plugin display name used as the first prefix in every log entry. */
    private final String pluginName;

    /** Extension display name used as the second prefix in every log entry. */
    private final String extensionName;

    /** Bukkit logger instance that actually emits the formatted text. */
    private final Logger logger;

    /**
     * Constructs a logger for a specific plugin/extension pair.
     *
     * @param pluginName     host plugin name
     * @param extensionName  extension name
     */
    public MCExtensionLogger(String pluginName, String extensionName) {
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.extensionName = Objects.requireNonNull(extensionName, "extensionName");
        this.logger = JavaPlugin.getProvidingPlugin(MCExtensionLogger.class).getLogger();
    }

    /**
     * Logs an info-level message with prefixes.
     *
     * @param msg message content
     */
    public void info(String msg) {
        logger.info(format(msg));
    }

    /**
     * Logs a warning-level message with prefixes.
     *
     * @param msg message content
     */
    public void warn(String msg) {
        logger.warning(format(msg));
    }

    /**
     * Logs an error-level message with prefixes.
     *
     * @param msg message content
     */
    public void error(String msg) {
        logger.severe(format(msg));
    }

    /**
     * Formats a message with plugin/extension prefixes.
     *
     * @param msg raw message
     * @return formatted message
     */
    private String format(String msg) {
        return "[" + pluginName + "] [" + extensionName + "] " + msg;
    }
}
