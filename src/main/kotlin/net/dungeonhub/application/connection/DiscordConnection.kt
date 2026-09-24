package net.dungeonhub.application.connection

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.kord.common.annotation.KordUnsafe
import dev.kord.common.entity.PresenceStatus
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.asChannelOfOrNull
import dev.kord.core.entity.Embed
import dev.kord.core.entity.Embed.*
import dev.kord.core.entity.Member
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.embed
import dev.kord.rest.ratelimit.ParallelRequestRateLimiter
import dev.kord.rest.request.KtorRequestHandler
import dev.kordex.core.ExtensibleBot
import dev.kordex.core.components.components
import dev.kordex.core.components.linkButton
import dev.kordex.core.i18n.SupportedLocales
import dev.kordex.core.i18n.toKey
import dev.kordex.core.utils.dm
import dev.kordex.data.api.DataCollection
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.dungeonhub.application.config.ConfigProperty
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.listener.ServerJoinListener
import net.dungeonhub.application.loader.ClassLoader
import net.dungeonhub.application.loader.OnStart
import net.dungeonhub.application.loader.StartPriority
import net.dungeonhub.application.loader.StartupListener
import net.dungeonhub.application.misc.EmbedModel
import net.dungeonhub.application.service.ApplicationService
import net.dungeonhub.application.service.color
import net.dungeonhub.service.MoshiService
import net.dungeonhub.wrapper.kord.toJavaColor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Color
import java.time.Instant
import java.util.*
import java.util.regex.Pattern
import kotlin.jvm.java
import kotlin.time.*

/**
 * This is the main-class for the application.
 * It automatically loads all listeners and commands and initiates the bot-instance.
 * Even if it doesn't make much of a difference, it is advised to not use {@link #getBot()} too much.
 *
 * @author Taubsie
 * @since 1.0.0
 */
@OnStart(priority = StartPriority.DISCORD_BOT)
object DiscordConnection : StartupListener {
    private val logger: Logger = LoggerFactory.getLogger(DiscordConnection::class.java)
    private var started: Boolean = false
    var uptime: Instant = Instant.now()

    lateinit var bot: ExtensibleBot
    val botIsLoaded: Boolean
        get() = ::bot.isInitialized

    /**
     * Returns a line for command-line output.
     *
     * @return a line for command-line output.
     */
    private const val LINE = "----------------------------------------"

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        ClassLoader.loadStartupListeners()
        ClassLoader.executePreStart()
    }

    /**
     * Method by the {@link StartupListener} interface, this is automatically executed on program launch.
     * This implementation starts the discord-bot.
     */
    @OptIn(KordUnsafe::class, ExperimentalTime::class)
    override suspend fun preStart() {
        bot = ExtensibleBot(ConfigProperty.DISCORD_BOT_TOKEN.value!!) {
            dataCollectionMode = DataCollection.Extra

            kord {
                httpClient = HttpClient(Java) {
                    engine {
                        protocolVersion = java.net.http.HttpClient.Version.HTTP_2
                    }

                    install(WebSockets)
                }
                stackTraceRecovery = true

                requestHandler {
                    KtorRequestHandler(it.httpClient, ParallelRequestRateLimiter(), token = token)
                }
            }

            about {
                ephemeral = true

                general {
                    message {
                        embed {
                            color(EmbedColor.Default)
                            description = "## Thanks for using our bot!\n" +
                                    "While this bot was initially created only to be used on the Dungeon Hub Discord " +
                                    "server, we have since decided to allow it to be used on other servers as well!\n" +
                                    "If you're confused about how to configure the bot, you can check out the " +
                                    "[documentation](https://docs.dungeon-hub.net/) or ask for help " +
                                    "[in our discord](https://discord.dungeon-hub.net/)."
                        }

                        components {
                            linkButton {
                                label = "Documentation".toKey()
                                url = "https://docs.dungeon-hub.net/"
                            }

                            linkButton {
                                label = "Discord".toKey()
                                url = "https://discord.dungeon-hub.net/"
                            }
                        }
                    }
                }

                section("apis", "APIs we use") {
                    message {
                        embed {
                            color(EmbedColor.Default)
                            description = "### We use the following services and external APIs:\n" +
                                    "- [Mojang API](https://minecraft.wiki/w/Mojang_API)\n" +
                                    "- [Hypixel API](https://api.hypixel.net/)\n" +
                                    "- [Hypixel Safety API (by SBM)](https://hs.sbm.gg/)\n" +
                                    "- [Scammer List (BlockHelper)](https://list.lenny.ie/)\n" +
                                    "-# We are not affiliated with any of the above services/companies"
                        }
                    }
                }
            }

            errorResponse { message, type ->
                embeds = ApplicationService.getErrorEmbeds(type.error, message.translate())
            }

            hooks {
                beforeStart {
                    ClassLoader.executeStartup()
                }
            }

            @OptIn(PrivilegedIntent::class)
            intents(addDefaultIntents = false, addExtensionIntents = true) { // TODO add these to the extensions themselves
                +Intent.Guilds
                +Intent.GuildMembers
                +Intent.GuildMessages
                +Intent.MessageContent
            }

            presence {
                state = "Loading..."
                status = PresenceStatus.Idle
            }

            i18n {
                defaultLocale = SupportedLocales.ENGLISH

                applicationCommandLocale(setOf(Locale.GERMAN))

                interactionUserLocaleResolver()
                interactionGuildLocaleResolver()
            }
        }

        ClassLoader.loadExtensions(bot)

        bot.on<ReadyEvent> {
            if (!started) {
                ClassLoader.executePostStart()

                started = true
                uptime = Instant.now()
            }
        }

        bot.start()
    }

    /**
     * Returns the formatted message to list all servers the bot is on.
     *
     * @return the formatted message to list all servers the bot is on.
     */
    private suspend fun getServerListMessage(): List<String> {
        val message: MutableList<String> = mutableListOf()

        message.add("Im on servers:")
        message.addAll(
            bot.kordRef.guilds.map { server ->
                "${server.name} with id '${server.id}' by ${
                    server.withStrategy(EntitySupplyStrategy.cache).getOwnerOrNull()?.effectiveName ?: "no-name"
                } (${server.ownerId})"
            }.toList()
        )

        return message
    }

    override suspend fun onStart() {
        ServerJoinListener.GUILD_ON_JOIN.addAll(
            bot.kordRef.guilds.map { guild ->
                guild.id.value
            }.toList()
        )
    }

    override suspend fun postStart() {
        logger.info(LINE)
        getServerListMessage().forEach(logger::info)
        logger.info(LINE)

        ApplicationService.getBotOwner(bot.kordRef)?.dm {
            val embed = ApplicationService.embed
            embed.color(EmbedColor.Positive)
            embed.description = "Bot started"

            embeds = mutableListOf(embed)
        }
    }
}

// TODO slowly move these method usages to alternatives such as mass-syncing
fun User.getMutualServers(): Flow<Member> {
    return kord.guilds.mapNotNull { server ->
        this.asMemberOrNull(server.id)
    }
}

@OptIn(ExperimentalTime::class)
fun EmbedBuilder.copy(other: EmbedBuilder) {
    this.description = other.description
    this.title = other.title
    this.footer = other.footer
    this.fields = other.fields
    this.color = other.color
    this.timestamp = other.timestamp
    this.url = other.url
    this.image = other.image
    this.author = other.author
    this.thumbnail = other.thumbnail
}

private val messageLinkPattern = Pattern.compile(
    "(?x)                               # enable comment mode \n" +
            "(?i)                             # ignore case \n" +
            "(?:https?+://)?+                 # 'https://' or 'http://' or '' \n" +
            "(?:(?:canary|ptb)\\.)?+          # 'canary.' or 'ptb.'\n" +
            "discord(?:app)?+\\.com/channels/ # 'discord(app).com/channels/' \n" +
            "(?:(?<server>[0-9]++)|@me)       # '@me' or the server id as named group \n" +
            "/                                # '/' \n" +
            "(?<channel>[0-9]++)              # the textchannel id as named group \n" +
            "/                                # '/' \n" +
            "(?<message>[0-9]++)              # the message id as named group \n"
)

suspend fun Kord.loadMessageByLink(messageLink: String): Message? {
    val matcher = messageLinkPattern.matcher(messageLink)

    require(matcher.matches()) { "The message link has an invalid format" }

    return getChannel(Snowflake(matcher.group("channel")))
        ?.asChannelOfOrNull<MessageChannel>()
        ?.getMessage(Snowflake(matcher.group("message")))
}

fun EmbedBuilder.setFields(value: JsonElement) {
    value.asJsonArray.asList().stream()
        .map { obj: JsonElement -> obj.asJsonObject }
        .forEach { jsonObject: JsonObject ->
            field(
                jsonObject.getAsJsonPrimitive("name").asString,
                jsonObject.getAsJsonPrimitive("inline")?.asBoolean ?: false
            ) {
                jsonObject.getAsJsonPrimitive("value").asString
            }
        }
}

fun EmbedBuilder.setFooter(value: JsonElement) {
    val footer = value.asJsonObject

    footer {
        text = footer.getAsJsonPrimitive("text").asString
        icon = footer.getAsJsonPrimitive("icon")?.asString
    }
}

fun EmbedBuilder.setThumbnail(value: JsonElement) {
    thumbnail {
        url = value.asJsonObject.getAsJsonPrimitive("url").asString
    }
}

fun EmbedBuilder.setAuthor(value: JsonElement) {
    if (value.isJsonPrimitive) {
        author {
            name = value.asString
        }
    } else {
        val jsonObject = value.asJsonObject

        author {
            name = jsonObject.getAsJsonPrimitive("name").asString
            url = jsonObject.getAsJsonPrimitive("url")?.asString
            icon = jsonObject.getAsJsonPrimitive("icon")?.asString
        }
    }
}

@OptIn(ExperimentalTime::class)
fun EmbedBuilder.applyJson(key: String, value: JsonElement) {
    when (key) {
        "title" -> title = value.asString
        "description" -> description = value.asString
        "author" -> setAuthor(value)
        "url" -> url = value.asString
        "color" -> color = run {
            val internalColor = MoshiService.moshi.adapter(Color::class.java).fromJsonValue(
                value.asString
            )!!
            dev.kord.common.Color(internalColor.red, internalColor.green, internalColor.blue)
        }

        "fields" -> setFields(value)
        "footer" -> setFooter(value)
        "timestamp" -> timestamp =
            MoshiService.moshi.adapter(Instant::class.java).fromJson(value.asString)!!.toKotlinInstant()

        "thumbnail" -> setThumbnail(value)
        "image" -> image = if(value.isJsonNull) null else value.asString
    }
}

@OptIn(ExperimentalTime::class)
fun Embed.toBuilder(): EmbedBuilder {
    val embed = EmbedBuilder()

    embed.title = title
    embed.description = description
    embed.url = url
    embed.timestamp = timestamp
    embed.color = color
    embed.image = image?.url
    embed.footer = footer?.toBuilder()
    embed.thumbnail = thumbnail?.toBuilder()
    embed.author = author?.toBuilder()
    embed.fields = fields.map { it.toBuilder() }.toMutableList()

    return embed
}

fun Author.toBuilder(): EmbedBuilder.Author {
    val author = EmbedBuilder.Author()

    author.name = name
    author.url = url
    author.icon = iconUrl

    return author
}

fun Field.toBuilder(): EmbedBuilder.Field {
    val field = EmbedBuilder.Field()

    field.name = name
    field.inline = inline
    field.value = value

    return field
}

@OptIn(ExperimentalTime::class)
fun Embed.toModel(): EmbedModel {
    val embed = EmbedModel(
        title,
        description,
        url,
        timestamp?.toJavaInstant(),
        color?.toJavaColor(),
        image?.url,
        footer?.toBuilder(),
        thumbnail?.toBuilder(),
        author?.toModel()
    )

    embed.fields = fields.map { it.toModel() }.toMutableList()

    return embed
}

fun Footer.toBuilder(): EmbedBuilder.Footer {
    val footer = EmbedBuilder.Footer()

    footer.text = text
    footer.icon = iconUrl

    return footer
}

fun Thumbnail.toBuilder(): EmbedBuilder.Thumbnail? {
    val url = url ?: return null

    val thumbnail = EmbedBuilder.Thumbnail()

    thumbnail.url = url

    return thumbnail
}

fun Author.toModel(): EmbedModel.Author {
    return EmbedModel.Author(name, url, iconUrl)
}

fun Field.toModel(): EmbedModel.Field {
    return EmbedModel.Field(name, inline, value)
}

fun Duration.withNanos(nanos: Int): Duration {
    return toJavaDuration().withNanos(nanos).toKotlinDuration()
}