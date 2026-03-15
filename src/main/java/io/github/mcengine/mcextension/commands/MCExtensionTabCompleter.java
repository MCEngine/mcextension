package io.github.mcengine.mcextension.commands;

import io.github.mcengine.mcextension.common.MCExtensionManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides context-aware tab completion for {@code /mcextension} admin commands.
 * <p>
 * This completer filters subcommand keywords and dynamically exposes loaded extension IDs
 * while respecting admin permissions so suggestions stay narrow and safe on high-scale servers.
 * </p>
 */
public class MCExtensionTabCompleter implements TabCompleter {

    /**
     * Manager responsible for tracking loaded extensions used to offer ID suggestions.
     */
    private final MCExtensionManager manager;

    /**
     * @param manager manager supplying the registry of loaded extensions for ID-based completion
     */
    public MCExtensionTabCompleter(MCExtensionManager manager) {
        this.manager = manager;
    }

    /**
     * Provides contextual completion for admin-only subcommands and extension IDs.
     * <p>
     * This method first checks admin permissions, then offers subcommands when only one argument
     * was typed, and finally exposes matching extension IDs for reload/disable operations.
     * </p>
     *
     * @param sender  command sender
     * @param command command object being tab completed
     * @param alias   alias used for the command
     * @param args    current argument array being completed
     * @return filtered suggestions or an empty list when no completion is possible
     */
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

    /**
     * Returns the subset of {@code options} that start with {@code prefix}, case insensitively.
     *
     * @param options candidate strings to filter
     * @param prefix  user-typed prefix
     * @return matching subset (or original list when prefix is blank)
     */
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
