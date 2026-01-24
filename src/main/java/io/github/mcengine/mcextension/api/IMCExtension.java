package io.github.mcengine.mcextension.api;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.Executor;

/**
 * The core interface that all Extensions must implement.
 * <p>
 * This interface defines the lifecycle methods and identity of an extension.
 * Extensions are loaded by the {@code ExtensionManager} relative to the host plugin.
 * </p>
 */
public interface IMCExtension {

    /**
     * Called when the extension is loaded and enabled.
     * <p>
     * Use this method to register listeners, commands, or initialize logic.
     * The host plugin instance is provided to allow registration with the Bukkit API.
     * The provided Executor allows for safe task execution (compatible with Folia/Paper/Spigot).
     * </p>
     *
     * @param plugin   The host JavaPlugin that loaded this extension.
     * @param executor The executor for handling asynchronous tasks or platform-safe scheduling.
     */
    default void onLoad(JavaPlugin plugin, Executor executor) {}

    /**
     * Called when the extension is disabled (usually when the server stops or the plugin reloads).
     * <p>
     * Use this method to clean up resources, close connections, or unregister tasks.
     * </p>
     *
     * @param plugin   The host JavaPlugin that is disabling this extension.
     * @param executor The executor for handling cleanup tasks if necessary.
     */
    default void onDisable(JavaPlugin plugin, Executor executor) {}

    /**
     * Checks for updates for this extension.
     * <p>
     * This method is optional. By default, it returns false (no update check).
     * Extensions can override this to implement their own update logic.
     * </p>
     *
     * @param url   The URL endpoint to check for updates.
     * @param token The authentication token (if required for private repos).
     * @return true if an update is available, false otherwise.
     */
    default boolean checkUpdate(String url, String token) {
        return false;
    }
}
