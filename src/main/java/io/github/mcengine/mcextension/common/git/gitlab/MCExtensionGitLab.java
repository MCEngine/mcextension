package io.github.mcengine.mcextension.common.git.gitlab;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class MCExtensionGitLab {

    private MCExtensionGitLab() {}

    public static boolean checkUpdate(JavaPlugin plugin, String owner, String repository, String currentVersion, String token) {
        try {
            if (owner == null || owner.isBlank() || repository == null || repository.isBlank()) {
                plugin.getLogger().warning("GitLab update check skipped: owner/repository not configured for repo " + owner + "/" + repository);
                return false;
            }
            return MCUtil.compareVersion("gitlab", currentVersion, owner, repository, token);
        } catch (Exception e) {
            plugin.getLogger().warning("GitLab update check failed for " + owner + "/" + repository + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean downloadUpdate(JavaPlugin plugin, String owner, String repository, String token, File destination) {
        try {
            if (owner == null || owner.isBlank() || repository == null || repository.isBlank()) {
                plugin.getLogger().warning("GitLab download skipped: owner/repository not configured for repo " + owner + "/" + repository);
                return false;
            }
            String project = URLEncoder.encode(owner + "/" + repository, StandardCharsets.UTF_8);
            String apiUrl = "https://gitlab.com/api/v4/projects/" + project + "/releases";
            String body = fetchString(apiUrl, token);
            if (body == null) {
                plugin.getLogger().warning("GitLab download skipped: no release response for " + owner + "/" + repository);
                return false;
            }
            String assetUrl = findJarUrl(body, "direct_asset_url");
            if (assetUrl == null) {
                assetUrl = findJarUrl(body, "url");
            }
            if (assetUrl == null) {
                plugin.getLogger().warning("No downloadable jar asset found for GitLab release " + owner + "/" + repository);
                return false;
            }
            return downloadToFile(assetUrl, token, destination);
        } catch (Exception e) {
            plugin.getLogger().warning("GitLab download failed for " + owner + "/" + repository + ": " + e.getMessage());
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
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "MCExtension-Updater");
        if (token != null && !token.isBlank()) {
            conn.setRequestProperty("PRIVATE-TOKEN", token);
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
