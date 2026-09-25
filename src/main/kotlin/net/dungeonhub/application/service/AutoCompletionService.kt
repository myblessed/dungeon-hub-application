package net.dungeonhub.application.service

import dev.kord.common.entity.Choice
import dev.kord.common.entity.optional.Optional
import dev.kord.core.behavior.interaction.suggest
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import dev.kordex.core.commands.converters.AutoCompleteCallback
import net.dungeonhub.application.enums.KnownStaticResource
import net.dungeonhub.application.enums.ServerProperty
import net.dungeonhub.connection.*
import net.dungeonhub.hypixel.connection.HypixelApiConnection
import net.dungeonhub.model.carry_tier.CarryTierModel
import net.dungeonhub.model.ticket.TicketModel
import net.dungeonhub.mojang.connection.MojangConnection

object AutoCompletionService {
    val carryType: AutoCompleteCallback = { event ->
        suggest(
            CarryTypeConnection[event.getGuildId()].authenticated()
                .getAllCarryTypes()
                ?.filter { carryType ->
                    focusedOption.value.isEmpty()
                            || (carryType.identifier.contains(focusedOption.value, true)
                            || carryType.displayName.contains(focusedOption.value, true))
                }
                ?.map { carryType ->
                    Choice.StringChoice(
                        name = carryType.displayName,
                        value = carryType.identifier,
                        nameLocalizations = Optional()
                    )
                } ?: listOf()
        )
    }

    val carryTier: AutoCompleteCallback = { event ->
        val allOptions = event.interaction.command.options

        val carryType: String? = allOptions.filter { entry ->
            entry.key.equals("carry-type", ignoreCase = true)
        }.values.firstOrNull()?.value as String?

        if (carryType != null) {
            suggest(
                CarryTypeConnection[event.getGuildId()].authenticated()
                    .findCarryTypeByString(carryType)
                    ?.let { carryTypeModel ->
                        CarryTierConnection[carryTypeModel].authenticated().getAllCarryTiers()
                    }
                    ?.filter { carryTier ->
                        focusedOption.value.isEmpty()
                                || (carryTier.identifier.contains(focusedOption.value, true)
                                || carryTier.displayName.contains(focusedOption.value, true))
                    }
                    ?.map { carryTier ->
                        Choice.StringChoice(
                            name = carryTier.displayName,
                            value = carryTier.identifier,
                            nameLocalizations = Optional()
                        )
                    } ?: listOf()
            )
        }
    }

    val carryDifficulty: AutoCompleteCallback = { event ->
        val allOptions = event.interaction.command.options

        val carryType: String? = allOptions.filter { entry ->
            entry.key.equals("carry-type", ignoreCase = true)
        }.values.firstOrNull()?.value as String?

        val carryTier: String? = allOptions.filter { entry ->
            entry.key.equals("carry-tier", ignoreCase = true)
        }.values.firstOrNull()?.value as String?

        if (carryType != null && carryTier != null) {
            val carryTierModel =
                CarryTypeConnection[event.getGuildId()].authenticated()
                    .findCarryTypeByString(carryType)
                    ?.let {
                        CarryTierConnection[it].authenticated().findCarryTierByString(carryTier)
                    }

            if (carryTierModel != null) {
                suggest(
                    CarryDifficultyConnection[carryTierModel].authenticated()
                        .getAllCarryDifficulties()
                        ?.filter { carryDifficulty ->
                            focusedOption.value.isEmpty()
                                    || (carryDifficulty.identifier.contains(focusedOption.value, true)
                                    || carryDifficulty.displayName.contains(focusedOption.value, true))
                        }
                        ?.map { carryDifficulty ->
                            Choice.StringChoice(
                                name = carryDifficulty.displayName,
                                value = carryDifficulty.identifier,
                                nameLocalizations = Optional()
                            )
                        } ?: listOf()
                )
            }
        } else {
            val guildId = event.getGuildId()

            val ticket = DiscordServerConnection.authenticated().findTickets(guildId, channelId = channel.id.value.toLong())?.firstOrNull()

            val ticketCarryTier = ticket?.let { getCarryTierFromTicket(it) }

            if (ticketCarryTier != null) {
                suggest(
                    CarryDifficultyConnection[ticketCarryTier].authenticated()
                        .getAllCarryDifficulties()
                        ?.filter { carryDifficulty ->
                            focusedOption.value.isEmpty()
                                    || (carryDifficulty.identifier.contains(focusedOption.value, true)
                                    || carryDifficulty.displayName.contains(focusedOption.value, true))
                        }
                        ?.map { carryDifficulty ->
                            Choice.StringChoice(
                                name = carryDifficulty.displayName,
                                value = carryDifficulty.identifier,
                                nameLocalizations = Optional()
                            )
                        } ?: listOf()
                )
            }
        }
    }

    val purgeType: AutoCompleteCallback = { event ->
        val allOptions = event.interaction.command.options

        val carryType: String? = allOptions.filter { entry ->
            entry.key.equals("carry-type", ignoreCase = true)
        }.values.firstOrNull()?.value as String?

        if (carryType != null) {
            suggest(
                (CarryTypeConnection[event.getGuildId()].authenticated()
                    .getByIdentifier(carryType)
                    ?.let { carryTypeModel ->
                        PurgeTypeConnection[carryTypeModel].authenticated().getAllPurgeTypes()
                    } ?: listOf())
                    .filter { purgeType ->
                        focusedOption.value.isEmpty()
                                || (purgeType.identifier.contains(focusedOption.value, true)
                                || purgeType.displayName.contains(focusedOption.value, true))
                    }
                    .map { purgeType ->
                        Choice.StringChoice(
                            name = purgeType.displayName,
                            value = purgeType.identifier,
                            nameLocalizations = Optional()
                        )
                    }
            )
        }
    }

    val knownStaticResource: AutoCompleteCallback = { _ ->
        suggest(
            KnownStaticResource.entries.filter { staticResource ->
                focusedOption.value.isEmpty()
                        || (staticResource.name.contains(focusedOption.value, true)
                        || staticResource.path.contains(focusedOption.value, true)
                        || staticResource.loadDisplayName().contains(focusedOption.value, true))
            }.map { staticResource ->
                Choice.StringChoice(
                    name = staticResource.loadDisplayName(),
                    value = staticResource.name,
                    nameLocalizations = Optional()
                )
            }.take(25)
        )
    }

    val allConfigs: AutoCompleteCallback = { _ ->
        suggest(
            ServerProperty.entries.filter {
                focusedOption.value.isEmpty()
                        || (it.readableName.translate().contains(focusedOption.value, true)
                        || it.name.contains(focusedOption.value, true))
            }.map { property ->
                Choice.StringChoice(
                    name = property.readableName.translate(),
                    value = property.name,
                    nameLocalizations = Optional()
                )
            }.take(25)
        )
    }

    val skyblockProfile: AutoCompleteCallback = { event ->
        val uuid = if(event.interaction.command.options.any { it.key == "ign" || it.key == "player" }) {
            val ign = event.interaction.command.options.entries.firstOrNull { it.key == "ign" || it.key == "player" }.let { it?.value?.value as? String? }
            ign?.let { MojangConnection.getUUIDByName(it) }
        } else {
            DiscordUserConnection.authenticated().getLinkedById(event.interaction.user.id.value.toLong())?.minecraftId
        }

        if(uuid != null) {
            suggest(
                HypixelApiConnection().getSkyblockProfiles(uuid).valueOrNull?.profiles?.map {
                    it.cuteName to it.profileId
                }?.map { (name, uuid) ->
                    Choice.StringChoice(
                        name = name ?: "Unknown ($uuid)",
                        value = uuid.toString(),
                        nameLocalizations = Optional()
                    )
                } ?: emptyList()
            )
        }
    }

    fun getCarryTierFromTicket(ticket: TicketModel): CarryTierModel? {
        return ticket.ticketPanel.relatedCarryTier
    }
}

private fun AutoCompleteInteractionCreateEvent.getGuildId(): Long {
    return interaction.data.guildId.value?.value!!.toLong()
}
