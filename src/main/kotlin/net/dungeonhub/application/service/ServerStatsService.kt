package net.dungeonhub.application.service

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.asChannelOfOrNull
import dev.kord.core.behavior.channel.edit
import dev.kord.core.behavior.getChannelOfOrNull
import dev.kord.core.entity.Guild
import dev.kord.core.entity.channel.GuildChannel
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.entity.channel.VoiceChannel
import dev.kordex.core.utils.scheduling.Scheduler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dungeonhub.application.connection.DiscordConnection
import net.dungeonhub.application.exceptions.CommandExecutionException
import net.dungeonhub.application.loader.OnStart
import net.dungeonhub.application.loader.StartupListener
import net.dungeonhub.application.misc.DhScheduler
import net.dungeonhub.connection.DiscordServerConnection
import net.dungeonhub.connection.StatsConnection
import net.dungeonhub.hypixel.service.FormattingService
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OnStart
object ServerStatsService : StartupListener {
    private val logger = LoggerFactory.getLogger(ServerStatsService::class.java)
    private lateinit var scheduler: Scheduler
    private val serverStatChannels = listOf(
        //DH Testing
        1023684107877761196L to listOf(
            1273562134948876380L to "{member_count} members",
            1272308210102964254L to "{linked_users} linked Users",
            1272323673457557616L to "{spent_money} coins spent",
            1280463884003704874L to "{carry_count_30d} carries last 30 days"
        ),
        //Dungeon Hub
        693263712626278553L to listOf(
            1273562347797090377L to "{member_count} members",
            1272331486154194984L to "{linked_users} linked Users",
            1272331662772142081L to "{spent_money} coins spent",
            1280470213745184810L to "{carry_count_30d} carries last 30 days"
        )
    )

    override suspend fun postStart() {
        if (::scheduler.isInitialized) {
            scheduler.cancel("Application was restarted.")
        }

        scheduler = DhScheduler()

        val task = scheduler.schedule(2.hours, startNow = false, name = "Server-Stats-Schedule", repeat = true) {
            logger.debug("Server stat channels reloading...")
            loadServerStatChannels()
            logger.debug("Server stat channels reloaded!")
        }

        scheduler.launch {
            delay(60.seconds)
            task.callNow()
            task.start()
        }
    }

    private suspend fun loadServerStatChannels() {
        serverStatChannels.forEach { (guild, channels) ->
            DiscordConnection.bot.kordRef.getGuildOrNull(Snowflake(guild))
                ?.let {
                    updateStatChannels(it, channels)
                }
        }
    }

    private suspend fun updateStatChannels(guild: Guild, channels: List<Pair<Long, String>>) {
        val linkedUsers = StatsConnection.authenticated().getGlobalStats()?.linkedUsers ?: 0
        val spentMoney = try {
            FormattingService.makeNumberReadable(
                DiscordServerConnection.authenticated().getTotalAmountOfMoneySpent(guild.id.value.toLong())
                    ?: throw CommandExecutionException("Couldn't load the total amount of money spent."),
                3
            )
        } catch (_: Exception) {
            0
        }
        val monthlyCarries = DiscordServerConnection.authenticated().getCarryAmount(
            guild.id.value.toLong(),
            ZonedDateTime.now().minusDays(30).toInstant()
        ) ?: 0

        channels.forEach { (channel, stats) ->
            guild.getChannelOfOrNull<GuildChannel>(Snowflake(channel))
                ?.let { guildChannel ->
                    if (stats.contains("{member_count}") && guild.approximateMemberCount == null) {
                        return@let
                    }

                    val newName = stats
                        .replace("{linked_users}", linkedUsers.toString())
                        .replace("{spent_money}", spentMoney.toString())
                        .replace("{member_count}", guild.approximateMemberCount.toString())
                        .replace("{carry_count_30d}", monthlyCarries.toString())

                    guildChannel.asChannelOfOrNull<TextChannel>()?.let { textChannel ->
                        textChannel.edit {
                            name = newName
                        }
                    }

                    guildChannel.asChannelOfOrNull<VoiceChannel>()?.let { voiceChannel ->
                        voiceChannel.edit {
                            name = newName
                        }
                    }
                }
        }
    }
}