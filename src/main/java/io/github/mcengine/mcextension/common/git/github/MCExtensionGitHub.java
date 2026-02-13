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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class MCExtensionGitHub {

    private MCExtensionGitHub() {}

    public static boolean checkUpdate(JavaPlugin plugin, String owner, String repository, String currentVersion, String token) {
        try {
            if (owner == null || owner.isBlank() || repository == null || repository.isBlank()) {
                plugin.getLogger().warning("GitHub update check skipped: owner/repository not configured");
                return false;
            }
            return MCUtil.compareVersion("github", currentVersion, owner, repository, token);
        } catch (Exception e) {
            plugin.getLogger().warning("GitHub update check failed: " + e.getMessage());
            return false;
        }
    }

    public static boolean downloadUpdate(JavaPlugin plugin, String owner, String repository, String token, File destination) {
        try {
            if (owner == null || owner.isBlank() || repository == null || repository.isBlank()) {
                plugin.getLogger().warning("GitHub download skipped: owner/repository not configured");
                return false;
            }
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repository + "/releases/latest";
            String body = fetchString(apiUrl, token);
            if (body == null) {
                return false;
            }
            String assetUrl = findJarUrl(body, "browser_download_url");
            if (assetUrl == null) {
                plugin.getLogger().warning("No downloadable jar asset found for GitHub release " + owner + "/" + repository);
                return false;
            }
            return downloadToFile(assetUrl, token, destination);
        } catch (Exception e) {
            plugin.getLogger().warning("GitHub download failed: " + e.getMessage());
            return false;
        }
    }

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

    private static HttpURLConnection open(String url, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "MCExtension-Updater");
        if (token != null && !token.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

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
}
