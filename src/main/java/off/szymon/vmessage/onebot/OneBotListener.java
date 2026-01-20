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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class OneBotListener {

    private Server server;
    private final Gson gson;
    private final ProxyServer proxyServer;

    public OneBotListener() {
        this.gson = new Gson();
        this.proxyServer = VMessagePlugin.get().getServer();
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
        start();
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

            try {
                // Verify token if configured
                // Use accessTokenCallback only
                var config = ConfigManager.get().getConfig().getOnebot();
                String configuredToken = config.getAccessTokenCallback();
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
                        response.getWriter().write("Unauthorized");
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
                    response.getWriter().write("OK");
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
                        response.getWriter().write("OK");
                        return;
                    }
                }

                // Check if forwarding to game is enabled
                if (!ConfigManager.get().getConfig().getOnebot().getForwardToGame()) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("OK");
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
                    
                    // Format message using configured format
                    String format = config.getFormat().getToGame();
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
                response.getWriter().write("OK");
            } catch (Exception e) {
                VMessagePlugin.get().getLogger().warn("Error processing OneBot callback: {}", e.getMessage());
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("ERROR");
            }
        }

        private String extractMessageText(JsonObject event) {
            // Try to get raw_message first (plain text)
            if (event.has("raw_message")) {
                return event.get("raw_message").getAsString();
            }

            // Otherwise, parse message array
            if (event.has("message") && event.get("message").isJsonArray()) {
                JsonArray messageArray = event.get("message").getAsJsonArray();
                StringBuilder text = new StringBuilder();

                for (int i = 0; i < messageArray.size(); i++) {
                    JsonObject segment = messageArray.get(i).getAsJsonObject();
                    String type = segment.has("type") ? segment.get("type").getAsString() : "";

                    if ("text".equals(type) && segment.has("data")) {
                        JsonObject data = segment.get("data").getAsJsonObject();
                        if (data.has("text")) {
                            text.append(data.get("text").getAsString());
                        }
                    }
                }

                return text.toString();
            }

            return null;
        }
    }
}
