package io.github.mcengine.mcextension.commands;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tab completer for MCExtension commands.
 */
public class MCExtensionTabCompleter implements TabCompleter {

    private final MCExtensionManager manager;

    public MCExtensionTabCompleter(MCExtensionManager manager) {
        this.manager = manager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mcextension.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> subs = List.of("list", "reload", "reloadall", "disable");
            return filterPrefix(subs, args[0]);
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("reload") || args[0].equalsIgnoreCase("disable"))) {
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
