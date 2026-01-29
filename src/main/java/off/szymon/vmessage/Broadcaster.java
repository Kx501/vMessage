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

package off.szymon.vmessage;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;
import off.szymon.vmessage.compatibility.LuckPermsCompatibilityProvider;
import off.szymon.vmessage.config.ConfigManager;
import off.szymon.vmessage.onebot.OneBotClient;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import com.velocitypowered.api.scheduler.ScheduledTask;

public class Broadcaster {

    private final HashMap<String,String> serverAliases; // Server name, Server alias
    private final LuckPermsCompatibilityProvider lp;
    private final HashMap<String,String> metaPlaceholders; // Placeholder, Meta key
    private OneBotClient oneBotClient;
    // Pending leave messages (for delay and rejoin filtering)
    private final ConcurrentHashMap<String, ScheduledTask> pendingLeaveTasks = new ConcurrentHashMap<>();
    // Pattern for QQ at from game chat: @ + 5-11 digits, e.g. @1234567
    // Note: this only supports pure numeric QQ ids; texts like test@1234567.com may be partially treated as QQ at.
    private static final Pattern QQ_AT_PATTERN = Pattern.compile("@([0-9]{5,11})");

    public Broadcaster() {
        serverAliases = new HashMap<>();
        reloadAliases();

        /* LuckPerms */
        lp = VMessagePlugin.get().getLuckPermsCompatibilityProvider();

        metaPlaceholders = new HashMap<>();
        reloadMetaPlaceholders();

        /* OneBot */
        if (ConfigManager.get().getConfig().getOnebot().getEnabled()) {
            oneBotClient = new OneBotClient();
        }
    }

    public void message(Player player, String message) {
        if (!ConfigManager.get().getConfig().getMessages().getChat().getEnabled()) return;

        String msg = ConfigManager.get().getConfig().getMessages().getChat().getFormat();

        //noinspection OptionalGetWithoutIsPresent
        msg = msg
                .replace("%player%", player.getUsername())
                .replace("%message%", escapeMiniMessage(message))
                .replace("%server%", parseAlias(player.getCurrentServer().get().getServerInfo().getName()));
        if (lp != null) {
            LuckPermsCompatibilityProvider.PlayerData data = lp.getMetaData(player);
            msg = msg
                    .replace("%suffix%", Optional.ofNullable(data.metaData().getSuffix()).orElse(""))
                    .replace("%prefix%", Optional.ofNullable(data.metaData().getPrefix()).orElse(""));

            for (Map.Entry<String,String> entry : metaPlaceholders.entrySet()) {
                String metaValue = Optional.ofNullable(data.metaData().getMetaValue(entry.getValue())).orElse("");
                msg = msg.replace(
                        entry.getKey(),
                        metaValue
                );
            }
        }
        VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(msg));
        
        // Send to OneBot/QQ group if enabled
        if (oneBotClient != null && ConfigManager.get().getConfig().getOnebot().getForwardToQq().getChat()) {
            String qqMessage = formatMessageForQQ("chat", player.getUsername(), escapeMiniMessage(message), parseAlias(player.getCurrentServer().get().getServerInfo().getName()), null);
            oneBotClient.sendGroupMessage(qqMessage);
        }
    }

    public void join(Player player) {
        if (!ConfigManager.get().getConfig().getMessages().getJoin().getEnabled()) return;
        if (player.hasPermission("vmessage.silent.join")) {
            VMessagePlugin.get().getLogger().info("{} has silent join permission, not broadcasting join message", player.getUsername());
            return;
        }

        String msg = ConfigManager.get().getConfig().getMessages().getJoin().getFormat();
        //noinspection OptionalGetWithoutIsPresent
        msg = msg
                .replace("%player%", player.getUsername())
                .replace("%server%", parseAlias(player.getCurrentServer().get().getServerInfo().getName()));

        if (lp != null) {
            LuckPermsCompatibilityProvider.PlayerData data = lp.getMetaData(player);
            msg = msg
                    .replace("%suffix%", Optional.ofNullable(data.metaData().getSuffix()).orElse(""))
                    .replace("%prefix%", Optional.ofNullable(data.metaData().getPrefix()).orElse(""));

            for (Map.Entry<String,String> entry : metaPlaceholders.entrySet()) {
                msg = msg.replace(
                        entry.getKey(),
                        Optional.ofNullable(data.metaData().getMetaValue(entry.getValue())).orElse("")
                );
            }
        }
        VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(msg));
        
        // Send to OneBot/QQ group if enabled (with rejoin filtering)
        if (oneBotClient != null && ConfigManager.get().getConfig().getOnebot().getForwardToQq().getJoin()) {
            String playerName = player.getUsername();
            
            // Check if there's a pending leave message (player rejoined during delay)
            ScheduledTask pendingLeave = pendingLeaveTasks.remove(playerName);
            if (pendingLeave != null) {
                // Cancel leave message and filter out join message (fast rejoin)
                pendingLeave.cancel();
                VMessagePlugin.get().getLogger().debug("Cancelled pending leave message for {} and filtered join message due to fast rejoin", playerName);
            } else {
                // Normal join, send message
                String qqMessage = formatMessageForQQ("join", playerName, null, parseAlias(player.getCurrentServer().get().getServerInfo().getName()), null);
                oneBotClient.sendGroupMessage(qqMessage);
            }
        }
    }

    public void leave(Player player) {
        if (!ConfigManager.get().getConfig().getMessages().getLeave().getEnabled()) return;
        if (player.hasPermission("vmessage.silent.leave")) {
            VMessagePlugin.get().getLogger().info("{} has silent leave permission, not broadcasting leave message", player.getUsername());
            return;
        }

        String msg = ConfigManager.get().getConfig().getMessages().getLeave().getFormat();
        String serverName = player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .map(this::parseAlias)
                .orElse(null);

        if (serverName == null) {
            return; // invalid server connection, do not send leave message
        }

        msg = msg
                .replace("%player%", player.getUsername())
                .replace("%server%", serverName);

        if (lp != null) {
            LuckPermsCompatibilityProvider.PlayerData data = lp.getMetaData(player);
            msg = msg
                    .replace("%suffix%", Optional.ofNullable(data.metaData().getSuffix()).orElse(""))
                    .replace("%prefix%", Optional.ofNullable(data.metaData().getPrefix()).orElse(""));

            for (Map.Entry<String,String> entry : metaPlaceholders.entrySet()) {
                msg = msg.replace(
                        entry.getKey(),
                        Optional.ofNullable(data.metaData().getMetaValue(entry.getValue())).orElse("")
                );
            }
        }
        VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(msg));
        
        // Send to OneBot/QQ group if enabled (with delay for rejoin filtering)
        if (oneBotClient != null && ConfigManager.get().getConfig().getOnebot().getForwardToQq().getLeave()) {
            String playerName = player.getUsername();
            int delay = ConfigManager.get().getConfig().getOnebot().getForwardToQq().getLeaveDelay() * 1000;
            
            // Prepare message
            String qqMessage = formatMessageForQQ("leave", playerName, null, serverName, null);
            
            // Delay sending leave message (if player rejoins during delay, this will be cancelled)
            ScheduledTask task = VMessagePlugin.get().getServer().getScheduler()
                .buildTask(VMessagePlugin.get(), () -> {
                    // Check if player rejoined during delay (task might have been cancelled)
                    if (!pendingLeaveTasks.containsKey(playerName)) {
                        return; // Task was cancelled due to rejoin
                    }
                    pendingLeaveTasks.remove(playerName);
                    oneBotClient.sendGroupMessage(qqMessage);
                })
                .delay(delay, TimeUnit.MILLISECONDS)
                .schedule();
            
            pendingLeaveTasks.put(playerName, task);
            VMessagePlugin.get().getLogger().debug("Scheduled leave message for {} with {}ms delay", playerName, delay);
        }
    }

    public void change(Player player, String oldServer) {
        if (!ConfigManager.get().getConfig().getMessages().getChange().getEnabled()) return;
        if (player.hasPermission("vmessage.silent.change")) {
            VMessagePlugin.get().getLogger().info("{} has silent change permission, not broadcasting change message", player.getUsername());
            return;
        }

        String msg = ConfigManager.get().getConfig().getMessages().getChange().getFormat();
        //noinspection OptionalGetWithoutIsPresent
        msg = msg
                .replace("%player%", player.getUsername())
                .replace("%new_server%", parseAlias(player.getCurrentServer().get().getServerInfo().getName()))
                .replace("%old_server%", parseAlias(oldServer));

        if (lp != null) {
            LuckPermsCompatibilityProvider.PlayerData data = lp.getMetaData(player);

            msg = msg
                    .replace("%suffix%", Optional.ofNullable(data.metaData().getSuffix()).orElse(""))
                    .replace("%prefix%", Optional.ofNullable(data.metaData().getPrefix()).orElse(""));


            for (Map.Entry<String,String> entry : metaPlaceholders.entrySet()) {
                String metaValue = Optional.ofNullable(data.metaData().getMetaValue(entry.getValue())).orElse("DEBUG_VAL");
                msg = msg.replace(
                        entry.getKey(),
                        metaValue
                );
            }
        }
        VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(msg));
        
        // Send to OneBot/QQ group if enabled
        if (oneBotClient != null && ConfigManager.get().getConfig().getOnebot().getForwardToQq().getChange()) {
            String newServer = parseAlias(player.getCurrentServer().get().getServerInfo().getName());
            String qqMessage = formatMessageForQQ("change", player.getUsername(), null, newServer, parseAlias(oldServer));
            oneBotClient.sendGroupMessage(qqMessage);
        }
    }

    public void reload() {
        reloadAliases();
        reloadMetaPlaceholders();
    }

    public void reloadAliases() {
        serverAliases.clear();
        Set<Map.Entry<Object, CommentedConfigurationNode>> aliases = ConfigManager.get().getNode("server-aliases").childrenMap().entrySet();
        for (Map.Entry<Object, CommentedConfigurationNode> entry : aliases) {
            serverAliases.put(entry.getKey().toString(),entry.getValue().getString(""));
        }
    }

    public void reloadMetaPlaceholders() {
        metaPlaceholders.clear();
        if (lp != null) {
            Set<Map.Entry<Object, CommentedConfigurationNode>> metas = ConfigManager.get().getNode("luck-perms-meta").childrenMap().entrySet();
            for (Map.Entry<Object, CommentedConfigurationNode> entry : metas) {
                metaPlaceholders.put("&"+entry.getKey().toString()+"&",entry.getValue().getString(""));
            }
        }
    }

    public void broadcast(String message, @Nullable Player player) {
        String msg = ConfigManager.get().getConfig().getCommands().getBroadcast().getFormat();

        if (player != null) {
            if (!ConfigManager.get().getConfig().getCommands().getBroadcast().getAllowMiniMessage()) {
                msg = msg.replace("%message%", MiniMessage.miniMessage().escapeTags(message));
            } else {
                msg = msg.replace("%message%", message);
            }
            msg = msg
                .replace("%player%", player.getUsername())
                .replace("%server%", parseAlias(player.getCurrentServer().get().getServerInfo().getName()));
            if (lp != null) {
                LuckPermsCompatibilityProvider.PlayerData data = lp.getMetaData(player);
                msg = msg
                        .replace("%suffix%", Optional.ofNullable(data.metaData().getSuffix()).orElse(""))
                        .replace("%prefix%", Optional.ofNullable(data.metaData().getPrefix()).orElse(""));

                for (Map.Entry<String,String> entry : metaPlaceholders.entrySet()) {
                    String metaValue = Optional.ofNullable(data.metaData().getMetaValue(entry.getValue())).orElse("");
                    msg = msg.replace(
                            entry.getKey(),
                            metaValue
                    );
                }
            }
        } else {
            msg = msg
                .replace("%message%", message)
                .replace("%player%", "Server")
                .replace("%server%", "Server")
                .replace("%suffix%", "")
                .replace("%prefix%", "");
            for (String key : metaPlaceholders.keySet()) {
                msg = msg.replace(key, "");
            }
        }

        VMessagePlugin.get().getServer().sendMessage(MiniMessage.miniMessage().deserialize(msg));
        
        // Send to OneBot/QQ group if enabled
        if (oneBotClient != null && ConfigManager.get().getConfig().getOnebot().getForwardToQq().getBroadcast()) {
            String qqMessage = formatMessageForQQ("broadcast", player != null ? player.getUsername() : "Server", message, null, null);
            oneBotClient.sendGroupMessage(qqMessage);
        }
    }

    public String parseAlias(String serverName) {
        String output;
        for (Map.Entry<String,String> entry : serverAliases.entrySet()) {
            if (serverName.equalsIgnoreCase(entry.getKey())) {
                output = entry.getValue();
                return output;
            }
        }
        return serverName;
    }

    public HashMap<String, String> getMetaPlaceholders() {
        return metaPlaceholders;
    }

    private String escapeMiniMessage(String input) {
        return ConfigManager.get().getConfig().getMessages().getChat().getAllowMiniMessage() ? input : MiniMessage.miniMessage().escapeTags(input);
    }

    /**
     * Convert in-game @QQ patterns (e.g. @2483654847) into QQ group at CQ codes for OneBot/NapCat.
     * <p>
     * Only pure numeric QQ ids are supported. This may affect strings like "test@1234567.com",
     * whose "@1234567" part will be interpreted as a QQ at.
     */
    private String convertGameAtToQqAt(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        return QQ_AT_PATTERN.matcher(message).replaceAll("[CQ:at,qq=$1]");
    }

    private String formatMessageForQQ(String type, String player, String message, String server, String oldServer) {
        var formatConfig = ConfigManager.get().getConfig().getOnebot().getForwardToQq().getFormat();
        String format;
        
        switch (type) {
            case "chat":
                format = formatConfig.getChat();
                break;
            case "join":
                format = formatConfig.getJoin();
                break;
            case "leave":
                format = formatConfig.getLeave();
                break;
            case "change":
                format = formatConfig.getChange();
                break;
            case "broadcast":
                format = formatConfig.getBroadcast();
                break;
            default:
                return "";
        }
        
        // Replace placeholders
        if (player != null) {
            format = format.replace("%player%", player);
        }
        if (message != null) {
            // Convert in-game @QQ to CQ at codes before sending to QQ group
            String qqMessage = convertGameAtToQqAt(message);
            format = format.replace("%message%", qqMessage);
        }
        if (server != null) {
            format = format.replace("%server%", server);
            format = format.replace("%new_server%", server);
        }
        if (oldServer != null) {
            format = format.replace("%old_server%", oldServer);
        }
        
        // Remove any remaining placeholders
        format = format.replaceAll("%[a-z_]+%", "");
        
        return format;
    }

    public void reloadOneBot() {
        if (ConfigManager.get().getConfig().getOnebot().getEnabled()) {
            // Always recreate to pick up config changes (groupId, apiUrl, accessToken)
            oneBotClient = new OneBotClient();
        } else {
            oneBotClient = null;
        }
    }
}
