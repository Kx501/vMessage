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

@file:Suppress("unused")

package off.szymon.vmessage.config.tree

import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
class MainConfig {
    var messages = MessagesConfig()
    var commands = CommandsConfig()
    @Comment("If you want to use additional LuckPerms meta values you can add them here\nYou can then use them in placeholder format like this: &custom_name& [AMPERSAND not PERCENT]")
    var luckPermsMeta = LuckPermsMetaConfig()
    @Comment("If you want to display a different name for a server you can add it here\nIt will then be used in the messages above instead of the server's actual name")
    var serverAliases = ServerAliasesConfig()
    @Comment("OneBot protocol integration for QQ group message syncing")
    var onebot = OneBotConfig()
    @Comment("Whether to check for updates on plugin startup")
    var checkUpdates = true
    @Comment("This option is here for safety when updating the plugin from an older version\nWARNING: This will overwrite the previous backup (config.yml.bak)")
    var backupConfig = true
}

/* Messages Config */
@ConfigSerializable
class MessagesConfig {
    var chat = ChatConfig()
    var join = JoinConfig()
    var leave = LeaveConfig()
    var change = ChangeConfig()
}

@ConfigSerializable
class ChatConfig {
    var enabled = true
    @Comment("%player% - Player\n%message% - Message\n%server% - Player's Current Server\n%prefix% - LuckPerms Prefix\n%suffix% - LuckPerms Suffix")
    var format = "%prefix% <b>%player%:</b> %message%"
    @Comment("Whether to allow players to use MiniMessage in their messages")
    var allowMiniMessage = false
    @Comment("The message to send when a muted player tries to send a message\n%player% - Player\n%message% - Message\n%server% - Player's Current Server\n%prefix% - LuckPerms Prefix\n%suffix% - LuckPerms Suffix\n%reason% - Reason for muting\n%end-date% - End date of the mute\n%moderator% - Moderator who muted the player")
    var mutedMessage = "<red>You are muted and cannot send messages.</red>"
}

@ConfigSerializable
class JoinConfig {
    var enabled = true
    @Comment("%player% - Player\n%server% - Player's Current Server\n%prefix% - LuckPerms Prefix\n%suffix% - LuckPerms Suffix")
    var format = "<dark_gray>(<green>+<dark_gray>) <gray>%player%"
}

@ConfigSerializable
class LeaveConfig {
    var enabled = true
    @Comment("%player% - Player\n%server% - Player's Last Server\n%prefix% - LuckPerms Prefix\n%suffix% - LuckPerms Suffix")
    var format = "<dark_gray>(<red>-<dark_gray>) <gray>%player%"
}

@ConfigSerializable
class ChangeConfig {
    var enabled = true
    @Comment("%player% - Player\n%old_server% - Player's Previous Server\n%new_server% - Player's New Server\n%prefix% - LuckPerms Prefix\n%suffix% - LuckPerms Suffix")
    var format = "<dark_gray>(<gold>•<dark_gray>) <gray>%player% <dark_gray>(<gold>%old_server%→%new_server%<dark_gray>)"
}

/* Commands Config */
@ConfigSerializable
class CommandsConfig {
    @Comment("/broadcast, /bcast, /bc")
    var broadcast = BroadcastConfig()
    @Comment("/message, /msg, /whisper, /w")
    var message = MessageConfig()
}

@ConfigSerializable
class BroadcastConfig {
    var enabled = true
    @Comment("%player% - Player\n%message% - Message\n%server% - Player's Current Server\n%prefix% - LuckPerms Prefix\n%suffix% - LuckPerms Suffix")
    var format = "<b><#00ffff>%player%:</b> %message%"
    @Comment("If false, players will need the permission 'vmessage.command.broadcast' to use the /broadcast command\n" +
            "If true, all players can use the /broadcast command by default unless they have the permission 'vmessage.command.broadcast' set to false")
    var allowByDefault = false
    @Comment("Whether to allow players to use MiniMessage in their broadcast messages\nMiniMessage is always allowed for console broadcasts")
    var allowMiniMessage = true
}

@ConfigSerializable
class MessageConfig {
    var enabled = true
    @Comment("%sender% - Sender\n%receiver% - Receiver\n%message% - Message\n%sender-server% - Sender's Current Server\n%receiver-server% - Receiver's Current Server\n%sender-prefix% - Sender's LuckPerms Prefix\n%sender-suffix% - Sender's LuckPerms Suffix\n%receiver-prefix% - Receiver's LuckPerms Prefix\n%receiver-suffix% - Receiver's LuckPerms Suffix\nCustom meta placeholders are currently not supported for this option")
    var format = MessageFormatConfig()
    @Comment("Whether to allow players to use MiniMessage in their private messages")
    var allowMiniMessage = false
    @Comment("If false, players will need the permission 'vmessage.command.message' to use the /message command\n" +
            "If true, all players can use the /message command by default unless they have the permission 'vmessage.command.message' set to false\n" +
            "This also affects the /reply command")
    var allowByDefault = false
    @Comment("Whether to enable the /reply command\nIt will reply to the last person who sent you a message")
    var enableReplyCommand = true
}

@ConfigSerializable
class MessageFormatConfig {
    var sender = "<b>(<#00ffff>You </#00ffff>→ <#00ffff>%receiver%</#00ffff>): </b>%message%"
    var receiver = "<b>(<#00ffff>%sender% </#00ffff>→ <#00ffff>You</#00ffff>): </b>%message%"
}

@ConfigSerializable
class LuckPermsMetaConfig {
    // Not to be used in-code, these only serve as example keys for the config file
    var customName1 = "meta_key1"
    var customName2 = "meta_key2"
}

@ConfigSerializable
class ServerAliasesConfig {
    // Not to be used in-code, these only serve as example aliases for the config file
    var lobby = "Lobby"
    var lobby1 = "Lobby"
    var lobby2 = "Lobby"
}

/* OneBot Config */
@ConfigSerializable
class OneBotConfig {
    var enabled = false
    @Comment("NapCat HTTP API base URL")
    var apiUrl = "http://localhost:6727"
    var groupId = ""
    @Comment("Access token for sending messages to NapCat API (leave empty to disable authentication)")
    var accessTokenSend = ""
    @Comment("remove Unicode control characters from nickname to avoid display order issue")
    var nicknameClean = true
    var callback = OneBotCallbackConfig()
    @Comment("Access token for verifying incoming callbacks from NapCat (leave empty to disable authentication)")
    var accessTokenCallback = ""
    var forwardToQq = OneBotForwardToQQConfig()
    @Comment("Whether to forward QQ group messages to game")
    var forwardToGame = OneBotForwardToGameConfig()
    @Comment("Enable debug logging for OneBot integration (shows detailed token verification and request information)")
    var debug = false
}

@ConfigSerializable
class OneBotCallbackConfig {
    var enabled = true
    @Comment("Host to listen on for HTTP callback")
    var host = "0.0.0.0"
    @Comment("Port to listen on for HTTP callback")
    var port = 8080
    @Comment("Callback path")
    var path = "/onebot/callback"
}

@ConfigSerializable
class OneBotForwardToQQConfig {
    var chat = true
    var join = true
    var leave = true
    var change = true
    var broadcast = true
    var format = OneBotFormatToQQConfig()
    @Comment("Delay time in seconds before sending leave messages. If player rejoins during this delay, the leave message is cancelled and the join message is filtered (not sent)")
    var leaveDelay = 15
}

@ConfigSerializable
class OneBotFormatToQQConfig {
    @Comment("%player% - Player name\n%message% - Message content\n%server% - Server name\n%old_server% - Previous server\n%new_server% - New server")
    var chat = "[%server%] %player%: %message%"
    var join = "[游戏] %player% 加入了服务器"
    var leave = "[游戏] %player% 离开了服务器"
    var change = "[游戏] %player% 从 %old_server% 切换到 %new_server%"
    var broadcast = "[广播] %message%"
}

@ConfigSerializable
class OneBotForwardToGameConfig {
    var enabled = true
    var format = OneBotToGameFormatConfig()
}

@ConfigSerializable
class OneBotToGameFormatConfig {
    @Comment("Message format for forwarding QQ group messages to game\n%message% - Message content from QQ\n%sender% - Sender nickname\n%sender_id% - Sender QQ number\n%sender_role% - Sender role (owner/admin/member)")
    var toGame = "[QQ群] %sender%: %message%"
}