package io.github.mcengine.mcextension.commands;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Shared command executor/tab-completer for managing extensions at runtime.
 * Provides basic subcommands: list, reload <id>, reloadall.
 */
public class MCExtensionCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final MCExtensionManager manager;
    private final Executor executor;

    public MCExtensionCommand(JavaPlugin plugin, MCExtensionManager manager, Executor executor) {
        this.plugin = plugin;
        this.manager = manager;
        this.executor = executor;
    }

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
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        Map<String, String> loaded = manager.getLoadedExtensions();
        if (loaded.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No extensions loaded.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "Loaded extensions (id: version):");
        loaded.forEach((id, version) -> sender.sendMessage(ChatColor.AQUA + " - " + id + ": " + version));
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + sender.getName() + " reload <id>");
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

    private void handleReloadAll(CommandSender sender) {
        manager.reloadAllExtensions(plugin, executor);
        sender.sendMessage(ChatColor.GREEN + "Reloaded all extensions.");
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "Usage:");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " list" + ChatColor.GRAY + " - Show loaded extensions");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " reload <id>" + ChatColor.GRAY + " - Reload a specific extension");
        sender.sendMessage(ChatColor.AQUA + "/" + label + " reloadall" + ChatColor.GRAY + " - Reload all extensions");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mcextension.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = List.of("list", "reload", "reloadall");
            return filterPrefix(subs, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return filterPrefix(new ArrayList<>(manager.getLoadedExtensions().keySet()), args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return options;
        }
        List<String> matches = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase().startsWith(prefix.toLowerCase())) {
                matches.add(opt);
            }
        }
        return matches;
    }
}
