package net.dungeonhub.application.commands

import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.converters.impl.optionalString
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.components.components
import dev.kordex.core.components.linkButton
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.ephemeralSlashCommand
import dev.kordex.core.extensions.publicSlashCommand
import dev.kordex.i18n.toKey
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.exceptions.CommandExecutionException
import net.dungeonhub.application.exceptions.FailedToLoadEmbedException
import net.dungeonhub.application.exceptions.PlayerNotFoundWarning
import net.dungeonhub.application.loader.LoadExtension
import net.dungeonhub.application.service.ApplicationService
import net.dungeonhub.application.service.AutoCompletionService
import net.dungeonhub.application.service.addEmbed
import net.dungeonhub.application.service.color
import net.dungeonhub.connection.DiscordUserConnection
import net.dungeonhub.hypixel.client.responses.ValueResponse
import net.dungeonhub.hypixel.connection.HypixelApiConnection
import net.dungeonhub.i18n.Translations.Command.Player
import net.dungeonhub.mojang.entity.toUUID
import org.slf4j.LoggerFactory

@LoadExtension
class PlayerCommand : Extension() {
    override val name = "player-command"
    private val logger = LoggerFactory.getLogger(PlayerCommand::class.java)

    override suspend fun setup() {
        publicSlashCommand(::PlayerArguments) {
            name = Player.name
            description = Player.description
            allowInDms = true

            action {
                respond {
                    components {
                        linkButton {
                            label = Player.Response.Buttons.Skycrypt.label
                            url = ApplicationService.skyCryptUrl + "stats/" + arguments.ign
                        }
                    }

                    try {
                        embeds = mutableListOf(
                            ApplicationService.getPlayerDataEmbed(
                                arguments.ign,
                                user.id.value.toLong(),
                                profileOverride = arguments.profile?.let {
                                    try {
                                        it.toUUID()
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            )
                        )
                    } catch (failedToLoadEmbedException: FailedToLoadEmbedException) {
                        val embed = failedToLoadEmbedException.embed
                        embed.color = EmbedColor.Negative.color
                        embeds = mutableListOf(
                            embed
                        )
                    } catch (playerNotFoundWarning: PlayerNotFoundWarning) {
                        embeds = mutableListOf(
                            ApplicationService.getErrorEmbed(playerNotFoundWarning)
                        )
                    } catch (commandExecutionException: CommandExecutionException) {
                        embeds = mutableListOf(
                            ApplicationService.getErrorEmbed(commandExecutionException)
                        )

                        logger.error(null, commandExecutionException)
                    } catch (exception: Exception) {
                        logger.error(null, exception)
                    }
                }
            }
        }

        ephemeralSlashCommand(::SetProfileArguments) {
            name = "set-primary-profile".toKey()
            description = "Set your primary skyblock profile.".toKey()

            // TODO replace with something like /settings - this should only be temporary
            action {
                val discordUser = DiscordUserConnection.authenticated().getLinkedById(user.id.value.toLong())

                if(discordUser == null) {
                    respond {
                        addEmbed {
                            description = "You aren't linked. Use `/link` to link your account."
                            color(EmbedColor.Negative)
                        }
                    }
                    return@action
                }

                val profilesResponse = HypixelApiConnection().getSkyblockProfiles(discordUser.minecraftId!!)
                if(profilesResponse !is ValueResponse) {
                    respond {
                        addEmbed {
                            description = "An issue occurred while contacting the Hypixel API - please try again later."
                            color(EmbedColor.Negative)
                        }
                    }
                    return@action
                }

                val profiles = profilesResponse.value.profiles
                if(profiles.isEmpty()) {
                    respond {
                        addEmbed {
                            description = "It seems like you don't have any profiles - please try again later."
                            color(EmbedColor.Negative)
                        }
                    }
                    return@action
                }

                val profile = profiles.firstOrNull { it.profileId.toString() == arguments.profile || it.cuteName == arguments.profile }
                if(profile == null) {
                    respond {
                        addEmbed {
                            description = "Couldn't find your profile `${arguments.profile}`."
                            color(EmbedColor.Negative)
                        }
                    }
                    return@action
                }

                respond {
                    val updateModel = discordUser.getUpdateModel()
                    updateModel.primarySkyblockProfile = profile.profileId

                    val updatedUser = DiscordUserConnection.authenticated().updateUser(discordUser.id, updateModel)

                    if(updatedUser == null) {
                        addEmbed {
                            description = "An issue occurred while trying to save your primary profile."
                            color(EmbedColor.Negative)
                        }
                        return@respond
                    }

                    addEmbed {
                        description = "Your primary profile has been set to ${profile.cuteName}."
                        color(EmbedColor.Positive)
                    }
                }
            }
        }
    }

    class PlayerArguments : Arguments() {
        val ign by string {
            name = Player.Arguments.Ign.name
            description = Player.Arguments.Ign.description
            minLength = 2
        }

        val profile by optionalString {
            name = Player.Arguments.Profile.name
            description = Player.Arguments.Profile.description
            autoCompleteCallback = AutoCompletionService.skyblockProfile
        }
    }

    class SetProfileArguments : Arguments() {
        val profile by string {
            name = Player.Arguments.Profile.name
            description = "Select your main profile, which will then be used to display your stats across multiple features.".toKey()
            autoCompleteCallback = AutoCompletionService.skyblockProfile
        }
    }
}