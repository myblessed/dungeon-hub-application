package net.dungeonhub.application.commands

import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.TextInputStyle
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.entity.component.TextInputComponent
import dev.kord.core.event.interaction.GuildButtonInteractionCreateEvent
import dev.kord.core.event.interaction.GuildModalSubmitInteractionCreateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kordex.core.commands.Arguments
import dev.kordex.core.commands.converters.impl.int
import dev.kordex.core.commands.converters.impl.string
import dev.kordex.core.components.components
import dev.kordex.core.components.ephemeralButton
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.event
import dev.kordex.core.extensions.publicSlashCommand
import dev.kordex.core.i18n.toKey
import dev.kordex.core.utils.dm
import dev.kordex.core.utils.scheduling.Scheduler
import kotlinx.coroutines.launch
import net.dungeonhub.application.connection.DiscordConnection
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.enums.ServerProperty
import net.dungeonhub.application.exceptions.CommandExecutionException
import net.dungeonhub.application.exceptions.CommandExecutionWarning
import net.dungeonhub.application.exceptions.InvalidOptionException
import net.dungeonhub.application.exceptions.MissingPermissionException
import net.dungeonhub.application.loader.LoadExtension
import net.dungeonhub.application.misc.DhScheduler
import net.dungeonhub.application.misc.LoggedQueueEntry
import net.dungeonhub.application.service.*
import net.dungeonhub.connection.CarryDifficultyConnection
import net.dungeonhub.connection.DiscordServerConnection
import net.dungeonhub.connection.QueueConnection
import net.dungeonhub.connection.ScoreConnection
import net.dungeonhub.enums.QueueStep
import net.dungeonhub.enums.ScoreType
import net.dungeonhub.i18n.Translations.Command.Log
import net.dungeonhub.i18n.Translations.CommonArguments
import net.dungeonhub.model.carry_queue.CarryQueueCreationModel
import net.dungeonhub.model.carry_queue.CarryQueueModel
import net.dungeonhub.model.carry_type.CarryTypeModel
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.toKotlinInstant

@LoadExtension
class LoggingSystem : Extension() {
    override val name = "logging-system"

    override suspend fun setup() {
        publicSlashCommand(::LogArguments) {
            name = Log.name
            description = Log.description
            allowInDms = false

            action {
                respond {
                    val ticket = DiscordServerConnection.authenticated().findTickets(guild!!.id.value.toLong(), channelId = channel.id.value.toLong())?.firstOrNull()

                    val carryTier = ticket?.ticketPanel?.relatedCarryTier
                        ?: throw CommandExecutionWarning("Please use this in a ticket connected to a carry tier. If you think this is incorrect, tell the administrators to check [the documentation](https://docs.dungeon-hub.net/) about setting up the bot on [the dashboard](https://dashboard.dungeon-hub.net/).")

                    val alreadyPresentQueue = QueueConnection.authenticated().getCarryQueueByRelatedIdAndQueueStep(
                        channel.id.value.toLong(),
                        QueueStep.Confirmation
                    )?.firstOrNull()

                    if (alreadyPresentQueue != null) {
                        val embed = ApplicationService.embed
                        embed.color = EmbedColor.Negative.color
                        embed.description = " Someone is already logging this carry.\n" +
                                "If you think this is a mistake, clear the log using the buttons below.\n" +
                                "Otherwise, simply click dismiss to delete this message."

                        embeds = mutableListOf(embed)

                        components {
                            ephemeralButton {
                                label = "Clear log".toKey()
                                style = ButtonStyle.Primary

                                action {
                                    respond innerrespond@{
                                        val carryQueue =
                                            QueueConnection.authenticated().getCarryQueueByRelatedIdAndQueueStep(
                                                channel.id.value.toLong(),
                                                QueueStep.Confirmation
                                            )?.firstOrNull()

                                        if (carryQueue == null) {
                                            val innerEmbed = ApplicationService.embed
                                            innerEmbed.color = EmbedColor.Information.color
                                            innerEmbed.description = "That log request was already cleared."

                                            embeds = mutableListOf(innerEmbed)

                                            return@innerrespond
                                        }

                                        QueueConnection.authenticated().deleteQueue(carryQueue.id)

                                        val innerEmbed = ApplicationService.embed
                                        innerEmbed.color = EmbedColor.Positive.color
                                        innerEmbed.description = "The log request was cleared, you can now log again!"

                                        embeds = mutableListOf(innerEmbed)

                                        message.delete()
                                    }
                                }
                            }

                            ephemeralButton {
                                label = "Dismiss".toKey()
                                style = ButtonStyle.Danger

                                deferredAck = true

                                action {
                                    event.interaction.message.delete()
                                }
                            }
                        }

                        return@respond
                    }

                    val carryDifficulty = CarryDifficultyConnection[carryTier].authenticated()
                        .findCarryDifficultyByString(arguments.carryDifficulty)

                    if (carryDifficulty == null) {
                        embeds = mutableListOf(
                            ApplicationService.getErrorEmbed(
                                InvalidOptionException(
                                    "carry-difficulty",
                                    "`${arguments.carryDifficulty}` is no valid type."
                                )
                            )
                        )
                        return@respond
                    }

                    val carried = ticket.user.id

                    val time = Instant.now()

                    val creationModel = CarryQueueCreationModel(
                        queueStep = QueueStep.Confirmation,
                        time = time,
                        amount = arguments.carryAmount,
                        player = carried,
                        carrier = user.id.value.toLong(),
                        relationId = channel.id.value.toLong()
                    )

                    val carryQueueModel = QueueConnection.authenticated().addNewQueue(carryDifficulty, creationModel)
                        ?: throw CommandExecutionException(
                            "Unable to log this. Please contact an administrator of this bot."
                        )

                    val embed = ApplicationService.loadEmbedFromCarryQueue(carryQueueModel)
                    embed.title = "Are you sure that you want to log this?"

                    embeds = mutableListOf(embed)

                    actionRow {
                        interactionButton(ButtonStyle.Success, "send_log") {
                            label = "Confirm"
                        }

                        interactionButton(ButtonStyle.Danger, "discard") {
                            label = "Cancel"
                        }
                    }
                }
            }
        }

        event<GuildButtonInteractionCreateEvent> {
            check {
                failIfNot(listOf("send_log", "discard", "accept_log", "deny", "adjust_carry_amount").contains(event.interaction.componentId))
            }

            action {
                when (event.interaction.componentId) {
                    "send_log" -> sendLog(event)
                    "discard" -> discard(event)
                    "accept_log" -> acceptLog(event)
                    "deny" -> deny(event)
                    "adjust_carry_amount" -> adjustCarryAmount(event)
                }
            }
        }

        event<GuildModalSubmitInteractionCreateEvent> {
            check {
                failIfNot(listOf("adjust_carry_amount").contains(event.interaction.modalId))
            }

            action {
                val value = (event.interaction.responseComponents["amount"] as? TextInputComponent)?.value?.trim()?.toIntOrNull()

                if(value == null || value <= 0) {
                    event.interaction.respondEphemeral {
                        addEmbed {
                            description = "Please enter a positive number!"
                            color(EmbedColor.Negative)
                        }
                    }
                    return@action
                }

                val message = event.interaction.message

                if(message == null) {
                    event.interaction.respondEphemeral {
                        addEmbed {
                            description = "Couldn't find a related message. Report this!"
                            color(EmbedColor.Negative)
                        }
                    }
                    return@action
                }

                val carryQueues = QueueConnection.authenticated().getCarryQueueByRelatedIdAndQueueStep(
                    message.id.value.toLong(),
                    QueueStep.Approving
                ) ?: HashSet()

                if(carryQueues.size != 1) {
                    event.interaction.respondEphemeral {
                        addEmbed {
                            description = "Found less or more than 1 queued carries. Report this!"
                            color(EmbedColor.Negative)
                        }
                    }
                    return@action
                }

                val carryQueue = carryQueues.first()

                val updateModel = carryQueue.getUpdateModel()
                updateModel.amount = value
                val response = QueueConnection.authenticated().updateQueue(carryQueue.id, updateModel)

                if(response == null) {
                    event.interaction.respondEphemeral {
                        addEmbed {
                            description = "Couldn't update the carry queue. Report this!"
                            color(EmbedColor.Negative)
                        }
                    }
                    return@action
                }

                event.interaction.respondEphemeral {
                    addEmbed {
                        description = "Queue updated!"
                        field("Old amount", true) {
                            "${carryQueue.amount}"
                        }
                        field("New amount", true) {
                            "${response.amount}"
                        }
                    }
                }

                ServerProperty.SCORE_LOGS_CHANNEL
                    .getValue(event.interaction.guild.id.value.toLong())
                    ?.let { id: String ->
                        event.interaction.guild.getChannelOfOrNull<GuildMessageChannel>(Snowflake(id))
                    }
                    ?.let { serverTextChannel ->
                        serverTextChannel.createMessage {
                            val embed = ApplicationService.loadEmbedFromCarryQueue(carryQueue)
                            embed.color(EmbedColor.Default)
                            embed.title = "Carry amount changed"
                            embed.field("Changed by", true) { event.interaction.user.mention }
                            embed.field("Old amount", true) { "${carryQueue.amount}" }
                            embed.field("New amount", true) { "${response.amount}" }
                            embeds = mutableListOf(embed)
                        }
                    }

                message.edit {
                    val embed = loadGroupedCarryEmbed(listOf(response))
                    embed.title = "Accept carry-log?"
                    embed.color = EmbedColor.Default.color

                    embeds = mutableListOf(embed)

                    actionRow {
                        interactionButton(ButtonStyle.Success, "accept_log") {
                            label = "Accept"
                        }

                        interactionButton(ButtonStyle.Primary, "adjust_carry_amount") {
                            label = "Adjust amount"
                        }

                        interactionButton(ButtonStyle.Danger, "deny") {
                            label = "Deny"
                        }
                    }
                }
            }
        }
    }

    private suspend fun adjustCarryAmount(event: GuildButtonInteractionCreateEvent) {
        event.interaction.modal("Adjust amount", "adjust_carry_amount") {
            label("Amount") {
                textInput(TextInputStyle.Short, "amount") {
                    placeholder = "Enter a new amount here"
                    required = true
                }
            }
        }
    }

    private suspend fun deny(event: GuildButtonInteractionCreateEvent) {
        event.interaction.deferPublicMessageUpdate()

        val message = event.interaction.message

        val carryQueues = QueueConnection.authenticated().getCarryQueueByRelatedIdAndQueueStep(
            message.id.value.toLong(),
            QueueStep.Approving
        ) ?: HashSet()

        for ((carrierId, queueEntries) in groupCarryEntriesByCarrier(carryQueues)) {
            val carrier = event.kord.getUser(Snowflake(carrierId))

            carrier?.dm {
                content = denialNotificationContent(queueEntries.size, event.interaction.user.mention)
                val embed = createCarryOverview(compactCarryEntries(queueEntries), null)
                embed.color = EmbedColor.Negative.color
                embeds = mutableListOf(embed)
            }
        }

        for (queueModel in carryQueues) {
            ServerProperty.SCORE_LOGS_CHANNEL
                .getValue(event.interaction.guild.id.value.toLong())
                ?.let { id: String ->
                    event.interaction.guild.getChannelOfOrNull<GuildMessageChannel>(Snowflake(id))
                }
                ?.let { serverTextChannel ->
                    serverTextChannel.createMessage {
                        val embed = ApplicationService.loadEmbedFromCarryQueue(queueModel)
                        embed.color = EmbedColor.Negative.color
                        embed.title = "Carry denied"
                        embed.field("Denied by", true) { event.interaction.user.mention }

                        embeds = mutableListOf(embed)
                    }
                }

            logger.debug("Carry denied: {}", queueModel)

            QueueConnection.authenticated().deleteQueue(queueModel.id)
        }

        message.delete()
    }

    private suspend fun acceptLog(event: GuildButtonInteractionCreateEvent) {
        event.interaction.deferPublicMessageUpdate()

        val message = event.interaction.message

        val carryQueues = QueueConnection.authenticated()
            .getCarryQueueByRelatedIdAndQueueStep(message.id.value.toLong(), QueueStep.Approving) ?: HashSet()

        val approver = event.interaction.user.id.value.toLong()

        for((carrierId, queueEntries) in groupCarryEntriesByCarrier(carryQueues)) {
            logDirectly(carrierId, queueEntries, event.interaction.guild, approver)
        }

        message.delete()
    }

    private suspend fun sendLog(event: GuildButtonInteractionCreateEvent) {
        val response = event.interaction.deferEphemeralResponse()

        val channel = event.interaction.channel

        val carryQueue = QueueConnection.authenticated()
            .getCarryQueueByRelatedIdAndQueueStep(channel.id.value.toLong(), QueueStep.Confirmation)
            ?.firstOrNull()

        if (carryQueue == null) {
            response.respond {
                embeds = mutableListOf(
                    ApplicationService.getErrorEmbed(CommandExecutionException("Carry isn't in queue anymore. Please discard and log this again!"))
                )
            }
            return
        }

        if (carryQueue.carrier.id != event.interaction.user.id.value.toLong()) {
            response.respond {
                embeds = mutableListOf(
                    ApplicationService.getErrorEmbed(MissingPermissionException())
                )
            }
            return
        }

        val updateModel = carryQueue.getUpdateModel()
        updateModel.queueStep = QueueStep.Transcript

        val carryQueueModel = QueueConnection.authenticated().updateQueue(carryQueue.id, updateModel)

        if (carryQueueModel == null) {
            response.respond {
                content = "Couldn't log this ticket. Please contact an administrator."
            }

            logger.error("Error logging ticket '{}'.", channel.id)
            return
        }

        response.respond {
            content =
                "**Thank you for your service. Your carry will be sent to the staff team for review once the ticket is closed.**\n" +
                        "**You will be notified once it has been reviewed.**\n" +
                        "If the client doesn't want any more carries, please delete this ticket."

            channel.createMessage {
                embeds = mutableListOf(
                    ApplicationService.loadTicketNotificationFromCarryQueue(carryQueueModel)
                )
            }

            event.interaction.message.delete()
        }
    }

    private suspend fun discard(event: GuildButtonInteractionCreateEvent) {
        val response = event.interaction.deferEphemeralResponse()

        val channel = event.interaction.channel

        val carryQueue = QueueConnection.authenticated()
            .getCarryQueueByRelatedId(channel.id.value.toLong())
            ?.firstOrNull()

        if (carryQueue == null) {
            response.respond {
                content = "Carry isn't in queue anymore. Please log this again!"
            }

            event.interaction.message.delete("Carry not in queue - weird...")
            return
        }

        if (!PermissionService.mayManageServices(event.interaction.user) && carryQueue.carrier.id != event.interaction.user.id.value.toLong()) {
            response.respond {
                embeds = mutableListOf(ApplicationService.getErrorEmbed(MissingPermissionException()))
            }
            return
        }

        response.respond {
            content = "Log discarded!"
        }

        QueueConnection.authenticated().deleteQueue(carryQueue.id)

        event.interaction.message.delete()
    }

    class LogArguments : Arguments() {
        val carryDifficulty by string {
            name = CommonArguments.CarryDifficulty.name
            description = Log.Arguments.CarryDifficulty.description
            autoCompleteCallback = AutoCompletionService.carryDifficulty
        }

        val carryAmount by int {
            name = Log.Arguments.Amount.name
            description = Log.Arguments.Amount.description
            minValue = 1
            maxValue = 200
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LoggingSystem::class.java)
        private val scheduler: Scheduler by lazy { DhScheduler() }

        fun compactCarryEntries(queueEntries: Collection<CarryQueueModel>): List<List<CarryQueueModel>> {
            val groups = mutableListOf<MutableList<CarryQueueModel>>()

            for (queueModel in queueEntries.sortedBy { it.time?.toKotlinInstant() }) {
                val previousQueue = groups.lastOrNull()?.lastOrNull()

                val canJoinPreviousGroup =
                    previousQueue != null &&
                            previousQueue.amount == 1 &&
                            queueModel.amount == 1 &&
                            previousQueue.carrier.id == queueModel.carrier.id &&
                            previousQueue.carryDifficulty.id == queueModel.carryDifficulty.id

                if (canJoinPreviousGroup) {
                    groups.last().add(queueModel)
                } else {
                    groups.add(mutableListOf(queueModel))
                }
            }

            return groups
        }

        fun groupCarryEntriesByCarrier(
            queueEntries: Collection<CarryQueueModel>
        ): Map<Long, List<CarryQueueModel>> = queueEntries.groupBy { it.carrier.id }

        fun denialNotificationContent(queueCount: Int, approverMention: String): String =
            "Your ${if (queueCount == 1) "log was" else "logs were"} denied by $approverMention."

        fun sendLoggedDms(carrierId: Long, loggedEntries: Collection<LoggedQueueEntry>, approver: Long?) {
            if(loggedEntries.isEmpty()) return

            val latestScore = loggedEntries.last().updatedScore
            val allQueues = loggedEntries.flatMap { it.queues }

            scheduler.launch {
                DiscordConnection.bot.kordRef.getUser(Snowflake(carrierId))?.dm {
                    content = "Your ${if (allQueues.size == 1) "carry was" else "carries were"} " +
                            "logged!\n\n**Your Updated Score:** $latestScore"

                    embeds = mutableListOf(
                        createCarryOverview(compactCarryEntries(allQueues), approver)
                    )
                }
            }
        }

        fun loadGroupedCarryEmbed(
            queueEntries: Collection<CarryQueueModel>
        ) = buildEmbed {
            val representative = queueEntries.first()
            val last = queueEntries.last()

            timestamp = last.time?.toKotlinInstant()
            color = EmbedColor.Information.color

            field("Number of carries", true) {
                queueEntries.sumOf { it.amount }.toString()
            }

            field("Type of carry", true) {
                "${representative.carryTier.displayName} - " +
                        representative.carryDifficulty.displayName
            }

            val players = queueEntries.distinctBy { it.player.id }

            if(players.size > 1) {
                field("Players", true) {
                    players.joinToString("\n") { "<@${it.player.id}>" }
                }
            } else {
                field("Player", true) {
                    "<@${players[0].player.id}>"
                }
            }

            field("Carrier", true) {
                "<@${representative.carrier.id}>"
            }

            field("Gained score", true) {
                queueEntries.sumOf { it.calculateScore() }.toString()
            }

            representative.attachmentLink?.let { transcriptUrl ->
                field("Transcript-Link", true) {
                    "[Click to open]($transcriptUrl)"
                }
            }
        }

        fun createCarryOverview(queueEntries: List<List<CarryQueueModel>>, approver: Long?): EmbedBuilder = buildEmbed {
            val last = queueEntries.last().last()
            timestamp = last.time?.toKotlinInstant()
            title = "Information"
            color(EmbedColor.Default)

            description = queueEntries.joinToString("\n") { shownEntries ->
                val representative = shownEntries.first()

                val players = shownEntries.distinctBy { it.player.id }
                    .joinToString(", ") { "<@${it.player.id}>" }

                "- ${shownEntries.sumOf { it.amount }} ${representative.carryTier.displayName} - ${representative.carryDifficulty.displayName} for $players (${shownEntries.sumOf { it.calculateScore() }} score)"
            }

            last.attachmentLink?.let { transcriptUrl ->
                field("Transcript-Link", true) {
                    "[Click to open]($transcriptUrl)"
                }
            }

            if(approver != null) {
                field("Approved by", true) {
                    "<@$approver>"
                }
            }
        }

        suspend fun logDirectly(carrierId: Long, queueEntries: List<CarryQueueModel>, server: GuildBehavior, approver: Long?) {
            val carryTypes = mutableListOf<CarryTypeModel>()
            val loggedEntries = mutableListOf<LoggedQueueEntry>()

            for (group in compactCarryEntries(queueEntries)) {
                val representative = group.first()
                val successfullyLogged = mutableListOf<CarryQueueModel>()
                var latestScore = 0L

                for (queueModel in group) {
                    val updateModel = queueModel.getUpdateModel()
                    if(approver != null) {
                        updateModel.approver = approver
                    }

                    val loggedCarryModel = QueueConnection.authenticated()
                        .logQueue(queueModel.id, updateModel)

                    if (loggedCarryModel == null) {
                        logger.error("Failed to log carry queue {}", queueModel.id)
                        continue
                    }

                    successfullyLogged.add(queueModel)
                    carryTypes.add(queueModel.carryType)

                    latestScore = loggedCarryModel.scoreModels
                        .firstOrNull { it.scoreType == ScoreType.Default }
                        ?.scoreAmount
                        ?: ScoreConnection[queueModel.carryType]
                            .authenticated()
                            .getScore(queueModel.carrier.id)
                            ?.scoreAmount
                                ?: 0
                }

                if (successfullyLogged.isEmpty()) {
                    continue
                }

                loggedEntries.add(
                    LoggedQueueEntry(
                        queues = successfullyLogged,
                        updatedScore = latestScore
                    )
                )

                representative.carryTier
                    .carryType
                    .logChannel
                    ?.let { id ->
                        server.getChannelOfOrNull<GuildMessageChannel>(Snowflake(id))
                    }?.createMessage {
                        val embed = loadGroupedCarryEmbed(successfullyLogged)
                        embed.title = "Carry accepted."
                        embed.color(EmbedColor.Positive)

                        embeds = mutableListOf(embed)
                    }
            }

            sendLoggedDms(carrierId, loggedEntries, approver)

            scheduler.launch {
                StaticMessageService.updateScoreLeaderboard(carryTypes.distinctBy { it.id })
            }
        }
    }
}
