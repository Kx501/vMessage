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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import off.szymon.vmessage.VMessagePlugin;
import off.szymon.vmessage.config.ConfigManager;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class OneBotListener {

    private Server server;
    private final Gson gson;
    private final ProxyServer proxyServer;
    private final HttpClient httpClient;
    private final Map<String, String> groupMemberNames = new ConcurrentHashMap<>();

    public OneBotListener() {
        this.gson = new Gson();
        this.proxyServer = VMessagePlugin.get().getServer();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void start() {
        if (!ConfigManager.get().getConfig().getOnebot().getEnabled() ||
            !ConfigManager.get().getConfig().getOnebot().getCallback().getEnabled()) {
            return;
        }

        var callbackConfig = ConfigManager.get().getConfig().getOnebot().getCallback();
        String host = callbackConfig.getHost();
        int port = callbackConfig.getPort();
        String path = callbackConfig.getPath();

        try {
            loadGroupMembers();

            server = new Server(port);
            server.setHandler(new OneBotCallbackHandler(path));
            server.start();

            VMessagePlugin.get().getLogger().info("OneBot callback server started on {}:{}", host, port);
        } catch (Exception e) {
            VMessagePlugin.get().getLogger().error("Failed to start OneBot callback server: {}", e.getMessage(), e);
        }
    }

    public void stop() {
        if (server != null && server.isStarted()) {
            try {
                server.stop();
                VMessagePlugin.get().getLogger().info("OneBot callback server stopped");
            } catch (Exception e) {
                VMessagePlugin.get().getLogger().error("Error stopping OneBot callback server: {}", e.getMessage(), e);
            }
        }
    }

    public void reload() {
        stop();
        groupMemberNames.clear();
        start();
    }

    private static String sanitizeDisplayName(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        // Strip common Unicode bidirectional control characters to prevent visual reordering.
        // Includes: LRE/RLE/PDF/LRO/RLO (U+202A..U+202E),
        //           LRI/RLI/FSI/PDI (U+2066..U+2069),
        //           LRM/RLM (U+200E/U+200F),
        //           ALM (U+061C)
        return s.replaceAll("[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F\\u061C]", "");
    }

    private void loadGroupMembers() {
        var onebotConfig = ConfigManager.get().getConfig().getOnebot();
        if (!onebotConfig.getEnabled()) {
            return;
        }

        String groupId = onebotConfig.getGroupId();
        if (groupId == null || groupId.isEmpty()) {
            // Treat as disabled: nothing to load
            return;
        }

        String apiUrl = onebotConfig.getApiUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            VMessagePlugin.get().getLogger().warn("OneBot api-url is not configured; cannot load group member list");
            return;
        }

        String accessToken = onebotConfig.getAccessTokenSend();

        try {
            String url = apiUrl.endsWith("/") ? apiUrl + "get_group_member_list" : apiUrl + "/get_group_member_list";

            JsonObject requestBody = new JsonObject();
            // OneBot accepts number/string; we send as string to match config type
            requestBody.addProperty("group_id", groupId);
            requestBody.addProperty("no_cache", false);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody), StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10));

            if (accessToken != null && !accessToken.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + accessToken);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                VMessagePlugin.get().getLogger().warn("Failed to load OneBot group member list: HTTP {}", response.statusCode());
                return;
            }

            JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
            String status = jsonResponse.has("status") ? jsonResponse.get("status").getAsString() : "";
            int retcode = jsonResponse.has("retcode") ? jsonResponse.get("retcode").getAsInt() : -1;

            if (!"ok".equals(status) || retcode != 0) {
                String errorMsg = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "Unknown error";
                VMessagePlugin.get().getLogger().warn("Failed to load OneBot group member list: {} (retcode: {})", errorMsg, retcode);
                return;
            }

            if (!jsonResponse.has("data") || !jsonResponse.get("data").isJsonArray()) {
                VMessagePlugin.get().getLogger().warn("Failed to load OneBot group member list: missing data array");
                return;
            }

            JsonArray data = jsonResponse.getAsJsonArray("data");
            for (int i = 0; i < data.size(); i++) {
                JsonObject member = data.get(i).getAsJsonObject();
                if (!member.has("user_id")) {
                    continue;
                }

                String userId = member.get("user_id").isJsonPrimitive() && member.get("user_id").getAsJsonPrimitive().isNumber()
                        ? String.valueOf(member.get("user_id").getAsLong())
                        : member.get("user_id").getAsString();

                String card = member.has("card") ? member.get("card").getAsString() : "";
                String nickname = member.has("nickname") ? member.get("nickname").getAsString() : "";
                String name = (card != null && !card.isEmpty()) ? card : nickname;
                if (onebotConfig.getNicknameClean()) {
                    name = sanitizeDisplayName(name);
                }

                if (name != null && !name.isEmpty()) {
                    groupMemberNames.put(userId, name);
                }
            }

            VMessagePlugin.get().getLogger().debug("Loaded {} OneBot group member names", groupMemberNames.size());
        } catch (Exception e) {
            VMessagePlugin.get().getLogger().warn("Failed to load OneBot group member list: {}", e.getMessage());
        }
    }

    private class OneBotCallbackHandler extends AbstractHandler {
        private final String path;

        public OneBotCallbackHandler(String path) {
            this.path = path;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            if (!request.getMethod().equals("POST") || !target.equals(path)) {
                return;
            }

            baseRequest.setHandled(true);
            
            response.setContentType("application/json; charset=utf-8");

            try {
                // Verify token if configured
                // Use accessTokenCallback only
                var config = ConfigManager.get().getConfig().getOnebot();
                String configuredToken = config.getCallback().getAccessTokenCallback();
                if (configuredToken != null && !configuredToken.isEmpty()) {
                    String requestToken = null;
                    String tokenSource = null;
                    
                    // Check Authorization header first (Bearer token)
                    String authHeader = request.getHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        requestToken = authHeader.substring(7);
                        tokenSource = "Authorization header";
                    } else {
                        // Check X-Token header
                        String xTokenHeader = request.getHeader("X-Token");
                        if (xTokenHeader != null && !xTokenHeader.isEmpty()) {
                            requestToken = xTokenHeader;
                            tokenSource = "X-Token header";
                        } else {
                            // Check X-Access-Token header
                            String xAccessTokenHeader = request.getHeader("X-Access-Token");
                            if (xAccessTokenHeader != null && !xAccessTokenHeader.isEmpty()) {
                                requestToken = xAccessTokenHeader;
                                tokenSource = "X-Access-Token header";
                            } else {
                                // Check URL query parameters
                                String accessTokenParam = request.getParameter("access_token");
                                if (accessTokenParam != null && !accessTokenParam.isEmpty()) {
                                    requestToken = accessTokenParam;
                                    tokenSource = "access_token query parameter";
                                } else {
                                    String tokenParam = request.getParameter("token");
                                    if (tokenParam != null && !tokenParam.isEmpty()) {
                                        requestToken = tokenParam;
                                        tokenSource = "token query parameter";
                                    }
                                }
                            }
                        }
                    }
                    
                    if (requestToken == null || !requestToken.equals(configuredToken)) {
                        // Log error with partial token info (first 3 chars) for debugging
                        String tokenPreview = requestToken != null && requestToken.length() > 3 
                            ? requestToken.substring(0, 3) + "..." 
                            : (requestToken != null ? requestToken : "null");
                        String configTokenPreview = configuredToken.length() > 3 
                            ? configuredToken.substring(0, 3) + "..." 
                            : configuredToken;
                        VMessagePlugin.get().getLogger().warn("OneBot callback token verification failed - Received token from {}: {}, Expected: {}", 
                            tokenSource != null ? tokenSource : "unknown", tokenPreview, configTokenPreview);
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.getWriter().write("{}");
                        return;
                    }
                }

                String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                
                // Debug logging - show request body
                if (config.getDebug()) {
                    String bodyPreview = body.length() > 2000 ? body.substring(0, 2000) + "... (truncated)" : body;
                    VMessagePlugin.get().getLogger().info("OneBot callback request body: {}", bodyPreview);
                }
                
                JsonObject event = JsonParser.parseString(body).getAsJsonObject();

                // Check if it's a group message event
                String postType = event.has("post_type") ? event.get("post_type").getAsString() : "";
                String messageType = event.has("message_type") ? event.get("message_type").getAsString() : "";

                if (!"message".equals(postType) || !"group".equals(messageType)) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{}");
                    return;
                }

                // Filter by group ID if configured
                String configuredGroupId = ConfigManager.get().getConfig().getOnebot().getGroupId();
                if (configuredGroupId != null && !configuredGroupId.isEmpty()) {
                    String eventGroupId = null;
                    if (event.has("group_id")) {
                        if (event.get("group_id").isJsonPrimitive()) {
                            // Handle both number and string types
                            if (event.get("group_id").getAsJsonPrimitive().isNumber()) {
                                eventGroupId = String.valueOf(event.get("group_id").getAsLong());
                            } else {
                                eventGroupId = event.get("group_id").getAsString();
                            }
                        }
                    }
                    
                    if (eventGroupId == null || !eventGroupId.equals(configuredGroupId)) {
                        // Group ID doesn't match, ignore this message
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.getWriter().write("{}");
                        return;
                    }
                }

                // Check if forwarding to game is enabled
                if (!ConfigManager.get().getConfig().getOnebot().getForwardToGame().getEnabled()) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{}");
                    return;
                }

                // Extract message content
                String messageText = extractMessageText(event);

                if (messageText != null && !messageText.isEmpty()) {
                    // Extract sender information
                    String senderName = "";
                    String senderId = "";
                    String senderRole = "";
                    
                    if (event.has("sender") && event.get("sender").isJsonObject()) {
                        JsonObject sender = event.get("sender").getAsJsonObject();
                        if (sender.has("nickname")) {
                            senderName = sender.get("nickname").getAsString();
                        }
                        if (sender.has("user_id")) {
                            senderId = String.valueOf(sender.get("user_id").getAsLong());
                        }
                        if (sender.has("role")) {
                            senderRole = sender.get("role").getAsString();
                        }
                    }

                    // Prefer cached group member name (card > nickname)
                    if (senderId != null && !senderId.isEmpty()) {
                        String cachedName = groupMemberNames.get(senderId);
                        if (cachedName != null && !cachedName.isEmpty()) {
                            senderName = cachedName;
                        }
                    }
                    if (config.getNicknameClean()) {
                        senderName = sanitizeDisplayName(senderName);
                    }
                    
                    // Format message using configured format
                    String format = config.getForwardToGame().getFormat().getToGame();
                    String formattedMessage = format
                            .replace("%message%", messageText)
                            .replace("%sender%", senderName)
                            .replace("%sender_id%", senderId)
                            .replace("%sender_role%", senderRole);
                    
                    // Forward to game asynchronously using Velocity scheduler
                    String finalFormattedMessage = formattedMessage;
                    proxyServer.getScheduler().buildTask(VMessagePlugin.get(), () -> {
                        Component message = MiniMessage.miniMessage().deserialize(finalFormattedMessage);
                        proxyServer.sendMessage(message);
                    }).schedule();
                }

                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("{}");
            } catch (Exception e) {
                VMessagePlugin.get().getLogger().warn("Error processing OneBot callback: {}", e.getMessage());
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("{}");
            }
        }

        private String extractMessageText(JsonObject event) {
            // Prefer structured message segments (NapCat message_format=array)
            JsonArray message = event.getAsJsonArray("message");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < message.size(); i++) {
                JsonObject seg = message.get(i).getAsJsonObject();
                String type = seg.get("type").getAsString();
                JsonObject data = seg.getAsJsonObject("data");

                if ("text".equals(type)) {
                    sb.append(data.get("text").getAsString());
                } else if ("at".equals(type)) {
                    String qq = data.has("qq") ? data.get("qq").getAsString() : "";
                    if (!qq.isEmpty()) {
                        String name = groupMemberNames.getOrDefault(qq, qq);
                        if (ConfigManager.get().getConfig().getOnebot().getNicknameClean()) {
                            name = sanitizeDisplayName(name);
                        }
                        sb.append("@").append(name);
                    }
                } else if ("image".equals(type)) {
                    String url = data.get("url").getAsString();
                    sb.append("[[CICode,url=").append(url).append(",name=媒体消息]]");
                }
            }
            return sb.toString();
        }
    }
}
