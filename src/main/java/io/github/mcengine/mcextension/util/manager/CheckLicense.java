package io.github.mcengine.mcextension.util.manager;

import io.github.mcengine.mcextension.api.IMCExtension;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Validates an extension's license by delegating to the extension's own check implementation.
 */
public final class CheckLicense {
    private CheckLicense() {}

    /**
     * Loads the extension-specific config (if present) and runs {@link IMCExtension#checkLicense}.
     *
     * @param plugin    host plugin for data folder and logging
     * @param id        extension id
     * @param extension extension instance to validate
     * @return true if license passes or no config is present; false when validation fails
     */
    public static boolean invoke(JavaPlugin plugin, String id, IMCExtension extension) {
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
}
