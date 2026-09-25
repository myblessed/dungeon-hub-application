package net.dungeonhub.application.listener

import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.builder.message.actionRow
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.event
import dev.kordex.core.utils.addReaction
import dev.kordex.core.utils.dm
import dev.kordex.core.utils.scheduling.Scheduler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dungeonhub.application.commands.LoggingSystem
import net.dungeonhub.application.connection.DiscordConnection
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.enums.ServerProperty
import net.dungeonhub.application.event.TicketTranscriptCreatedEvent
import net.dungeonhub.application.loader.LoadExtension
import net.dungeonhub.application.misc.DhScheduler
import net.dungeonhub.application.service.buildEmbed
import net.dungeonhub.application.service.color
import net.dungeonhub.connection.QueueConnection
import net.dungeonhub.enums.QueueStep
import net.dungeonhub.model.carry_queue.CarryQueueModel
import net.dungeonhub.model.ticket.TicketModel
import org.slf4j.LoggerFactory
import java.util.*

@LoadExtension
class MessageListener : Extension() {
    private lateinit var scheduler: Scheduler

    companion object {
        private const val APPROVE_AMOUNT_THRESHOLD: Long = 11
        private const val APPROVE_SCORE_THRESHOLD: Long = 34
        private val mutex = Mutex()

        private val logger = LoggerFactory.getLogger(MessageListener::class.java)
    }

    override val name = "message-listener"
    @OptIn(PrivilegedIntent::class)
    override val intents = mutableSetOf<Intent>(Intent.MessageContent)

    override suspend fun setup() {
        scheduler = DhScheduler()

        event<MessageCreateEvent> {
            action {
                scheduler.launch {
                    addReactionToPets(event)
                }
            }
        }

        event<TicketTranscriptCreatedEvent> {
            action {
                scheduler.launch {
                    logDungeonHubTicket(event.ticket, event.transcriptUrl)
                }
            }
        }
    }

    suspend fun logDungeonHubTicket(ticket: TicketModel, transcriptUrl: String) {
        val server = kord.getGuildOrNull(Snowflake(ticket.ticketPanel.discordServer.id)) ?: return

        val approvingChannel = ServerProperty.LOG_APPROVING_CHANNEL.getValue(server.id.value.toLong())
            ?.let { DiscordConnection.bot.kordRef.getChannelOf<TextChannel>(Snowflake(it)) }

        val channelId = ticket.channel?.id

        if(channelId == null) {
            logger.error("Channel ID is null for ticket #${ticket.id}")
            return
        }

        mutex.withLock {
            val allCarryQueues = QueueConnection.authenticated().getCarryQueueByRelatedIdAndQueueStep(channelId, QueueStep.Transcript) ?: return

            val queueEntries = allCarryQueues.mapNotNull { queueModel ->
                val updateModel = queueModel.getUpdateModel()
                updateModel.attachmentLink = transcriptUrl

                QueueConnection.authenticated().updateQueue(queueModel.id, updateModel) ?: run {
                    logger.error("Failed to set attachment link for carry queue ${queueModel.id}")
                    null
                }
            }

            for((carrierId, queues) in queueEntries.groupBy { it.carrier.id }) {
                val totalAmount = queues.sumOf { it.amount }
                val totalScore = queues.sumOf { it.calculateScore() }

                val needsApproval = approvingChannel != null && (totalAmount >= APPROVE_AMOUNT_THRESHOLD || totalScore >= APPROVE_SCORE_THRESHOLD)

                if (needsApproval) {
                    sendToApproving(carrierId, queues, approvingChannel)
                } else {
                    LoggingSystem.logDirectly(carrierId, queues, server, null)
                }
            }
        }
    }

    private suspend fun sendToApproving(carrierId: Long, queueEntries: List<CarryQueueModel>, approvingChannel: TextChannel) {
        for(group in LoggingSystem.compactCarryEntries(queueEntries)) {
            val createdMessage = approvingChannel.createMessage {
                val embed = LoggingSystem.loadGroupedCarryEmbed(group)
                embed.title = "Accept carry-log?"
                embed.color = EmbedColor.Default.color

                embeds = mutableListOf(embed)

                actionRow {
                    interactionButton(ButtonStyle.Success, "accept_log") {
                        label = "Accept"
                    }

                    if(group.size == 1) {
                        interactionButton(ButtonStyle.Primary, "adjust_carry_amount") {
                            label = "Adjust amount"
                        }
                    }

                    interactionButton(ButtonStyle.Danger, "deny") {
                        label = "Deny"
                    }
                }
            }

            for (queueModel in group) {
                val updateModel = queueModel.getUpdateModel()
                updateModel.queueStep = QueueStep.Approving
                updateModel.relationId = createdMessage.id.value.toLong()

                val response = QueueConnection.authenticated().updateQueue(queueModel.id, updateModel)

                if(response == null) {
                    logger.error("Failed to update queue ${queueModel.id}")
                }
            }
        }

        sendApprovalDms(carrierId, queueEntries)
    }

    private fun sendApprovalDms(carrierId: Long, queueEntries: List<CarryQueueModel>) {
        val totalAmount = queueEntries.sumOf { it.amount }
        val totalScore = queueEntries.sumOf { it.calculateScore() }

        val carrySummary = queueEntries
            .groupBy { it.carryDifficulty.id }
            .values
            .joinToString("\n") { difficultyQueues ->
                val representative = difficultyQueues.first()

                "- ${difficultyQueues.sumOf { it.amount }}x " +
                        "${representative.carryTier.displayName} - " +
                        "${representative.carryDifficulty.displayName} " +
                        "(${difficultyQueues.sumOf { it.calculateScore() }} score)"
            }

        scheduler.launch {
            DiscordConnection.bot.kordRef.getUser(Snowflake(carrierId))?.dm {
                val embed = buildEmbed {
                    color(EmbedColor.Information)
                    title = "Approval needed"
                    description =
                        "Your carry-log request has to be manually approved by " +
                                "our server's staff team.\n\n" +
                                "**Total carries:** $totalAmount\n" +
                                "**Total score:** $totalScore\n\n" +
                                "**Logs awaiting approval:**\n$carrySummary\n\n" +
                                "You will be notified here once they have been approved or denied."
                }
                embeds = mutableListOf(embed)
            }
        }
    }

    override suspend fun unload() {
        scheduler.cancel("Extension shutting down.")
    }

    private suspend fun addReactionToPets(event: MessageCreateEvent) {
        if (event.guildId != null) {
            val serverId = event.guildId!!
            val channelId = event.message.channel.id
            if ((serverId.value.toLong() == 1023684107877761196L && channelId.value.toLong() == 1220895875102937098L)
                || (serverId.value.toLong() == 693263712626278553L && channelId.value.toLong() == 1219427157655289908L)
            ) {
                if (event.message.attachments.isEmpty() && event.message.author?.isBot == false &&
                    event.message.embeds.stream()
                        .map { embed -> embed.thumbnail }
                        .filter { it != null }
                        .findFirst().isEmpty
                ) {
                    return
                }

                val emoji: String = getRandomEmoji()

                event.message.addReaction(emoji)
            }
        }
    }

    private fun getEmojiPool(): List<String> {
        return listOf(
            "Woah:1220111116651204608",
            "woah:1220111081150615572",
            "girlwow:1220111157742800956",
            "catelove:1204407157848678430",
            "ZTcool:1204406493353353256",
            "pepega:697756021048868894",
            "smikecate:1204406375791333426",
            "poggorfish:694270485613117480"
        )
    }

    private fun getRandomEmoji(): String {
        val bound = 101.0

        val emojiPool = getEmojiPool()

        val random = Random().nextInt(bound.toInt())

        var emoji = emojiPool[(random * (emojiPool.size / bound)).toInt()]

        if (random == 100) {
            emoji = "swag:708383726370947132"
        }

        return emoji
    }
}
