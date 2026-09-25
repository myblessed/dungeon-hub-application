package net.dungeonhub.application.commands

import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.GuildButtonInteractionCreateEvent
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.converters.impl.optionalString
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.extensions.event
import dev.kordex.i18n.toKey
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.loader.LoadExtension
import net.dungeonhub.application.service.addEmbed
import net.dungeonhub.application.service.color
import net.dungeonhub.connection.DiscordServerConnection
import net.dungeonhub.connection.DiscordUserConnection
import net.dungeonhub.exception.PlayerNotFoundException
import net.dungeonhub.hypixel.connection.HypixelApiConnection
import net.dungeonhub.hypixel.entities.player.HypixelPlayer
import net.dungeonhub.hypixel.entities.status.PlayerSession
import net.dungeonhub.mojang.connection.MojangConnection
import java.time.Duration
import kotlin.time.toKotlinDuration

@LoadExtension
class StatusCommand : Extension() {
    override val name = "status-command"

    override suspend fun setup() {
        ephemeralSlashCommand(::StatusArguments) {
            name = "status".toKey()
            description = "Check the status of the given user".toKey()

            action {
                respond {
                    val ticket = guild?.id?.value?.toLong()?.let { guildId ->
                        DiscordServerConnection.authenticated()
                            .findTickets(guildId, channelId = event.interaction.channelId.value.toLong())
                            ?.firstOrNull()
                    }

                    val discordUser = DiscordUserConnection.authenticated().getLinkedById(user.id.value.toLong())

                    val ignArgument = arguments.ign

                    val uuid = if(ignArgument != null) {
                        try {
                            MojangConnection.getUUIDByName(ignArgument)
                        } catch (_: PlayerNotFoundException) {
                            addEmbed {
                                description = "Couldn't find the user `$ignArgument`."
                                color(EmbedColor.Negative)
                            }
                            return@respond
                        }
                    } else {
                        ticket?.user?.minecraftId
                            ?: discordUser?.minecraftId
                    }

                    val ign = uuid?.let {
                        try {
                            MojangConnection.getNameByUUID(it)
                        } catch (_: PlayerNotFoundException) {
                            null
                        }
                    }

                    if(uuid == null || ign == null) {
                        addEmbed {
                            description = "The given user is currently not linked!"
                            color(EmbedColor.Negative)
                        }
                        return@respond
                    }

                    val hypixelApiConnection = HypixelApiConnection().withCacheExpiration(2)

                    val session = hypixelApiConnection.getSession(uuid).valueOrNull
                    val playerData by lazy { hypixelApiConnection.getPlayerData(uuid).valueOrNull }

                    if(session == null) {
                        addEmbed {
                            description = "Couldn't load the session for this user!"
                            color(EmbedColor.Negative)
                        }
                        return@respond
                    }

                    addEmbed {
                        title = "Status of $ign"
                        color(if(session.online) EmbedColor.Positive else EmbedColor.Negative)
                        description = if(session.online) {
                            parseSessionText(session, ign)
                        } else {
                            parseOfflineText(playerData, ign)
                        }
                        thumbnail {
                            url = "https://visage.surgeplay.com/face/$uuid"
                        }
                    }
                }
            }
        }

        event<GuildButtonInteractionCreateEvent> {
            check {
                failIfNot(event.interaction.componentId == "ticket-user-status")
            }

            action {
                val response = event.interaction.deferEphemeralResponse()

                response.respond {
                    val ticket = DiscordServerConnection.authenticated()
                        .findTickets(event.interaction.guildId.value.toLong(), event.interaction.channelId.value.toLong())
                        ?.firstOrNull()

                    if(ticket == null) {
                        addEmbed {
                            description = "Couldn't load the ticket!"
                            color(EmbedColor.Negative)
                        }
                        return@respond
                    }

                    val uuid = ticket.user.minecraftId

                    if(uuid == null) {
                        addEmbed {
                            description = "The ticket user currently isn't linked!"
                            color(EmbedColor.Negative)
                        }
                        return@respond
                    }

                    val ign = try{
                        MojangConnection.getNameByUUID(uuid)
                    } catch (_: PlayerNotFoundException) {
                        addEmbed {
                            description = "Couldn't find the IGN of the user `$uuid`. Weird."
                            color(EmbedColor.Negative)
                        }
                        return@respond
                    }

                    val hypixelApiConnection = HypixelApiConnection().withCacheExpiration(2)

                    val session = hypixelApiConnection.getSession(uuid).valueOrNull
                    val playerData by lazy { hypixelApiConnection.getPlayerData(uuid).valueOrNull }

                    if(session == null) {
                        addEmbed {
                            description = "Couldn't load the session for this user!"
                            color(EmbedColor.Negative)
                        }
                        return@respond
                    }

                    addEmbed {
                        title = "Status of $ign"
                        color(if(session.online) EmbedColor.Positive else EmbedColor.Negative)
                        description = if(session.online) {
                            parseSessionText(session, ign)
                        } else {
                            parseOfflineText(playerData, ign)
                        }
                        thumbnail {
                            url = "https://visage.surgeplay.com/face/$uuid"
                        }
                    }
                }
            }
        }
    }

    fun parseSessionText(session: PlayerSession, ign: String): String {
        return if (session.gameType != null) {
            if (session.mode != null) {
                if (session.map != null) {
                    "$ign is currently in `${session.gameType}`, playing `${session.mode}` on `${session.map}`!"
                } else {
                    "$ign is currently in `${session.gameType}`, playing `${session.mode}`!"
                }
            } else {
                "$ign is currently in `${session.gameType}`!"
            }
        } else {
            "$ign is currently online!"
        }
    }

    fun parseOfflineText(player: HypixelPlayer?, ign: String): String {
        val lastLogin = player?.lastLogin
        val lastLogout = player?.lastLogout
        val lastPlayTime = lastLogout?.let { lastLogout ->
            lastLogin?.let {
                Duration.between(it, lastLogout)
            }
        }?.withNanos(0)?.toKotlinDuration()?.toString()

        var result = "$ign is offline!"

        if(lastLogout != null) {
            result += "\nLast seen: <t:${lastLogout.epochSecond}:R>."
            if(lastPlayTime != null) {
                result += "\nLast session length: `$lastPlayTime`."
            }
        }

        return result
    }

    class StatusArguments : Arguments() {
        val ign by optionalString {
            name = "ign".toKey()
            description = "The user to get the status of.".toKey()
            minLength = 2
        }
    }
}