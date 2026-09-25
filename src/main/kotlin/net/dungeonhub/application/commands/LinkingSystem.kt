package net.dungeonhub.application.commands

import dev.kord.common.entity.*
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.behavior.requestMembers
import dev.kord.core.builder.components.emoji
import dev.kord.core.entity.Member
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.entity.Role
import dev.kord.core.entity.interaction.ButtonInteraction
import dev.kord.core.event.guild.MemberJoinEvent
import dev.kord.core.event.interaction.GuildButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ModalSubmitInteractionCreateEvent
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.builder.component.ActionRowBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.create.FollowupMessageCreateBuilder
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.application.slash.publicSubCommand
import dev.kordex.core.commands.converters.impl.role
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.commands.converters.impl.user
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.event
import dev.kordex.core.extensions.publicSlashCommand
import dev.kordex.core.extensions.publicUserCommand
import dev.kordex.core.i18n.toKey
import dev.kordex.core.utils.scheduling.Scheduler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.exceptions.*
import net.dungeonhub.application.loader.LoadExtension
import net.dungeonhub.application.misc.DhScheduler
import net.dungeonhub.application.service.*
import net.dungeonhub.connection.DiscordUserConnection
import net.dungeonhub.exception.PlayerNotFoundException
import net.dungeonhub.hypixel.client.responses.ValueResponse
import net.dungeonhub.hypixel.connection.HypixelApiConnection
import net.dungeonhub.i18n.Translations
import net.dungeonhub.i18n.Translations.Command.FindUser
import net.dungeonhub.i18n.Translations.Command.ForceSync
import net.dungeonhub.i18n.Translations.Command.Ign
import net.dungeonhub.i18n.Translations.Command.Link
import net.dungeonhub.i18n.Translations.Command.Sync
import net.dungeonhub.i18n.Translations.Command.Unlink
import net.dungeonhub.mojang.connection.MojangConnection
import org.slf4j.LoggerFactory
import java.util.*

@PrivilegedIntent
@LoadExtension
class LinkingSystem : Extension() {
    override val name = "linking-system"
    override val intents = mutableSetOf<Intent>(Intent.GuildMembers)
    private val logger = LoggerFactory.getLogger(LinkingSystem::class.java)

    override suspend fun setup() {
        scheduler = DhScheduler()

        publicSlashCommand(::SingleIgnArguments) {
            name = Link.name
            description = Link.description
            allowInDms = true

            action {
                respond {
                    val linkedTo = DiscordUserConnection.authenticated().getById(user.id.value.toLong())?.minecraftId

                    if (linkedTo != null) {
                        val embed = ApplicationService.embed
                        embed.color = EmbedColor.Information.color
                        embed.description = "You're already linked to user `${
                            MojangConnection.getNameByUUID(linkedTo)
                        }`! If you think that's incorrect, try using ${"`/unlink`"}."

                        embeds = mutableListOf(
                            embed
                        )

                        return@respond
                    }

                    val linkedId = try {
                        NicknameService.linkToIgn(arguments.ign, user.asUser())
                    } catch (invalidOptionWarning: InvalidOptionWarning) {
                        embeds = mutableListOf(ApplicationService.getErrorEmbed(invalidOptionWarning))
                        actionRow {
                            addLinkHelpButton()
                        }

                        return@respond
                    } catch (hypixelLinkedToOtherWarning: HypixelLinkedToOtherWarning) {
                        embeds = mutableListOf(ApplicationService.getErrorEmbed(hypixelLinkedToOtherWarning))
                        actionRow {
                            addLinkHelpButton()
                        }

                        return@respond
                    } catch (playerNotFoundException: PlayerNotFoundException) {
                        embeds = mutableListOf(
                            ApplicationService.getErrorEmbed(
                                CommandExecutionWarning(playerNotFoundException.message)
                            )
                        )
                        return@respond
                    }

                    val embed = ApplicationService.embed
                    embed.title = "Linked successfully"
                    embed.description =
                        "You're now linked to `${MojangConnection.getNameByUUID(linkedId)}`."
                    embed.color = EmbedColor.Positive.color

                    embeds = mutableListOf(embed)
                }

                scheduler.launch {
                    if (guild != null) {
                        val member = user.asMember(guild!!.id)

                        val roles = RolesService.updateRoles(member)

                        NicknameService.updateNickname(member, roles)
                    } else {
                        // TODO dont use this. rework it, this causes too many updates
                        val user = user.asUser()

                        val roles = RolesService.updateRoles(user)

                        NicknameService.updateNickname(user, roles)
                    }
                }
            }
        }

        listOf(693263712626278553L, 633621474183217163L, 1023684107877761196L).map { Snowflake(it) }
            .forEach { guildId ->
                publicSlashCommand(::SingleIgnArguments) {
                    name = "manual-link".toKey()
                    description = "Manually link someone by IGN.".toKey()
                    guild(guildId)
                    check {
                        failIfNot("You aren't allowed to use this command.") {
                            event.interaction.user.id.value.toLong() == 356134481452597250L
                        }
                    }

                    action {
                        respond {
                            val uuid = MojangConnection.getUUIDByName(arguments.ign)

                            val discordUserResponse = HypixelApiConnection().getHypixelLinkedDiscord(uuid)

                            if(discordUserResponse !is ValueResponse) {
                                addEmbed {
                                    description = "There was an issue fetching your Hypixel data. Please try again later."
                                    color(EmbedColor.Negative)
                                }
                                return@respond
                            }

                            val discordUser = discordUserResponse.value ?: throw InvalidOptionWarning(
                                "ign",
                                "Please add the correct discord-account to your Hypixel social menu.\n"
                                        + "To learn more about how to do this, use `/help verification`."
                            )

                            val users = guild!!.requestMembers { query = discordUser; limit = 5 }
                                .map { it.members }
                                .toList()

                            val user = users.map { members -> members.firstOrNull { it.username == discordUser } }
                                .firstOrNull()

                            if (user == null) {
                                throw CommandExecutionException("The specified user (`$discordUser`) does not exist.")
                            }

                            NicknameService.linkToIgn(arguments.ign, user)

                            val embed = ApplicationService.embed
                            embed.color = EmbedColor.Positive.color
                            embed.description = "Linked `${arguments.ign}` to: ${user.tag}"

                            embeds = mutableListOf(embed)
                        }
                    }
                }
            }

        publicSlashCommand {
            name = "mass-sync".toKey()
            description = "Queue many users for role/nickname syncing.".toKey()
            allowInDms = false
            defaultMemberPermissions = Permissions(Permission.Administrator)

            publicSubCommand(::MassSyncRoleArguments) {
                name = "role".toKey()
                description = "Adds users in a role to the mass sync queue.".toKey()

                action {
                    respond {
                        val role = arguments.role

                        val members = guild!!.withStrategy(EntitySupplyStrategy.cachingRest).members.filter {
                            // first check for @everyone, if it's not @everyone, check if the user has the role
                            (role.id == guild!!.id && !it.isBot) || it.roleIds.contains(role.id)
                        }.toList()

                        MassSyncService.syncUsers(guild!!.id, members.map { it.id })

                        val embed = ApplicationService.embed
                        embed.color = EmbedColor.Positive.color
                        embed.description = "Added ${members.size} users to the mass-sync queue."

                        embeds = mutableListOf(embed)
                    }
                }
            }

            publicSubCommand(::MassSyncGuildArguments) {
                name = "guild".toKey()
                description = "Adds users in a guild to the mass sync queue.".toKey()

                action {
                    val guildId = guild!!.id

                    respond {
                        val guild = HypixelApiConnection().getGuild(arguments.guild).valueOrNull

                        if (guild == null) {
                            addEmbed {
                                description = "Couldn't find the Hypixel guild \"${arguments.guild}\"."
                                color(EmbedColor.Negative)
                            }
                            return@respond
                        }

                        var count = 0
                        val unknownUsers = mutableSetOf<UUID>()
                        for (member in guild.members) {
                            val discordUser = DiscordUserConnection.authenticated().findUserByUuid(member.uuid)

                            if (discordUser == null) {
                                unknownUsers.add(member.uuid)
                                continue
                            }

                            count++
                            MassSyncService.syncUser(guildId, Snowflake(discordUser.id))
                        }

                        val embed = ApplicationService.embed
                        embed.color = EmbedColor.Positive.color
                        embed.description =
                            "Added $count users for the guild ${guild.displayName} to the mass-sync queue.${
                                if (unknownUsers.isNotEmpty()) {
                                    "\nCouldn't link the following players: " + unknownUsers.joinToString(", ")
                                } else ""
                            }"

                        embeds = mutableListOf(embed)
                    }
                }
            }

            publicSubCommand {
                name = "list".toKey()
                description = "Show the number of users currently queued for mass syncing.".toKey()

                action {
                    respond {
                        addEmbed {
                            color(EmbedColor.Information)
                            description =
                                "There are currently ${MassSyncService.getUsersToSync(guild!!.id).size} users in the mass sync queue."
                        }
                    }
                }
            }

            publicSubCommand {
                name = "clear".toKey()
                description = "Clear all users currently queued for mass syncing.".toKey()

                action {
                    respond {
                        val count = MassSyncService.clearUsers(guild!!.id)
                        addEmbed {
                            color(EmbedColor.Information)
                            description = "Cleared $count users from the mass sync queue."
                        }
                    }
                }
            }
        }

        publicSlashCommand {
            name = Sync.name
            description = Sync.description
            allowInDms = false

            action {
                respond {
                    val userModel = DiscordUserConnection.authenticated().getLinkedById(user.id.value.toLong())

                    if (userModel == null) {
                        val embed = ApplicationService.embed
                        embed.color = EmbedColor.Negative.color
                        embed.description = "Please link your ingame-account before using this command again."

                        embeds = mutableListOf(embed)

                        actionRow {
                            addLinkButtons()
                        }

                        return@respond
                    }

                    val member = user.asMember(guild!!.id)

                    val roles = RolesService.updateRoles(member)

                    var nicknameChanged = true
                    try {
                        NicknameService.updateNickname(member, userModel, roles)
                    } catch (_: NoNameSchemaWarning) {
                        nicknameChanged = false
                    } catch (notLinkedWarning: NotLinkedWarning) {
                        embeds = mutableListOf(ApplicationService.getErrorEmbed(notLinkedWarning))
                        return@respond
                    }

                    val embed = ApplicationService.embed
                    embed.description = "Updating your ${if (nicknameChanged) "nickname and " else ""}roles."
                    embed.color = EmbedColor.Positive.color

                    embeds = mutableListOf(embed)
                }
            }
        }

        fun respondToForceSync(target: Member?): suspend FollowupMessageCreateBuilder.() -> Unit {
            if(target == null) {
                return {
                    addEmbed {
                        color(EmbedColor.Negative)
                        description = "That user isn't a member of this server!"
                    }
                }
            }

            return {
                val roles = RolesService.updateRoles(target, cacheExpiration = 5)

                val embed = ApplicationService.embed

                try {
                    NicknameService.updateNickname(target, roles)

                    embed.color = EmbedColor.Positive.color
                    embed.description = "Username and roles of ${target.mention} were synced!"
                } catch (_: NotLinkedWarning) {
                    embed.color = EmbedColor.Negative.color
                    embed.description = "${target.mention} is not linked, their roles were synced!"
                }

                embeds = mutableListOf(embed)
            }
        }

        publicSlashCommand(::ForceSyncArguments) {
            name = ForceSync.name
            description = ForceSync.description
            defaultMemberPermissions = Permissions(Permission.ManageNicknames, Permission.ManageRoles)
            allowInDms = false

            action {
                respond {
                    val target = arguments.user.asMemberOrNull(guild!!.id)

                    respondToForceSync(target)()
                }
            }
        }

        publicUserCommand {
            name = Translations.UserCommand.ForceSync.name
            allowInDms = false
            defaultMemberPermissions = Permissions(Permission.ManageNicknames, Permission.ManageRoles)

            action {
                respond {
                    val target = targetUsers.first().asMemberOrNull(guild!!.id)

                    respondToForceSync(target)()
                }
            }
        }

        publicSlashCommand {
            name = Unlink.name
            description = Unlink.description
            allowInDms = true

            action {
                respond {
                    val oldUserModel = DiscordUserConnection.authenticated().getLinkedById(user.id.value.toLong())
                        ?: throw NotLinkedWarning()

                    val updateModel = oldUserModel.getUpdateModel()
                    updateModel.minecraftId = null
                    updateModel.primarySkyblockProfile = null

                    DiscordUserConnection.authenticated().updateUser(user.id.value.toLong(), updateModel)
                        ?: throw CommandExecutionException("Couldn't update your user data.")

                    addEmbed {
                        description = "Unlinked successfully from account `${
                            MojangConnection.getNameByUUID(oldUserModel.minecraftId!!)
                        }`."
                        color(EmbedColor.Positive)
                    }
                }

                scheduler.launch {
                    val user = user.asUser()

                    val roles = RolesService.updateRoles(user)

                    try {
                        NicknameService.updateNickname(user, roles)
                    } catch (_: NotLinkedWarning) {
                        // Do nothing
                    }
                }
            }
        }

        publicSlashCommand(::IgnArguments) {
            name = Ign.name
            description = Ign.description

            action {
                respond {
                    val uuid =
                        DiscordUserConnection.authenticated().getById(arguments.user.id.value.toLong())?.minecraftId

                    if (uuid == null) {
                        val embed = ApplicationService.errorEmbed
                        embed.description = "That user is not linked!"
                        embeds = mutableListOf(embed)
                        return@respond
                    }

                    val ign = MojangConnection.getNameByUUID(uuid)

                    val embed = ApplicationService.embed
                    embed.color = EmbedColor.Positive.color
                    embed.description = "The given user has the IGN `$ign`."
                    embed.thumbnail {
                        url = "https://visage.surgeplay.com/face/${uuid.toString().replace("-", "")}"
                    }
                    embeds = mutableListOf(embed)
                }
            }
        }

        publicSlashCommand(::SingleIgnArguments) {
            name = FindUser.name
            description = FindUser.description

            action {
                respond {
                    val uuid = MojangConnection.getUUIDByName(arguments.ign)

                    val userModel = DiscordUserConnection.authenticated().findUserByUuid(uuid)
                        ?: throw CommandExecutionWarning("Couldn't find who the given user is linked to.")

                    addEmbed {
                        color(EmbedColor.Positive)
                        description = "The given player is linked to user <@${userModel.id}>."
                    }
                }
            }
        }

        event<GuildButtonInteractionCreateEvent> {
            check {
                failIfNot(
                    listOf(
                        "link_user",
                        "link_user_silent"
                    ).contains(event.interaction.componentId)
                )
            }

            action {
                val linkedTo =
                    DiscordUserConnection.authenticated().getById(event.interaction.user.id.value.toLong())?.minecraftId

                if (linkedTo != null) {
                    event.interaction.respondEphemeral {
                        val embed = ApplicationService.embed
                        embed.color = EmbedColor.Information.color
                        embed.description = "You're already linked to user `${
                            MojangConnection.getNameByUUID(linkedTo)
                        }`! If you think that's incorrect, try using ${"`/unlink`"}."

                        embeds = mutableListOf(embed)
                    }

                    return@action
                }

                if (event.interaction.componentId == "link_user") {
                    event.interaction.sendLinkModal()
                } else {
                    event.interaction.sendSilentLinkModal()
                }
            }
        }

        event<ModalSubmitInteractionCreateEvent> {
            check {
                failIfNot(
                    event.interaction.modalId == "link_ign" || event.interaction.modalId == "link_ign_silent"
                )
            }

            action {
                val response = if (event.interaction.modalId == "link_ign") {
                    event.interaction.deferPublicResponse()
                } else {
                    event.interaction.deferEphemeralResponse()
                }

                try {
                    val ign = event.interaction.textInputs["ign"]?.value?.let { it.ifBlank { null } }

                    if (ign == null) {
                        throw InvalidOptionWarning(
                            "ign",
                            "Please enter a valid Ingame-Name."
                        )
                    }

                    val linkedId = NicknameService.linkToIgn(ign, event.interaction.user)

                    response.respond {
                        val embed = ApplicationService.embed
                        embed.title = "Linked successfully"
                        embed.description = "${event.interaction.user.mention}, you're now linked to `${
                            MojangConnection.getNameByUUID(linkedId)
                        }`."
                        embed.color = EmbedColor.Positive.color

                        embeds = mutableListOf(embed)
                    }
                } catch (commandExecutionWarning: CommandExecutionWarning) {
                    response.respond {
                        embeds = mutableListOf(ApplicationService.getErrorEmbed(commandExecutionWarning))
                    }
                    return@action
                } catch (commandExecutionException: CommandExecutionException) {
                    logger.error(null, commandExecutionException)
                    response.respond {
                        embeds = mutableListOf(ApplicationService.getErrorEmbed(commandExecutionException))
                    }
                    return@action
                }

                scheduler.launch {
                    // TODO dont use this. this updates the user on all servers. use the member object here instead
                    val roles = RolesService.updateRoles(event.interaction.user)

                    NicknameService.updateNickname(event.interaction.user, roles)
                }
            }
        }

        event<MemberJoinEvent> {
            action {
                scheduler.launch {
                    val roles: List<Role> = RolesService.updateRoles(event.member)

                    NicknameService.updateNickname(event.member, roles)
                }
            }
        }
    }

    override suspend fun unload() {
        scheduler.cancel("Extension shutting down.")
    }

    class SingleIgnArguments : Arguments() {
        val ign by string {
            name = "ign".toKey()
            description = "The users ingame-name".toKey()
            minLength = 2
        }
    }

    class ForceSyncArguments : Arguments() {
        val user by user {
            name = "user".toKey()
            description = "The user to sync.".toKey()
        }
    }

    class IgnArguments : Arguments() {
        val user by user {
            name = "user".toKey()
            description = "The user to show the IGN for.".toKey()
        }
    }

    class MassSyncRoleArguments : Arguments() {
        val role by role {
            name = "role".toKey()
            description = "The role in which users should be synced.".toKey()
        }
    }

    class MassSyncGuildArguments : Arguments() {
        val guild by string {
            name = "guild".toKey()
            description = "The guild in which users should be synced.".toKey()
        }
    }

    companion object {
        lateinit var scheduler: Scheduler
    }
}

fun ActionRowBuilder.addLinkHelpButton() {
    interactionButton(ButtonStyle.Secondary, "show_help_linking") {
        emoji(ReactionEmoji.Unicode("❔"))
        label = "Help"
    }
}

fun ActionRowBuilder.addLinkButtons() {
    interactionButton(ButtonStyle.Primary, "link_user") {
        emoji(ReactionEmoji.Unicode("\uD83D\uDD17"))
        label = "Link"
    }

    addLinkHelpButton()
}

fun ActionRowBuilder.addSilentLinkButtons() {
    interactionButton(ButtonStyle.Primary, "link_user_silent") {
        emoji(ReactionEmoji.Unicode("\uD83D\uDD17"))
        label = "Link"
    }

    interactionButton(ButtonStyle.Secondary, "show_help_linking") {
        emoji(ReactionEmoji.Unicode("❔"))
        label = "Help"
    }
}

suspend fun ButtonInteraction.sendLinkModal() {
    modal("Link your ingame-account.", "link_ign") {
        actionRow {
            textInput(TextInputStyle.Short, "ign", "Ingame-Name") {
                allowedLength = 3..ApplicationService.MAX_MINECRAFT_USERNAME_LENGTH
                placeholder = "For example: Taubsie"
                required = true
            }
        }
    }
}

suspend fun ButtonInteraction.sendSilentLinkModal() {
    modal("Link your ingame-account.", "link_ign_silent") {
        actionRow {
            textInput(TextInputStyle.Short, "ign", "Ingame-Name") {
                allowedLength = 3..ApplicationService.MAX_MINECRAFT_USERNAME_LENGTH
                placeholder = "For example: Taubsie"
                required = true
            }
        }
    }
}
