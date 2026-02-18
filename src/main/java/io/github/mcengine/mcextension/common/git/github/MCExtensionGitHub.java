package io.github.mcengine.mcextension.common.git.github;

import io.github.mcengine.mcutil.MCUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * GitHub utility for checking and downloading extension updates.
 */
public final class MCExtensionGitHub {

    private MCExtensionGitHub() {}

    /**
     * Compares current extension version against the latest GitHub release.
     *
     * @param plugin          host plugin for logging
     * @param owner           repository owner/org
     * @param repository      repository name
     * @param currentVersion  current extension version
     * @param token           optional auth token for private repos
     * @return true if newer version is available; false otherwise
     */
    public static boolean checkUpdate(JavaPlugin plugin, String owner, String repository, String currentVersion, String token) {
        try {
            if (owner == null || owner.isBlank() || repository == null || repository.isBlank()) {
                plugin.getLogger().warning("GitHub update check skipped: owner/repository not configured for repo " + owner + "/" + repository);
                return false;
            }
            return MCUtil.compareVersion("github", currentVersion, owner, repository, token);
        } catch (Exception e) {
            plugin.getLogger().warning("GitHub update check failed for " + owner + "/" + repository + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Downloads the latest release asset (jar) for the repository.
     *
     * @param plugin     host plugin for logging
     * @param owner      repository owner/org
     * @param repository repository name
     * @param token      optional auth token for private repos
     * @param parentDir  destination directory to place the jar
     * @return downloaded file or null when unavailable
     */
    public static File downloadUpdate(JavaPlugin plugin, String owner, String repository, String token, File parentDir) {
        try {
            if (owner == null || owner.isBlank() || repository == null || repository.isBlank()) {
                plugin.getLogger().warning("GitHub download skipped: owner/repository not configured for repo " + owner + "/" + repository);
                return null;
            }
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repository + "/releases/latest";
            String body = fetchString(apiUrl, token);
            if (body == null) {
                plugin.getLogger().warning("GitHub download skipped: no release response for " + owner + "/" + repository);
                return null;
            }
            String assetUrl = findJarUrl(body, "browser_download_url");
            if (assetUrl == null) {
                String tag = findSimpleValue(body, "tag_name");
                String assetName = findAssetName(body);
                if (tag != null && assetName != null && assetName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    assetUrl = "https://github.com/" + owner + "/" + repository + "/releases/download/" + tag + "/" + assetName;
                    plugin.getLogger().info("GitHub release asset fallback resolved for " + owner + "/" + repository + " -> " + assetUrl);
                } else {
                    assetUrl = findAnyJarUrl(body);
                    if (assetUrl == null) {
                        plugin.getLogger().warning("No downloadable jar asset found for GitHub release " + owner + "/" + repository + "; tag=" + tag + ", asset=" + assetName);
                        return null;
                    } else {
                        plugin.getLogger().info("GitHub release asset fallback (any jar) resolved for " + owner + "/" + repository + " -> " + assetUrl);
                    }
                }
            }
            String assetFileName = fileNameFromUrl(assetUrl, repository + ".jar");
            File destination = new File(parentDir, assetFileName);
            if (downloadToFile(assetUrl, token, destination)) {
                return destination;
            }
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("GitHub download failed for " + owner + "/" + repository + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetches string content from a URL with optional bearer token, following one redirect manually.
     *
     * @param url   target URL
     * @param token optional bearer token
     * @return response body as string
     */
    private static String fetchString(String url, String token) throws IOException {
        HttpURLConnection conn = open(url, token);
        if (conn.getResponseCode() >= 300 && conn.getResponseCode() < 400) {
            String location = conn.getHeaderField("Location");
            if (location != null) {
                conn.disconnect();
                conn = open(location, token);
            }
        }
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Downloads from URL to destination (via temp file) with optional bearer token.
     *
     * @param url         source URL
     * @param token       optional bearer token
     * @param destination destination file path
     * @return true when download succeeds
     */
    private static boolean downloadToFile(String url, String token, File destination) throws IOException {
        HttpURLConnection conn = open(url, token);
        if (conn.getResponseCode() >= 300 && conn.getResponseCode() < 400) {
            String location = conn.getHeaderField("Location");
            if (location != null) {
                conn.disconnect();
                conn = open(location, token);
            }
        }
        destination.getParentFile().mkdirs();
        File temp = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
            in.transferTo(out);
        } finally {
            conn.disconnect();
        }
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * Opens an HTTP connection with standard headers and optional bearer token.
     *
     * @param url   target URL
     * @param token optional bearer token
     * @return configured connection
     */
    private static HttpURLConnection open(String url, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "MCExtension-Updater");
        if (token != null && !token.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

    /**
     * Finds a jar URL by searching for a key and ensuring the value ends with .jar.
     *
     * @param body response body to scan
     * @param key  JSON key to search for
     * @return jar URL or null when not found
     */
    private static String findJarUrl(String body, String key) {
        String lower = body.toLowerCase(Locale.ROOT);
        String search = key.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(search);
        while (idx >= 0) {
            int start = body.indexOf('"', idx + search.length());
            if (start < 0) break;
            int end = body.indexOf('"', start + 1);
            if (end < 0) break;
            String url = body.substring(start + 1, end);
            if (url.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return url;
            }
            idx = lower.indexOf(search, end);
        }
        return null;
    }

    /**
     * Derives a filename from URL path, falling back to default when missing.
     *
     * @param url         the URL to derive the filename from
     * @param defaultName the default filename to use if none is found
     * @return derived filename
     */
    private static String fileNameFromUrl(String url, String defaultName) {
        try {
            String path = URI.create(url).getPath();
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < path.length()) {
                String name = path.substring(slash + 1);
                if (!name.isBlank()) {
                    return name;
                }
            }
            return defaultName;
        } catch (Exception e) {
            return defaultName;
        }
    }

    /**
     * Scans the body for any URL ending with .jar.
     *
     * @param body response body to scan
     * @return jar URL or null when not found
     */
    private static String findAnyJarUrl(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("http");
        while (idx >= 0) {
            int end = lower.indexOf(".jar", idx);
            if (end > idx) {
                // include protocol through .jar
                int space = lower.indexOf('"', end);
                int newline = lower.indexOf('\n', end);
                int stop = Math.min(space > 0 ? space : lower.length(), newline > 0 ? newline : lower.length());
                String url = body.substring(idx, stop).replace("\\", "").replace("\"", "").trim();
                if (url.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    return url;
                }
            }
            idx = lower.indexOf("http", idx + 1);
        }
        return null;
    }

    /**
     * Extracts a simple quoted string value by key name.
     *
     * @param body response body to scan
     * @param key  key to search for
     * @return value string or null when missing
     */
    private static String findSimpleValue(String body, String key) {
        String lower = body.toLowerCase(Locale.ROOT);
        String search = '"' + key.toLowerCase(Locale.ROOT) + '"';
        int idx = lower.indexOf(search);
        if (idx < 0) {
            return null;
        }
        int start = body.indexOf('"', idx + search.length());
        if (start < 0) {
            return null;
        }
        int end = body.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return body.substring(start + 1, end);
    }

    /**
     * Attempts to find a jar asset name within a GitHub release assets block.
     *
     * @param body release JSON body
     * @return asset name ending with .jar or null
     */
    private static String findAssetName(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        int assetsIdx = lower.indexOf("\"assets\"");
        if (assetsIdx < 0) {
            return null;
        }
        int nameIdx = lower.indexOf("\"name\"", assetsIdx);
        while (nameIdx >= 0) {
            int start = body.indexOf('"', nameIdx + "\"name\"".length());
            if (start < 0) {
                return null;
            }
            int end = body.indexOf('"', start + 1);
            if (end < 0) {
                return null;
            }
            String value = body.substring(start + 1, end);
            if (value.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return value;
            }
            nameIdx = lower.indexOf("\"name\"", end);
        }
        return null;
    }
}
