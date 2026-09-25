package net.dungeonhub.application.service

import com.google.zxing.*
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.*
import dev.kord.core.entity.Member
import dev.kord.core.entity.User
import dev.kord.core.entity.effectiveName
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.EmbedBuilder.Footer
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.create.AbstractMessageCreateBuilder
import dev.kord.rest.builder.message.modify.InteractionResponseModifyBuilder
import dev.kordex.core.extensions.Extension
import dev.kordex.core.utils.dm
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import net.dungeonhub.application.commands.LinkingSystem
import net.dungeonhub.application.connection.DiscordConnection
import net.dungeonhub.application.connection.FlaggingConnection
import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.application.enums.ServerProperty
import net.dungeonhub.application.exceptions.CommandExecutionException
import net.dungeonhub.application.exceptions.CommandExecutionWarning
import net.dungeonhub.application.exceptions.FailedToLoadEmbedException
import net.dungeonhub.application.misc.FlagResponse
import net.dungeonhub.connection.DiscordUserConnection
import net.dungeonhub.connection.ReputationConnection
import net.dungeonhub.connection.ReputationConnection.Companion.ClientlessReputationConnection
import net.dungeonhub.enums.CntRequestType
import net.dungeonhub.enums.ScoreType
import net.dungeonhub.enums.WarningAction
import net.dungeonhub.exception.PlayerNotFoundException
import net.dungeonhub.hypixel.client.responses.Stale
import net.dungeonhub.hypixel.client.responses.ValueResponse
import net.dungeonhub.hypixel.connection.HypixelApiConnection
import net.dungeonhub.hypixel.entities.skyblock.statsoverview.StatsOverviewType
import net.dungeonhub.hypixel.service.FormattingService
import net.dungeonhub.model.carry.CarryModel
import net.dungeonhub.model.carry_difficulty.CarryDifficultyModel
import net.dungeonhub.model.carry_queue.CarryQueueModel
import net.dungeonhub.model.carry_tier.CarryTierModel
import net.dungeonhub.model.carry_type.CarryTypeModel
import net.dungeonhub.model.cnt_request.CntRequestModel
import net.dungeonhub.model.discord_role.DiscordRoleModel
import net.dungeonhub.model.role_requirement.RoleRequirementModel
import net.dungeonhub.model.score.ScoreModel
import net.dungeonhub.model.static_message.StaticMessageModel
import net.dungeonhub.model.warning.DetailedWarningModel
import net.dungeonhub.model.warning.WarningActionModel
import net.dungeonhub.model.warning.WarningModel
import net.dungeonhub.mojang.connection.MojangConnection
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.*
import javax.imageio.ImageIO
import kotlin.time.*

@OptIn(ExperimentalTime::class)
object ApplicationService {
    const val MAX_MINECRAFT_USERNAME_LENGTH = 16
    const val skyCryptUrl = "https://sky.shiiyu.moe/"

    private val logger: Logger = LoggerFactory.getLogger(DiscordConnection::class.java)

    val dungeonHubDirectory = "${System.getProperty("user.home")}${File.separator}dungeon-hub"

    private val serverLink: String
        get() = "discord.dungeon-hub.net"

    val footer: Footer
        /**
         * Returns the default footer used for most embeds.
         *
         * @return the default footer used for most embeds.
         */
        get() {
            val footer = Footer()
            footer.text = "$serverLink • made by @taubsie"
            return footer
        }

    val unstableFooter: String
        /**
         * Returns the footer used for embeds of unstable or new features.
         *
         * @return the footer used for embeds of unstable or new features.
         */
        get() = "$serverLink • THIS FEATURE IS UNSTABLE • please report bugs to @taubsie"

    val priceFooter: String
        /**
         * Returns the footer used for price message embeds.
         *
         * @return the footer used for price message embeds.
         */
        get() = "$serverLink • also see /calc-price • made by @taubsie"

    val embed: EmbedBuilder
        get() = getEmbed(Clock.System.now())

    val embedWithoutTimestamp: EmbedBuilder
        get() {
            val embed = EmbedBuilder()
            embed.footer = footer
            return embed
        }

    fun getEmbed(time: Instant?): EmbedBuilder {
        val embed = embedWithoutTimestamp
        embed.timestamp = time
        return embed
    }

    /**
     * This returns the owner of the bot as a discord user.
     * If the bot is part of an application team, the owner of the team is returned, otherwise the bot owner is returned.
     */
    suspend fun getBotOwner(kord: Kord): User? {
        val ownerId = kord.getApplicationInfo().team?.ownerUserId ?: kord.getApplicationInfo().ownerId

        if (ownerId == null) {
            return null
        }

        return kord.getUser(ownerId)
    }

    val errorEmbed: EmbedBuilder
        get() = getErrorEmbed(embed = embed)

    fun getErrorEmbed(embed: EmbedBuilder): EmbedBuilder {
        embed.title = "Error"
        embed.color = EmbedColor.Negative.color

        return embed
    }

    fun getErrorEmbed(commandExecutionException: CommandExecutionException): EmbedBuilder {
        val embed = errorEmbed
        embed.description = commandExecutionException.message
        return embed
    }

    fun getErrorEmbed(commandExecutionWarning: CommandExecutionWarning): EmbedBuilder {
        val embed = errorEmbed
        embed.description = commandExecutionWarning.message
        return embed
    }

    fun getErrorEmbed(embed: EmbedBuilder, commandExecutionException: CommandExecutionException): EmbedBuilder {
        val embedBuilder = getErrorEmbed(embed)
        embedBuilder.description = commandExecutionException.message
        return embedBuilder
    }

    fun loadEmbedFromDiscordRole(discordRoleModel: DiscordRoleModel, locale: Locale? = null): EmbedBuilder {
        val embed = embed

        embed.field("Role", true) { "<@&" + discordRoleModel.id + ">" }
        embed.field(
            "Name schema",
            true
        ) { discordRoleModel.nameSchema ?: "none" }
        embed.field("Role action", true) { discordRoleModel.roleAction.readableName.withLocale(locale).translate() }

        return embed
    }

    @JvmOverloads
    fun loadEmbedFromCarry(
        carry: CarryModel,
        embedBuilder: EmbedBuilder = getEmbed(Instant.fromEpochSeconds(carry.time!!.epochSecond))
    ): EmbedBuilder {
        embedBuilder.color = EmbedColor.Information.color

        embedBuilder.field("Number of carries", true) { carry.amount.toString() }
        embedBuilder.field(
            "Type of carry",
            true
        ) { carry.carryDifficulty.carryTier.displayName + " - " + carry.carryDifficulty.displayName }
        embedBuilder.field("Player", true) { "<@" + carry.player.id + ">" }
        embedBuilder.field("Carrier", true) { "<@" + carry.carrier.id + ">" }
        embedBuilder.field("Gained Score", true) { carry.calculateScore().toString() }

        if (carry.approver != null) {
            embedBuilder.field("Approved by", true) { "<@" + carry.approver + ">" }
        }

        if (carry.attachmentLink != null) {
            embedBuilder.field("Transcript-Link", true) { "[Click to open]" + "(" + carry.attachmentLink + ")" }
        }

        return embedBuilder
    }

    fun loadTicketNotificationFromCarryQueue(carryQueue: CarryQueueModel): EmbedBuilder {
        return buildEmbed {
            description = "<@${carryQueue.carrier.id}> logged **${carryQueue.amount} ${carryQueue.carryTier.displayName} - ${carryQueue.carryDifficulty.displayName}** ${if(carryQueue.amount == 1) "carry" else "carries"} for <@${carryQueue.player.id}>, worth ${carryQueue.calculateScore()} score."
            color(EmbedColor.Information)
        }
    }

    fun loadEmbedFromCarryQueue(carryQueue: CarryQueueModel, embedBuilder: EmbedBuilder): EmbedBuilder {
        embedBuilder.color = EmbedColor.Information.color

        embedBuilder.field("Number of carries", true) { carryQueue.amount.toString() }
        embedBuilder.field(
            "Type of carry",
            true
        ) { carryQueue.carryTier.displayName + " - " + carryQueue.carryDifficulty.displayName }
        embedBuilder.field("Player", true) { "<@" + carryQueue.player.id + ">" }
        embedBuilder.field("Carrier", true) { "<@" + carryQueue.carrier.id + ">" }
        embedBuilder.field("Gained Score", true) { carryQueue.calculateScore().toString() }

        if (carryQueue.attachmentLink != null) {
            embedBuilder.field("Transcript-Link", true) { "[Click to open]" + "(" + carryQueue.attachmentLink + ")" }
        }

        return embedBuilder
    }

    fun loadEmbedFromCarryQueue(carryQueue: CarryQueueModel): EmbedBuilder {
        return loadEmbedFromCarryQueue(carryQueue, getEmbed(Instant.fromEpochSeconds(carryQueue.time!!.epochSecond)))
    }

    fun formatWarn(warningModel: WarningModel): EmbedBuilder {
        val embed = getEmbed(warningModel.time.toKotlinInstant())
        embed.color = EmbedColor.Information.color
        embed.title = "Warning #${warningModel.id}"

        embed.field("User") { "<@${warningModel.user.id}>" }
        embed.field("Staff member") { "<@${warningModel.striker.id}>" }
        embed.field("Severity") { warningModel.warningType.name }
        embed.field("Reason") { warningModel.reason ?: "No reason provided." }
        embed.field("Active") { warningModel.active.toString() }

        return embed
    }

    fun formatWarn(warningModel: DetailedWarningModel, showEvidences: Boolean = true): EmbedBuilder {
        val embed = getEmbed(warningModel.time.toKotlinInstant())
        embed.color = EmbedColor.Information.color
        embed.title = "Warning #${warningModel.id}"

        embed.field("User") { "<@${warningModel.user.id}>" }
        embed.field("Staff member") { "<@${warningModel.striker.id}>" }
        embed.field("Severity") { warningModel.warningType.name }
        embed.field("Reason") { warningModel.reason ?: "No reason provided." }
        embed.field("Active") { warningModel.active.toString() }

        if (showEvidences && warningModel.evidences.isNotEmpty()) {
            val evidences = warningModel.evidences.stream().map {
                "- ${it.evidence}"
            }.toList().joinToString(separator = System.lineSeparator())

            embed.field("Evidences") { evidences }
        }

        return embed
    }

    suspend fun formatWarnDm(warningModel: WarningModel): EmbedBuilder {
        val embedBuilder = getEmbed(warningModel.time.toKotlinInstant())
        embedBuilder.color = EmbedColor.Information.color
        embedBuilder.title = "You were warned on server `${
            DiscordConnection.bot.kordRef.getGuildOrNull(Snowflake(warningModel.server.id))?.name ?: "unknown"
        }`"

        embedBuilder.field("You") { "<@${warningModel.user.id}>" }
        embedBuilder.field("Id") { "#${warningModel.id}" }
        embedBuilder.field("Severity") { warningModel.warningType.name }
        embedBuilder.field("Reason") {
            warningModel.reason ?: "No reason provided."
        }

        return embedBuilder
    }

    fun formatWarnLog(warningModel: WarningModel): EmbedBuilder {
        val embed = getEmbed(warningModel.time.toKotlinInstant())
        embed.color = EmbedColor.Information.color
        embed.title = "Warning #${warningModel.id}"

        embed.field("User") { "<@${warningModel.user.id}>" }
        embed.field("Staff member") { "<@${warningModel.striker.id}>" }
        embed.field("Severity") { warningModel.warningType.name }
        embed.field("Reason") { warningModel.reason ?: "No reason provided." }

        return embed
    }

    val noCarryTypeFoundEmbed: EmbedBuilder
        get() {
            val embed = embed
            embed.title = "No score was found!"
            embed.description =
                "Please make sure that a carry type is setup on this server.\n" +
                        "For more information about how to do this, contact `@taubsie` (<@356134481452597250>)!"
            embed.color = EmbedColor.Negative.color

            return embed
        }

    suspend fun getScoreCountMessage(
        userToCheck: User,
        user: User,
        server: GuildBehavior?,
        scoreCount: List<ScoreModel>,
        carryCount: Int? = null,
        locale: Locale? = null
    ): EmbedBuilder {
        if (scoreCount.isEmpty()) {
            return noCarryTypeFoundEmbed
        }

        val embed = embed

        embed.title = if ((userToCheck.id != user.id && server != null)) {
            (server.getMemberOrNull(userToCheck.id)?.effectiveName ?: userToCheck.effectiveName) + "'s score${if (carryCount != null) " from $carryCount total carries" else ""}:"
        } else {
            "Your score${if (carryCount != null) " from $carryCount total carries" else ""}:"
        }
        embed.color = EmbedColor.Default.color

        val scoreDescriptions: EnumMap<ScoreType, MutableList<String>> = EnumMap<ScoreType, MutableList<String>>(
            ScoreType::class.java
        )

        scoreCount.forEach { scoreModel: ScoreModel ->
            if (scoreModel.scoreType == ScoreType.Event && !scoreModel.carryType?.isEventActive!!) {
                return@forEach
            }
            val description = scoreModel.carryType?.displayName + ": " + scoreModel.scoreAmount
            if (scoreDescriptions.containsKey(scoreModel.scoreType)) {
                scoreDescriptions[scoreModel.scoreType]!!.add(description)
            } else {
                scoreDescriptions[scoreModel.scoreType] = mutableListOf(description)
            }
        }

        scoreDescriptions.forEach { (scoreType: ScoreType, strings: List<String>?) ->
            embed.field(
                scoreType.readableName.withLocale(locale).translate(),
                true
            ) { java.lang.String.join(System.lineSeparator(), strings) }
        }

        return embed
    }

    //TODO maybe make it possible to update the embed in 2 intervals, since the mojang+safety+jerry api takes long,
    // as well as the skycrypt api takes long too
    //probably first load skycrypt, then the rest?
    @Throws(FailedToLoadEmbedException::class)
    suspend fun getPlayerDataEmbed(ign: String, discordId: Long?, cacheExpiration: Int = 60, profileOverride: UUID? = null, statsOverviewTypes: List<StatsOverviewType>? = null): EmbedBuilder {
        val embed = embed

        val uuid: UUID = MojangConnection.getUUIDByName(ign)

        val flagResponses: List<FlagResponse> =
            FlaggingConnection.isFlagged(uuid, discordId)
                .stream()
                .filter { flagResponse: FlagResponse -> flagResponse.uuid != null || flagResponse.discord != null }
                .filter { flagResponse: FlagResponse ->
                    ((flagResponse.uuid != null && flagResponse.uuid.flagged)
                            || (flagResponse.discord != null && flagResponse.discord.flagged))
                }
                .toList()

        if (flagResponses.isNotEmpty()) {
            embed.field("Flagged", false) {
                "**This user is flagged, which means it might not safe to interact with them.**\n${
                    formatFlagDetails(flagResponses)
                }"
            }

            embed.color = EmbedColor.Negative.color
        } else {
            embed.color = EmbedColor.Positive.color
        }

        embed.thumbnail {
            url = "https://visage.surgeplay.com/face/$uuid"
        }
        embed.url = skyCryptUrl + "stats/" + ign
        embed.title = try {
            MojangConnection.getNameByUUID(uuid)
        } catch (_: PlayerNotFoundException) {
            ign
        }

        val discordUser = DiscordUserConnection.authenticated().findUserByUuid(uuid)

        val statsOverviewResponse = HypixelApiConnection().withCacheExpiration(cacheExpiration).getStatsOverview(uuid, profileOverride ?: discordUser?.primarySkyblockProfile, statsOverviewTypes)

        val statsOverview = statsOverviewResponse.valueOrNull

        if (statsOverviewResponse !is ValueResponse || statsOverview == null) {
            embed.description = "No profiles found."
            throw FailedToLoadEmbedException(embed)
        }

        embed.description = statsOverview.description
        embed.title = "${
            try {
                MojangConnection.getNameByUUID(uuid)
            } catch (_: PlayerNotFoundException) {
                ign
            }
        } (${statsOverview.profileName})"
        embed.timestamp = statsOverviewResponse.timestamp
        if(statsOverviewResponse is Stale) {
            embed.footer {
                text = "Error connecting to Hypixel API, serving stale cache data"
            }
        }

        return embed
    }

    fun formatTotalFlagDetails(flagged: List<FlagResponse>): MutableList<EmbedBuilder.Field> {
        val result = mutableListOf<EmbedBuilder.Field>()

        for (flagResponse in flagged) {
            if (flagResponse.discordGiven) {
                val discordField = EmbedBuilder.Field()
                discordField.name =
                    (if (flagResponse.discord?.flagged == true) ":x: " else if (flagResponse.discord == null) ":question_mark: " else ":white_check_mark: ") + flagResponse.name + " (by discord)"
                discordField.value =
                    if (flagResponse.discord?.flagged == true) "This user is flagged!\n${
                        flagResponse.discord.format(
                            false
                        )
                    }" else if (flagResponse.discord == null) "Service is currently unreachable." else "User isn't flagged"
                discordField.inline = true
                result.add(discordField)
            }

            if (flagResponse.uuidGiven) {
                val uuidField = EmbedBuilder.Field()
                uuidField.name =
                    (if (flagResponse.uuid?.flagged == true) ":x: " else if (flagResponse.uuid == null) ":question_mark: " else ":white_check_mark: ") + flagResponse.name + " (by UUID)"
                uuidField.value =
                    if (flagResponse.uuid?.flagged == true) "This user is flagged!\n${flagResponse.uuid.format(false)}" else if (flagResponse.uuid == null) "Service is currently unreachable." else "User isn't flagged"
                uuidField.inline = true
                result.add(uuidField)
            }
        }

        return result
    }

    fun formatFlagDetails(flagged: List<FlagResponse>): String {
        val result: MutableList<String> = ArrayList()

        for (flagResponse in flagged) {
            if (flagResponse.discord != null && flagResponse.discord.flagged) {
                result.add(flagResponse.name + " (by discord):\n" + flagResponse.discord.format(true))
            }

            if (flagResponse.uuid != null && flagResponse.uuid.flagged) {
                result.add(flagResponse.name + " (by UUID):\n" + flagResponse.uuid.format(true))
            }
        }

        return java.lang.String.join("\n", result)
    }

    fun getCarryTypeEmbed(carryType: CarryTypeModel): EmbedBuilder {
        val embed = embed
        embed.color = EmbedColor.Default.color
        embed.field("ID", true) { carryType.id.toString() }
        embed.field("Identifier", true) { carryType.identifier }
        embed.field("Display Name", true) { carryType.displayName }

        carryType.logChannel?.let { logChannel ->
            embed.field("Log Channel", true) { "<#$logChannel>" }
        }

        embed.field("Event active", true) { if (carryType.isEventActive == true) "yes" else "no" }

        return embed
    }

    fun getCarryTierEmbed(carryTier: CarryTierModel): EmbedBuilder {
        val embed = embed

        embed.color = EmbedColor.Default.color
        embed.field("ID", true) { carryTier.id.toString() }
        embed.field("Identifier", true) { carryTier.identifier }
        embed.field("Display Name", true) { carryTier.displayName }
        embed.field("Descriptive Name", true) { carryTier.descriptiveName!! }
        embed.field(
            "Carry Type",
            true
        ) { carryTier.carryType.displayName + " (" + carryTier.carryType.identifier + ")" }

        carryTier.category?.let { category: Long -> embed.field("Category", true) { "<#$category>" } }
        carryTier.thumbnailUrl?.let { thumbnailUrl: String ->
            embed.field("Thumbnail URL", true) { thumbnailUrl }
        }
        carryTier.priceTitle?.let { s: String -> embed.field("Price Title", true) { s } }
        carryTier.priceDescription?.let { s: String -> embed.field("Price Description", true) { s } }

        return embed
    }

    fun getCarryDifficultyEmbed(carryDifficulty: CarryDifficultyModel): EmbedBuilder {
        val embed = embed
        embed.color = EmbedColor.Default.color

        embed.field("ID", true) { carryDifficulty.id.toString() }
        embed.field("Identifier", true) { carryDifficulty.identifier }
        embed.field("Display Name", true) { carryDifficulty.displayName }
        embed.field(
            "Carry Type",
            true
        ) { carryDifficulty.carryType.displayName + " (" + carryDifficulty.carryType.identifier + ")" }
        embed.field(
            "Carry Tier",
            true
        ) { carryDifficulty.carryTier.displayName + " (" + carryDifficulty.carryTier.identifier + ")" }
        embed.field(
            "Price",
            true
        ) { carryDifficulty.price.toString() + " (" + FormattingService.makeNumberReadable(carryDifficulty.price.toLong()) + ")" }
        embed.field("Score", true) { carryDifficulty.score.toString() }

        carryDifficulty.bulkAmount
            ?.let { integer: Int -> embed.field("Bulk Amount", true) { integer.toString() } }
        carryDifficulty.bulkPrice
            ?.let { integer: Int -> embed.field("Bulk Price", true) { integer.toString() } }
        carryDifficulty.thumbnailUrl?.let { s: String -> embed.field("Thumbnail URL", true) { s } }
        carryDifficulty.thumbnailUrl?.let { s: String -> embed.field("Price Title", true) { s } }

        return embed
    }

    @Throws(WriterException::class)
    fun generateQRCodeImage(barcodeText: String?): BufferedImage {
        val barcodeWriter = QRCodeWriter()

        val hints: MutableMap<EncodeHintType, Any> = EnumMap(
            EncodeHintType::class.java
        )
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = 1

        val bitMatrix: BitMatrix = barcodeWriter.encode(barcodeText, BarcodeFormat.QR_CODE, 200, 200, hints)

        return MatrixToImageWriter.toBufferedImage(bitMatrix)
    }

    @Throws(ChecksumException::class, NotFoundException::class, FormatException::class)
    fun readQRCodeImage(bufferedImage: BufferedImage?): String {
        val binaryBitmap = BinaryBitmap(
            HybridBinarizer(
                BufferedImageLuminanceSource(bufferedImage)
            )
        )

        val qrCodeReader = QRCodeReader()

        return qrCodeReader.decode(binaryBitmap).text
    }

    fun readImageData(bufferedImage: BufferedImage?): ByteArray {
        val outputStream = ByteArrayOutputStream()
        try {
            ImageIO.write(bufferedImage, "png", outputStream)
        } catch (ioException: IOException) {
            logger.error(null, ioException)
            return ByteArray(0)
        }
        return outputStream.toByteArray()
    }

    //TODO maybe make sure that multiple actions on roles don't override each other?
    suspend fun applyWarningActions(actions: List<WarningActionModel>, member: Member): String? {
        if (actions.isEmpty()) {
            return null
        }

        if (actions.any { it.warningAction == WarningAction.Ban }) {
            try {
                member.dm {
                    val unbanForm = ServerProperty.UNBAN_FORM.getValue(member.guildId.value.toLong())

                    var message = (ServerProperty.BAN_MESSAGE
                        .getValue(member.guildId.value.toLong())
                        ?: "You got banned from `%server%` because of too many severe warnings.\nIf you think this is a mistake, contact the administrators for further information.")
                        .replace("%server%", member.guild.asGuildOrNull()?.name ?: member.guildId.toString())

                    unbanForm?.let {
                        message = message.replace("%form%", it)

                        actionRow {
                            linkButton(it) {
                                label = "Appeal"
                            }
                        }
                    }

                    content = message
                }
            } catch (_: Exception) {
                //ignore, dm is just optional
            }

            member.ban {
                reason = "Too many severe warnings."
            }
            return "- Permanent ban"
        }

        val reason = mutableListOf<String>()

        var timeout = actions.filter { it.warningAction == WarningAction.Timeout }
            .map { parseTimeoutDuration(it.data!!) }
            .reduceOrNull { acc, duration -> acc.plus(duration) }

        if (timeout != null && timeout.isPositive()) {
            val currentTimeout = member.communicationDisabledUntil

            if (currentTimeout != null) {
                val currentTimeoutDuration = member.communicationDisabledUntil!! - Clock.System.now()

                if (currentTimeoutDuration.isPositive()) {
                    timeout += currentTimeoutDuration
                }
            }

            member.edit {
                communicationDisabledUntil = Clock.System.now().plus(timeout)
                this.reason = "Too many warnings."
            }

            reason.add("- Timeout for `$timeout`")
        }

        for (action in actions) {
            when (action.warningAction) {
                WarningAction.AddRole -> {
                    val role = member.guild.getRoleOrNull(Snowflake(action.data!!))

                    if (role == null) {
                        reason.add("- Tried to apply role with id `${action.data}`, but it couldn't be found.")
                        continue
                    }

                    member.addRole(role.id, "Too many warnings.")
                    reason.add("- Applied role `${role.name}`")
                }

                WarningAction.RemoveRole -> {
                    val role = member.guild.getRoleOrNull(Snowflake(action.data!!))

                    if (role == null) {
                        reason.add("- Tried to remove role with id `${action.data}`, but it couldn't be found.")
                        continue
                    }

                    member.removeRole(role.id, "Too many warnings.")
                    reason.add("- Removed role `${role.name}`")
                }

                WarningAction.RemoveRoleGroup -> {
                    val role = member.guild.getRoleOrNull(Snowflake(action.data!!))

                    if (role == null) {
                        reason.add("- Tried to remove role-group with id `${action.data}`, but it couldn't be found.")
                        continue
                    }

                    RolesService.removeRoleGroup(member, role.id.value.toLong())
                    reason.add("- Removed role `${role.name}` and all connected roles.")
                }

                WarningAction.Timeout, WarningAction.Ban -> { /* simply ignore, is already handled above */
                }
            }
        }

        @OptIn(PrivilegedIntent::class)
        LinkingSystem.scheduler.launch {
            val reloadedMember = member.withStrategy(EntitySupplyStrategy.cachingRest).fetchMember()

            val roles = RolesService.updateRoles(reloadedMember)

            NicknameService.updateNickname(reloadedMember, roles)
        }

        return reason.joinToString(System.lineSeparator())
    }

    fun getCntEmbed(
        requestType: CntRequestType,
        description: String,
        coinValue: String,
        requirement: String,
        time: Instant,
        userId: Long?
    ): EmbedBuilder {
        val embed = getEmbed(time)
        embed.color = EmbedColor.Default.color
        embed.description = "### Craft and Transfers"

        embed.field("User supplied value") { requestType.description }
        embed.field("Request", true) { description }
        embed.field("Value", true) { coinValue }
        embed.field("Requirement", true) { requirement }
        if(userId != null) {
            embed.field("Requested by", true) { "<@$userId>" }
        }

        return embed
    }

    fun getCntEmbed(cntRequest: CntRequestModel): EmbedBuilder {
        val embed = getCntEmbed(
            cntRequest.requestType,
            cntRequest.description,
            cntRequest.coinValue,
            cntRequest.requirement,
            cntRequest.time.toKotlinInstant(),
            if (cntRequest.completed) cntRequest.user.id else null
        )

        cntRequest.claimer?.let { embed.field("Claimed by", true) { "<@${it.id}>" } }

        return embed
    }

    fun parseTimeoutDuration(data: String): Duration {
        return Duration.parse(data)
    }

    fun getFirstOfMonth(): java.time.LocalDate {
        return java.time.LocalDate.now().withDayOfMonth(1)
    }

    fun getErrorEmbeds(throwable: Throwable, message: String): MutableList<EmbedBuilder> {
        return when (throwable) {
            is CommandExecutionException -> {
                mutableListOf(getErrorEmbed(throwable))
            }

            is CommandExecutionWarning -> {
                mutableListOf(getErrorEmbed(throwable))
            }

            is PlayerNotFoundException -> {
                mutableListOf(getErrorEmbed(CommandExecutionWarning(throwable.message)))
            }

            else -> {
                val embed = getErrorEmbed(CommandExecutionException(throwable))
                if (message.isNotBlank()) {
                    embed.title = message
                }

                mutableListOf(embed)
            }
        }
    }

    fun RoleRequirementModel.toEmbed(locale: Locale? = null): EmbedBuilder {
        val embed = embed
        embed.title = "Role Requirement #$id"

        embed.field {
            name = "Role"
            inline = true
            value = "<@&${discordRole.id}>"
        }

        embed.field {
            name = "Full Comparison"
            inline = true
            value = "${
                requirementType.readableName.withLocale(locale).translate()
            } ${comparison.readableName.translate()} $count"
        }

        embed.field {
            name = "Extra Data"
            inline = true
            value = extraData ?: "`None`"
        }

        return embed
    }

    suspend fun getSlashCommandDisplay(commandName: String): String {
        return getGlobalCommandId(commandName)?.let { "</$commandName:$it>" } ?: "`/$commandName`"
    }

    suspend fun getGlobalCommandId(name: String): Snowflake? {
        return DiscordConnection.bot.kordRef.getGlobalApplicationCommands().firstOrNull { it.name == name }?.id
    }

    fun StaticMessageModel.toEmbed(locale: Locale? = null): EmbedBuilder {
        val embed = embed
        embed.title = "Static Message #${id}"
        embed.color(EmbedColor.Default)

        embed.field("Link") { "https://discord.com/channels/${server.id}/${channelId}/${messageId}" }

        embed.field("Type") { staticMessageType.name }

        embed.field("Object IDs") {
            if (objectIds.isEmpty()) {
                "None"
            } else {
                objectIds.joinToString(", ")
            }
        }

        if(embedOverride != null) {
            embed.field("Embed Override") {
                "```json\n$embedOverride\n```"
            }
        }

        return embed
    }
}

@OptIn(ExperimentalTime::class)
fun Extension.getEmbed(): EmbedBuilder {
    return ApplicationService.getEmbed(Clock.System.now())
}

@OptIn(ExperimentalTime::class)
fun Extension.getEmbed(time: Instant?): EmbedBuilder {
    return ApplicationService.getEmbed(time)
}

@OptIn(ExperimentalTime::class)
fun AbstractMessageCreateBuilder.addEmbed(function: EmbedBuilder.() -> Unit) {
    this.addEmbed(Clock.System.now(), function)
}

@OptIn(ExperimentalTime::class)
fun AbstractMessageCreateBuilder.addEmbed(time: Instant?, function: EmbedBuilder.() -> Unit) {
    val embed = ApplicationService.getEmbed(time)
    function(embed)
    embeds = ((embeds ?: mutableListOf()) + embed).toMutableList()
}

@OptIn(ExperimentalTime::class)
fun InteractionResponseModifyBuilder.addEmbed(function: EmbedBuilder.() -> Unit) {
    this.addEmbed(Clock.System.now(), function)
}

@OptIn(ExperimentalTime::class)
fun InteractionResponseModifyBuilder.addEmbed(time: Instant?, function: EmbedBuilder.() -> Unit) {
    val embed = ApplicationService.getEmbed(time)
    function(embed)
    embeds = ((embeds ?: mutableListOf()) + embed).toMutableList()
}

@OptIn(ExperimentalTime::class)
fun buildEmbed(function: EmbedBuilder.() -> Unit): EmbedBuilder {
    return buildEmbed(Clock.System.now(), function)
}

@OptIn(ExperimentalTime::class)
fun buildEmbed(time: Instant?, function: EmbedBuilder.() -> Unit): EmbedBuilder {
    val embed = ApplicationService.getEmbed(time)
    function(embed)
    return embed
}

@OptIn(ExperimentalTime::class)
fun Extension.createEmbed(function: EmbedBuilder.() -> Unit): EmbedBuilder {
    return this.createEmbed(Clock.System.now(), function)
}

@OptIn(ExperimentalTime::class)
fun Extension.createEmbed(time: Instant?, function: EmbedBuilder.() -> Unit): EmbedBuilder {
    val embed = getEmbed(time)
    function(embed)
    return embed
}

fun EmbedBuilder.color(color: EmbedColor) {
    this.color = color.color
}

suspend fun UserBehavior.getUUIDOrNull(): UUID? {
    return DiscordUserConnection.authenticated().getById(id.value.toLong())?.minecraftId
}

operator fun ReputationConnection.Companion.get(member: MemberBehavior): ClientlessReputationConnection {
    return get(member.guild.id.value.toLong(), member.id.value.toLong())
}