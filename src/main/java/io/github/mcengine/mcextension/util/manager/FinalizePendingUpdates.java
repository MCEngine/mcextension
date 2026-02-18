package io.github.mcengine.mcextension.util.manager;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class FinalizePendingUpdates {
    private FinalizePendingUpdates() {}

    public static void invoke(JavaPlugin plugin, File extensionFolder) {
        File[] pending = extensionFolder.listFiles((dir, name) -> name.endsWith(".update") || name.endsWith(".jar.tmp"));
        if (pending == null || pending.length == 0) {
            return;
        }

        for (File file : pending) {
            String name = file.getName();
            String base = name.endsWith(".update") ? name.substring(0, name.length() - 7)
                    : name.substring(0, name.length() - 8);
            if (!base.endsWith(".jar")) {
                base = base + ".jar";
            }
            File target = new File(extensionFolder, base);
            if (target.exists() && !target.delete()) {
                plugin.getLogger().warning("Could not delete old jar while applying pending update: " + target.getName());
                continue;
            }
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Applied pending update for " + target.getName());
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to apply pending update for " + target.getName() + ": " + e.getMessage());
            }
        }
    }
}
