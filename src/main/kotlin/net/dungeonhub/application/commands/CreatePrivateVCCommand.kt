package net.dungeonhub.application.commands


import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.core.behavior.createVoiceChannel
import dev.kord.rest.builder.channel.addMemberOverwrite
import dev.kord.rest.builder.channel.addRoleOverwrite
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.converters.impl.*
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.publicSlashCommand
import dev.kordex.core.i18n.toKey
import net.dungeonhub.application.exceptions.CommandExecutionException
import dev.kord.common.entity.Snowflake
import dev.kordex.core.utils.Video
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.enums.ServerProperty
import net.dungeonhub.application.loader.ClassLoader
import net.dungeonhub.application.loader.LoadExtension
import net.dungeonhub.application.service.addEmbed
import net.dungeonhub.application.service.color
import org.slf4j.Logger
import org.slf4j.LoggerFactory


@LoadExtension
class CreatePrivateVCCommand : Extension() {
    private val logger: Logger = LoggerFactory.getLogger(CreatePrivateVCCommand::class.java)
    override val name = "create-vc"
    override suspend fun setup() {
        publicSlashCommand(::CreateVCArgs) {
            name = "create".toKey()
            description = "Creates a private voice channel for a user.".toKey()
            defaultMemberPermissions = Permissions(Permission.ManageChannels)
            allowInDms = false


            action {
                val guild = guild
                val everyoneRole = guild!!.id
                val rawVerifiedId = ServerProperty.VERIFIED_GROUP_ROLE.getValue(everyoneRole)
                    ?: throw CommandExecutionException("VERIFIED_GROUP_ROLE has not been configured in ServerProperty")
                val verifiedRoleId = Snowflake(rawVerifiedId)
                val rawStaffId = ServerProperty.STAFF_GROUP_ROLE.getValue(everyoneRole)
                    ?: throw CommandExecutionException("STAFF_GROUP_ROLE has not been configured in ServerProperty")
                val staffRoleId = Snowflake(rawStaffId)
                val rawAdminId = ServerProperty.ADMINISTRATION_GROUP_ROLE.getValue(everyoneRole)
                    ?: throw CommandExecutionException("ADMINISTRATION_GROUP_ROLE has not been configured in ServerProperty")
                val adminRoleId = Snowflake(rawAdminId)
                val rawCategoryString: String? = ServerProperty.VC_CREATE_CATEGORY.getValue(everyoneRole)
                val categoryId: Snowflake? = rawCategoryString?.let { Snowflake(it) }

                try {
                    val voiceChannel = guild.createVoiceChannel(arguments.name) {

                        addRoleOverwrite(adminRoleId) {
                            allowed += Permission.ViewChannel
                            allowed += Permission.ManageChannels
                            allowed += Permission.ManageRoles

                            allowed += Permission.MoveMembers
                            allowed += Permission.DeafenMembers
                            allowed += Permission.MuteMembers

                            allowed += Permission.ViewChannel
                            allowed += Permission.Connect
                            allowed += Permission.SendMessages
                        }

                        addRoleOverwrite(staffRoleId) {
                            allowed += Permission.MoveMembers
                            allowed += Permission.DeafenMembers
                            allowed += Permission.MuteMembers

                            allowed += Permission.ViewChannel
                            allowed += Permission.Connect
                            allowed += Permission.SendMessages
                        }

                        addRoleOverwrite(verifiedRoleId) {
                            allowed += Permission.ViewChannel
                            allowed += Permission.SendMessages
                        }

                        addRoleOverwrite(everyoneRole) {
                            denied += Permission.ViewChannel
                            denied += Permission.Connect
                            denied += Permission.SendMessages
                        }

                        addMemberOverwrite(arguments.user.id) {
                            allowed += Permission.ViewChannel
                            allowed += Permission.Connect
                            allowed += Permission.ManageChannels
                            allowed += Permission.SendMessages
                            allowed += Permission.Speak
                            allowed += Permission.Video
                            allowed += Permission.MoveMembers
                        }

                        parentId = categoryId

                    }

                    respond {
                        content = "${voiceChannel.mention} has been created successfully!"
                    }

                } catch (exception: Exception) {
                    logger.error("Error while executing PrivateVC command", exception)
                    respond {
                        addEmbed {
                            description = "Error while executing PrivateVC command."
                            color(EmbedColor.Negative)
                        }
                    }
                }
            }
        }
    }
}


class CreateVCArgs : Arguments() {
    val name by string {
        name = "vc-name".toKey()
        description = "Give a name to the new VC.".toKey()
    }

    val user by user {
        name = "vc-user".toKey()
        description = "Select the owner of the VC.".toKey()
    }
}