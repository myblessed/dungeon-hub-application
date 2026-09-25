package net.dungeonhub.application.listener.ticket

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.asChannelOf
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.channel.edit
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.Member
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.event.interaction.GuildButtonInteractionCreateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.event
import kotlinx.coroutines.launch
import net.dungeonhub.application.commands.TicketSystem
import net.dungeonhub.application.commands.TicketSystem.Companion.isAllowedToClaim
import net.dungeonhub.application.commands.TicketSystem.Companion.isAllowedToUnclaim
import net.dungeonhub.application.commands.TicketSystem.Companion.updateTicketPermissions
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.loader.LoadExtension
import net.dungeonhub.application.service.addEmbed
import net.dungeonhub.application.service.buildEmbed
import net.dungeonhub.application.service.color
import net.dungeonhub.connection.DiscordServerConnection
import net.dungeonhub.connection.TicketConnection
import net.dungeonhub.enums.TicketState
import net.dungeonhub.model.ticket.TicketModel

@LoadExtension
class TicketClaimListener : Extension() {
    override val name = "ticket-claim-listener"
    override suspend fun setup() {
        event<GuildButtonInteractionCreateEvent> {
            check {
                failIfNot(event.interaction.componentId == "claim-ticket")
            }

            action {
                val response = event.interaction.deferEphemeralResponse()

                val ticket = DiscordServerConnection.authenticated().findTickets(event.interaction.guildId.value.toLong(), channelId = event.interaction.channelId.value.toLong())?.firstOrNull()

                val claimAction = resolveTicketClaimAction(
                    ticketExists = ticket != null,
                    claimable = ticket?.ticketPanel?.claimable == true,
                    state = ticket?.state,
                    hasClaimer = ticket?.claimer != null,
                )

                val embed = when (claimAction) {
                    TicketClaimAction.NotATicket -> buildEmbed {
                        description = "This isn't a ticket channel!"
                        color(EmbedColor.Negative)
                    }
                    TicketClaimAction.NotClaimable -> buildEmbed {
                        description = "This panel doesn't allow ticket claiming!"
                        color(EmbedColor.Negative)
                    }
                    TicketClaimAction.Deleted -> buildEmbed {
                        description = "This ticket is already deleted!"
                        color(EmbedColor.Negative)
                    }
                    TicketClaimAction.NotOpen -> buildEmbed {
                        description = "This ticket isn't open!"
                        color(EmbedColor.Negative)
                    }
                    TicketClaimAction.Claim -> claimTicket(event.interaction.user, ticket!!, event.interaction.channel.asChannelOf())
                    TicketClaimAction.Unclaim -> unclaimTicket(event.interaction.user, ticket!!, event.interaction.channel.asChannelOf())
                }

                response.respond {
                    embeds = mutableListOf(embed)
                }
            }
        }
    }

    companion object {
        suspend fun claimTicket(member: Member, ticket: TicketModel, textChannel: TextChannel): EmbedBuilder {
            if(!member.isAllowedToClaim(ticket)) {
                return buildEmbed {
                    description = "You're not allowed to claim this ticket!"
                    color(EmbedColor.Negative)
                }
            }

            val updatedTicket = updateTicketState(ticket, member)

            if(updatedTicket == null) {
                return buildEmbed {
                    description = "Couldn't claim the ticket!"
                    color(EmbedColor.Negative)
                }
            }

            TicketSystem.logTicketAction(member.guild, ticket) {
                description = "Ticket #${ticket.id} claimed by ${member.mention}."
            }

            TicketSystem.scheduler.launch {
                updateTicketChannel(updatedTicket, textChannel)

                val updateTime = TicketSystem.updateTicketName(updatedTicket, member, textChannel)

                // TODO configurable message
                textChannel.createMessage {
                    content = "<@${ticket.user.id}>, your ticket has been claimed by ${member.mention}."
                    addEmbed {
                        description = "Ticket claimed by ${member.mention}.${
                            if (updateTime != null) {
                                "\n-# The ticket name will be updated in $updateTime due to ratelimits."
                            } else {
                                ""
                            }
                        }"
                        color(EmbedColor.Default)
                    }

                    actionRow {
                        TicketSystem.getClaimedButtons().forEach {
                            it()
                        }
                    }
                }
            }

            return buildEmbed {
                description = "You claimed the ticket."
                color(EmbedColor.Positive)
            }
        }

        suspend fun unclaimTicket(member: Member, ticket: TicketModel, textChannel: TextChannel): EmbedBuilder {
            if(!member.isAllowedToUnclaim(ticket)) {
                return buildEmbed {
                    description = "You're not allowed to unclaim this ticket!"
                    color(EmbedColor.Negative)
                }
            }

            val updatedTicket = updateTicketState(ticket, null)

            if(updatedTicket == null) {
                return buildEmbed {
                    description = "Couldn't unclaim the ticket!"
                    color(EmbedColor.Negative)
                }
            }

            TicketSystem.logTicketAction(member.guild, ticket) {
                description = "Ticket #${ticket.id} unclaimed by ${member.mention}."
            }

            TicketSystem.scheduler.launch {
                updateTicketChannel(updatedTicket, textChannel)

                val updateTime = TicketSystem.updateTicketName(updatedTicket, member, textChannel)

                textChannel.createMessage {
                    addEmbed {
                        description = "Ticket unclaimed by ${member.mention}.${
                            if (updateTime != null) {
                                "\n-# The ticket name will be updated in $updateTime due to ratelimits."
                            } else {
                                ""
                            }
                        }"
                        color(EmbedColor.Default)
                    }
                }
            }

            return buildEmbed {
                description = "You unclaimed the ticket."
                color(EmbedColor.Positive)
            }
        }

        suspend fun updateTicketState(ticket: TicketModel, member: Member?): TicketModel? {
            val updateModel = ticket.getUpdateModel()
            updateModel.claimer = member?.id?.value?.toLong()

            return TicketConnection[ticket.ticketPanel.discordServer, ticket.ticketPanel].authenticated().updateTicket(ticket.id, updateModel)
        }

        suspend fun updateTicketChannel(ticket: TicketModel, textChannel: TextChannel) {
            textChannel.edit {
                permissionOverwrites?.clear()
                updateTicketPermissions(ticket.ticketPanel, ticket)

                val categories = if (ticket.state in listOf(TicketState.Creating, TicketState.Open)) {
                    ticket.ticketPanel.openCategories
                } else {
                    ticket.ticketPanel.closedCategories
                }
                TicketSystem.getCategory(categories)?.let { parentId = Snowflake(it) }
            }
        }
    }
}
