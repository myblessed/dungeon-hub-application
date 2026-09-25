package net.dungeonhub.application.service

import dev.kord.common.entity.PresenceStatus
import dev.kord.gateway.builder.PresenceBuilder
import dev.kordex.core.utils.scheduling.Scheduler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.launch
import net.dungeonhub.application.connection.DiscordConnection.bot
import net.dungeonhub.application.connection.DiscordConnection.uptime
import net.dungeonhub.application.exceptions.CommandExecutionException
import net.dungeonhub.application.loader.OnStart
import net.dungeonhub.application.loader.StartupListener
import net.dungeonhub.application.misc.DhScheduler
import net.dungeonhub.connection.DiscordServerConnection
import net.dungeonhub.connection.StatsConnection
import net.dungeonhub.hypixel.service.FormattingService
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

@OnStart
object AppearanceService : StartupListener {
    private const val REFRESH_SECONDS = 60L * 30
    private val logger = LoggerFactory.getLogger(AppearanceService::class.java)
    private lateinit var scheduler: Scheduler
    private var currentAppearance = 0
    private val possibleAppearances: List<Pair<AppearanceType, suspend () -> String>> = listOf(
        AppearanceType.Custom to {
            "Handling ${StatsConnection.authenticated().getGlobalStats()?.linkedUsers ?: 0} linked users!"
        },
        AppearanceType.Watching to {
            "carriers on ${bot.kordRef.guilds.count()} servers"
        },
        AppearanceType.Competing to {
            "score leaderboards for first place"
        },
        AppearanceType.Custom to {
            "Customize me at dungeon-hub.net"
        },
        AppearanceType.Custom to {
            "Running 100% in Kotlin!"
        },
        AppearanceType.Watching to {
            "you clear dungeons"
        },
        AppearanceType.Custom to {
            "Helping you level up!"
        },
        AppearanceType.Playing to {
            "some Master Mode."
        },
        AppearanceType.Custom to {
            "Check out /help for more!"
        },
        AppearanceType.Custom to {
            "Remember to close and /log"
        },
        AppearanceType.Listening to {
            val uptime = Duration.between(uptime, Instant.now()).withNanos(0).toKotlinDuration().toString()

            "discord events since $uptime"
        },
        AppearanceType.Custom to {
            val amount = try {
                DiscordServerConnection.authenticated().getTotalAmountOfMoneySpent(693263712626278553L)
                    ?: throw CommandExecutionException("Couldn't load the total amount of money spent.")
            } catch (_: CommandExecutionException) {
                0
            }

            "${FormattingService.makeNumberReadable(amount, 3)} coins spent on Dungeon Hub!"
        }
    )

    override suspend fun postStart() {
        if(::scheduler.isInitialized) {
            scheduler.cancel("Application was restarted.")
        }

        scheduler = DhScheduler()

        val task = scheduler.schedule(REFRESH_SECONDS, startNow = false, name = "Appearance-Schedule", repeat = true) {
            resetBotAppearance()
        }

        scheduler.launch {
            delay(10.seconds)
            task.callNow()
            task.start()
        }
    }

    /**
     * This resets the bot's appearance.
     */
    private suspend fun resetBotAppearance() {
        bot.kordRef.editPresence {
            status = PresenceStatus.Online

            currentAppearance = if (currentAppearance >= possibleAppearances.size - 1) 0 else currentAppearance + 1

            val (appearanceType, appearanceFunction) = possibleAppearances[currentAppearance]

            try {
                appearanceType.apply(appearanceFunction())()
            } catch (exception: Exception) {
                logger.error("Error during reset of appearance.", exception)
            }
        }
    }

    enum class AppearanceType(val apply: (text: String) -> (PresenceBuilder.() -> Unit)) {
        /**
         * Playing {text}
         */
        Playing({ s -> { playing(s) } }),

        /**
         * Listening to {text}
         */
        Listening({ s -> { listening(s) } }),

        /**
         * Watching {text}
         */
        Watching({ s -> { watching(s) } }),

        /**
         * Competing in {text}
         */
        Competing({ s -> { competing(s) } }),

        /**
         * {text}
         */
        Custom({ s -> { state = s } });
    }
}