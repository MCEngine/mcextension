package io.github.mcengine.mcextension.util.manager;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Resolves git provider tokens from environment or plugin config.
 */
public final class ResolveToken {
    private ResolveToken() {}

    /**
     * Lookup order: provider-specific env, provider-specific config, generic config.
     *
     * @param plugin   host plugin with config
     * @param provider git provider id (github/gitlab)
     * @return token string or null when not found
     */
    public static String invoke(JavaPlugin plugin, String provider) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);

        String envToken = switch (normalizedProvider) {
            case "github" -> System.getenv("USER_GITHUB_TOKEN");
            case "gitlab" -> System.getenv("USER_GITLAB_TOKEN");
            default -> null;
        };
        if (envToken != null && !envToken.isBlank()) {
            return envToken;
        }

        if (plugin.getConfig() != null) {
            String providerKey = switch (normalizedProvider) {
                case "github" -> "git.github.token";
                case "gitlab" -> "git.gitlab.token";
                default -> null;
            };
            if (providerKey != null) {
                String providerToken = plugin.getConfig().getString(providerKey, null);
                if (providerToken != null && !providerToken.isBlank()) {
                    return providerToken;
                }
            }

            String generic = plugin.getConfig().getString("git.token", null);
            if (generic != null && !generic.isBlank()) {
                return generic;
            }
        }

        return null;
    }
}
