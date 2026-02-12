package io.github.mcengine.mcextension.common;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Logger;

public class MCExtensionLogger {
    private final String pluginName;
    private final String extensionName;
    private final Logger logger;

    public MCExtensionLogger(String pluginName, String extensionName) {
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.extensionName = Objects.requireNonNull(extensionName, "extensionName");
        this.logger = JavaPlugin.getProvidingPlugin(MCExtensionLogger.class).getLogger();
    }

    public void info(String msg) {
        logger.info(format(msg));
    }

    public void warn(String msg) {
        logger.warning(format(msg));
    }

    public void error(String msg) {
        logger.severe(format(msg));
    }

    private String format(String msg) {
        return "[" + pluginName + "] [" + extensionName + "] " + msg;
    }
}
