/*
 * vMessage
 * Copyright (c) 2025.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * See the LICENSE file in the project root for details.
 */

package off.szymon.vmessage.onebot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.minimessage.MiniMessage;
import off.szymon.vmessage.VMessagePlugin;
import off.szymon.vmessage.config.ConfigManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class OneBotClient {

    private final HttpClient httpClient;
    private final String apiUrl;
    private final String accessToken;
    private final String groupId;
    private final Gson gson;

    public OneBotClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
        
        var config = ConfigManager.get().getConfig().getOnebot();
        this.apiUrl = config.getApiUrl();
        // Use accessTokenSend only
        this.accessToken = config.getAccessTokenSend();
        this.groupId = config.getGroupId();
    }

    public void sendGroupMessage(String message) {
        if (!ConfigManager.get().getConfig().getOnebot().getEnabled()) {
            return;
        }

        if (groupId == null || groupId.isEmpty()) {
            VMessagePlugin.get().getLogger().warn("OneBot group ID is not configured");
            return;
        }

        // Convert MiniMessage to plain text
        String plainText = MiniMessage.miniMessage().stripTags(message);

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("group_id", groupId);
        requestBody.addProperty("message", plainText);
        requestBody.addProperty("auto_escape", false);

        String jsonBody = gson.toJson(requestBody);
        String url = apiUrl.endsWith("/") ? apiUrl + "send_group_msg" : apiUrl + "/send_group_msg";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(10));

        if (accessToken != null && !accessToken.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }

        HttpRequest request = requestBuilder.build();

        // Send asynchronously
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        future.thenAccept(response -> {
            if (response.statusCode() == 200) {
                try {
                    JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                    String status = jsonResponse.has("status") ? jsonResponse.get("status").getAsString() : "";
                    int retcode = jsonResponse.has("retcode") ? jsonResponse.get("retcode").getAsInt() : -1;

                    if ("ok".equals(status) && retcode == 0) {
                        VMessagePlugin.get().getLogger().debug("Successfully sent message to QQ group via OneBot");
                    } else {
                        String errorMsg = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "Unknown error";
                        VMessagePlugin.get().getLogger().warn("Failed to send message to QQ group: {} (retcode: {})", errorMsg, retcode);
                    }
                } catch (Exception e) {
                    VMessagePlugin.get().getLogger().warn("Failed to parse OneBot response: {}", e.getMessage());
                }
            } else {
                VMessagePlugin.get().getLogger().warn("OneBot API returned status code: {}", response.statusCode());
            }
        }).exceptionally(throwable -> {
            VMessagePlugin.get().getLogger().warn("Failed to send message to QQ group via OneBot: {}", throwable.getMessage());
            return null;
        });
    }

    public void reload() {
        // Configuration is read on each call, so no need to cache
    }
}
