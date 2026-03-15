package io.github.mcengine.mcextension.commands;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Shared command executor/tab-completer for managing extensions at runtime.
 * Provides basic subcommands: list, reload <id>, reloadall.
 */
public class MCExtensionCommand implements CommandExecutor {

    /** Host plugin instance used for logging and extension lifecycle events. */
    private final JavaPlugin plugin;

    /** Manager responsible for loading, disabling, and reloading extensions. */
    private final MCExtensionManager manager;

    /** Executor used to keep extension lifecycle calls off the main server thread. */
    private final Executor executor;

    /**
     * @param plugin   host plugin for logging and configuration access
     * @param manager  manager handling extension state transitions
     * @param executor executor used to offload extension reload/disable tasks
     */
    public MCExtensionCommand(JavaPlugin plugin, MCExtensionManager manager, Executor executor) {
        this.plugin = plugin;
        this.manager = manager;
        this.executor = executor;
    }

    /**
     * Handles the base command entry point, routing sub-command logic and keeping
     * the execution off the main server thread via the supplied {@link Executor}.
     *
     * @param sender  command issuer
     * @param command command metadata
     * @param label   command alias used
     * @param args    subcommand arguments
     * @return true when the command was handled
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mcextension.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to run this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "list" -> handleList(sender);
            case "reload" -> handleReload(sender, args);
            case "reloadall" -> handleReloadAll(sender);
            case "disable" -> handleDisable(sender, args);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        /**
         * Lists currently loaded extensions, depending on {@link MCExtensionManager#getLoadedExtensions()}.
         *
         * @param sender command issuer
         */
        Map<String, String> loaded = manager.getLoadedExtensions();
        if (loaded.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No extensions loaded.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "Loaded extensions (id: version):");
        loaded.forEach((id, version) -> sender.sendMessage(ChatColor.AQUA + " - " + id + ": " + version));
    }

    /**
     * Reloads a target extension through the manager/executor pair while preventing I/O on the main thread.
     *
     * @param sender command issuer
     * @param args   command arguments
     */
    private void handleReload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + "reload <id>");
            return;
        }
        String id = args[1];
        boolean success = manager.reloadExtension(plugin, executor, id);
        if (success) {
            sender.sendMessage(ChatColor.GREEN + "Reloaded extension: " + id);
        } else {
            sender.sendMessage(ChatColor.RED + "Extension not found or failed to reload: " + id);
        }
    }

    /**
     * Reloads every extension without blocking the main thread, leveraging the shared manager/executor.
     *
     * @param sender command issuer
     */
    private void handleReloadAll(CommandSender sender) {
        manager.reloadAllExtensions(plugin, executor);
        sender.sendMessage(ChatColor.GREEN + "Reloaded all extensions.");
    }

    /**
     * Disables a single extension, forwarding the request to the manager while keeping the executor involved.
     *
     * @param sender command issuer
     * @param args   command arguments
     */
    private void handleDisable(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + "disable <id>");
            return;
        }
        String id = args[1];
        boolean success = manager.disableExtension(plugin, executor, id);
        if (success) {
            sender.sendMessage(ChatColor.GREEN + "Disabled extension: " + id);
        } else {
            sender.sendMessage(ChatColor.RED + "Extension not found or failed to disable: " + id);
        }
    }

    /**
     * Sends the usage instructions for the admin command and highlights available subcommands.
     *
     * @param sender command issuer
     * @param label  alias used for the command
     */
    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " list" + ChatColor.GRAY + " - Show loaded extensions");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " reload <id>" + ChatColor.GRAY + " - Reload a specific extension");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " reloadall" + ChatColor.GRAY + " - Reload all extensions");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " disable <id>" + ChatColor.GRAY + " - Disable a specific extension");
    }
}
