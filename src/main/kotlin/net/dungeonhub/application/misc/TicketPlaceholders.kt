package net.dungeonhub.application.misc

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Member
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.effectiveName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import net.dungeonhub.application.connection.DiscordConnection
import net.dungeonhub.application.exceptions.NotLinkedWarning
import net.dungeonhub.connection.CarryDifficultyConnection
import net.dungeonhub.connection.DiscordUserConnection
import net.dungeonhub.connection.TicketConnection
import net.dungeonhub.hypixel.connection.HypixelApiConnection
import net.dungeonhub.model.ticket.TicketModel
import net.dungeonhub.model.ticket_panel.TicketPanelModel
import net.dungeonhub.mojang.connection.MojangConnection

// TODO implement more placeholders
// TODO check if a NotLinkedWarning is thrown anywhere, that could break the placeholder evaluation
class TicketPlaceholders(
    val ticketPanel: TicketPanelModel,
    val ticket: TicketModel,
    val interactionUser: Member,
    val ticketChannel: TextChannel?,
    val transcriptUrl: String? = null,
    val cacheExpiration: Int = 60 * 3
) {
    val ticketUserId = ticket.user.id
    val ticketUserIgn = scheduler.async(start = CoroutineStart.LAZY) {
        ticketUserModel.await()?.minecraftId?.let { MojangConnection.getNameByUUID(it) }
    }

    val ticketUser = scheduler.async(start = CoroutineStart.LAZY) {
        DiscordConnection.bot.kordRef.getUser(Snowflake(ticketUserId))
    }

    val ticketMember = scheduler.async(start = CoroutineStart.LAZY) {
        ticketUser.await()?.asMemberOrNull(Snowflake(ticketPanel.discordServer.id))
    }

    val ticketUserModel = scheduler.async(start = CoroutineStart.LAZY) {
        DiscordUserConnection.authenticated().getByIdOrCreate(ticketUserId)
    }

    val carryTier = ticketPanel.relatedCarryTier

    val formCarryDifficultyName by lazy { ticket.formResponses.firstOrNull { it.customId == "carry-difficulty" }?.value }
    val formCarryDifficulty = scheduler.async(start = CoroutineStart.LAZY) {
        formCarryDifficultyName?.let { formCarryDifficultyName ->
            carryTier?.let { carryTier ->
                CarryDifficultyConnection[carryTier].authenticated().findCarryDifficultyByString(formCarryDifficultyName)
            }
        }
    }
    val carryDifficulty = scheduler.async(start = CoroutineStart.LAZY) {
        formCarryDifficulty.await() ?: ticketPanel.relatedCarryDifficulty
    }

    val formCarryAmount by lazy { ticket.formResponses.firstOrNull { it.customId == "carry-amount" }?.value }

    private val hypixelApiConnection = HypixelApiConnection().withCacheExpiration(cacheExpiration)

    val skyblockProfiles = scheduler.async(start = CoroutineStart.LAZY) {
        ticketUserModel.await()?.minecraftId?.let {
            hypixelApiConnection.getSkyblockProfiles(it).valueOrNull?.profiles
        }
    }

    val selectedSkyblockProfiles = scheduler.async(start = CoroutineStart.LAZY) {
        skyblockProfiles.await()?.filter { ticketUserModel.await()?.primarySkyblockProfile == null || it.profileId == ticketUserModel.await()?.primarySkyblockProfile }
            ?.takeIf { it.isNotEmpty() }
            ?: skyblockProfiles.await()
    }

    val replacements: Map<String, suspend () -> String>
        get() {
            val replacements: MutableMap<String, suspend () -> String> = HashMap()

            replacements["user.id"] = { ticketUserId.toString() }
            replacements["user.mention"] = { "<@${ticketUserId}>" }
            replacements["claimer.mention"] = {
                if(ticket.claimer != null) {
                    "<@${ticket.claimer?.id}>"
                } else {
                    "unknown"
                }
            }
            replacements["user.name"] = { ticketUser.await()?.username ?: "unknown" }
            replacements["user.globalName"] = { ticketUser.await()?.effectiveName ?: "unknown" }
            replacements["user.displayName"] = { ticketMember.await()?.effectiveName ?: "unknown" }
            replacements["interactionUser.id"] = { interactionUser.id.value.toString() }
            replacements["interactionUser.mention"] = { interactionUser.mention }
            replacements["interactionUser.name"] = { interactionUser.username }
            replacements["interactionUser.displayName"] = { interactionUser.effectiveName }
            replacements["user.minecraft.name"] = { ticketUserIgn.await() ?: "unlinked" }
            replacements["user.skyblock.level"] = {
                selectedSkyblockProfiles.await()?.maxOfOrNull {
                    it.getCurrentMember(ticketUserModel.await()?.minecraftId ?: throw NotLinkedWarning())?.leveling?.level
                        ?: 0
                }?.toString() ?: "?"
            }
            replacements["user.catacombs.level"] = {
                selectedSkyblockProfiles.await()?.maxOfOrNull {
                    it.getCurrentMember(
                        ticketUserModel.await()?.minecraftId ?: throw NotLinkedWarning()
                    )?.dungeons?.catacombsLevel
                        ?: 0
                }?.toString() ?: "?"
            }
            replacements["panel.name"] = { ticketPanel.displayName ?: ticketPanel.name }
            replacements["ticket.id"] = { ticket.id.toString() }
            replacements["ticket.count"] = { // TODO custom endpoint
                val allTickets = TicketConnection[ticketPanel.discordServer, ticketPanel].authenticated().getAllTickets() ?: emptyList()

                val count = allTickets.map { it.id }.sorted().indexOf(ticket.id) + 1

                count.toString()
            }
            replacements["ticket.name"] = { ticketChannel?.name ?: "not-set" }
            replacements["transcript.url"] = { transcriptUrl ?: "unknown" }
            replacements["carry-tier.name"] = { carryTier?.displayName ?: "unknown" }
            replacements["carry-difficulty.name"] = { carryDifficulty.await()?.displayName ?: "unknown" }
            replacements["ticket.form.1"] = { ticket.formResponses.firstOrNull { it.ordinal == 0 }?.value ?: "unknown" }
            replacements["ticket.form.2"] = { ticket.formResponses.firstOrNull { it.ordinal == 1 }?.value ?: "unknown" }
            replacements["ticket.form.3"] = { ticket.formResponses.firstOrNull { it.ordinal == 2 }?.value ?: "unknown" }
            replacements["ticket.form.4"] = { ticket.formResponses.firstOrNull { it.ordinal == 3 }?.value ?: "unknown" }
            replacements["ticket.form.5"] = { ticket.formResponses.firstOrNull { it.ordinal == 4 }?.value ?: "unknown" }
            replacements["ticket.form.carry-difficulty"] = { formCarryDifficulty.await()?.displayName ?: "unknown" }
            replacements["ticket.form.carry-amount"] = { formCarryAmount ?: "unknown" }

            return replacements
        }

    companion object {
        val scheduler = DhScheduler()
    }
}