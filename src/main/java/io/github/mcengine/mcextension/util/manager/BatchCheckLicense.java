package io.github.mcengine.mcextension.util.manager;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class BatchCheckLicense {
    private BatchCheckLicense() {}

    /**
     * Scans all loaded descriptors, reads their config.yml, and batches license checks by URL.
     * @param plugin The host plugin
     * @param pendingExtensions List of descriptors waiting to be loaded
     * @return A map of extension IDs to their license validation status (true = valid, false = invalid)
     */
    public static Map<String, Boolean> invokeAsync(JavaPlugin plugin, List<MCExtensionManager.ExtensionDescriptor> pendingExtensions) {
        Map<String, Boolean> results = new ConcurrentHashMap<>();
        Map<String, List<LicenseData>> groupedByUrl = new HashMap<>();
        File extensionFolder = new File(plugin.getDataFolder(), "extensions/libs");

        for (MCExtensionManager.ExtensionDescriptor descriptor : pendingExtensions) {
            String id = descriptor.id();
            File extConfigPath = new File(extensionFolder, id + File.separator + "config.yml");

            if (!extConfigPath.exists()) {
                results.put(id, true);
                continue;
            }

            YamlConfiguration config = YamlConfiguration.loadConfiguration(extConfigPath);
            String url = config.getString("license.url", "");
            String token = config.getString("license.token", "");

            if (url.isEmpty()) {
                results.put(id, true);
            } else {
                groupedByUrl.computeIfAbsent(url, k -> new ArrayList<>()).add(new LicenseData(id, url, token));
            }
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        List<CompletableFuture<Void>> futures = groupedByUrl.entrySet().stream()
                .map(entry -> buildRequest(plugin, client, entry.getKey(), entry.getValue(), results))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return results;
    }

    private static CompletableFuture<Void> buildRequest(JavaPlugin plugin, HttpClient client, String url, List<LicenseData> batch, Map<String, Boolean> results) {
        JsonArray jsonArray = new JsonArray();
        for (LicenseData data : batch) {
            JsonObject obj = new JsonObject();
            obj.addProperty("extensionId", data.extensionId());
            obj.addProperty("token", data.token());
            jsonArray.add(obj);
        }
        String jsonPayload = jsonArray.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        boolean parsed = false;
                        try {
                            JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
                            for (Map.Entry<String, JsonElement> entry : payload.entrySet()) {
                                JsonElement element = entry.getValue();
                                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
                                    results.put(entry.getKey(), element.getAsBoolean());
                                }
                            }
                            parsed = true;
                        } catch (Exception ignored) {
                            // Continue, fallback to marking batch as valid
                        }
                        if (!parsed) {
                            for (LicenseData data : batch) {
                                results.put(data.extensionId(), true);
                            }
                        }
                    } else {
                        plugin.getLogger().warning("Batch license check returned " + response.statusCode());
                        batch.forEach(data -> results.put(data.extensionId(), false));
                    }
                })
                .exceptionally(e -> {
                    plugin.getLogger().warning("Failed to perform batch license check: " + e.getMessage());
                    batch.forEach(data -> results.put(data.extensionId(), true));
                    return null;
                });
    }
}
