package net.dungeonhub.application.commands

import net.dungeonhub.application.enums.EmbedColor
import net.dungeonhub.enums.IngameCarryType
import net.dungeonhub.enums.QueueStep
import net.dungeonhub.model.carry_difficulty.CarryDifficultyModel
import net.dungeonhub.model.carry_queue.CarryQueueModel
import net.dungeonhub.model.carry_tier.CarryTierModel
import net.dungeonhub.model.carry_type.CarryTypeModel
import net.dungeonhub.model.discord_server.DiscordServerModel
import net.dungeonhub.model.discord_user.DiscordUserModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.toKotlinInstant

class LoggingSystemTest {
    @Test
    fun `repeated single carries from the same carrier and difficulty are merged`() {
        val first = queue(id = 1, playerId = 101, time = Instant.parse("2026-01-01T10:00:00Z"))
        val second = queue(id = 2, playerId = 102, time = Instant.parse("2026-01-01T10:01:00Z"))
        val third = queue(id = 3, playerId = 103, time = Instant.parse("2026-01-01T10:02:00Z"))

        val groups = LoggingSystem.compactCarryEntries(listOf(first, second, third))

        assertEquals(1, groups.size)
        assertEquals(listOf(first, second, third), groups.single())
    }

    @Test
    fun `entries are ordered by time before adjacent carries are merged`() {
        val first = queue(id = 1, time = Instant.parse("2026-01-01T10:00:00Z"))
        val second = queue(id = 2, time = Instant.parse("2026-01-01T10:01:00Z"))
        val third = queue(id = 3, time = Instant.parse("2026-01-01T10:02:00Z"))

        val groups = LoggingSystem.compactCarryEntries(listOf(third, first, second))

        assertEquals(listOf(first, second, third), groups.single())
    }

    @Test
    fun `single carries with different carriers are not merged`() {
        val first = queue(id = 1, carrierId = 10)
        val second = queue(id = 2, carrierId = 11)

        assertEquals(listOf(listOf(first), listOf(second)), LoggingSystem.compactCarryEntries(listOf(first, second)))
    }

    @Test
    fun `single carries with different difficulties are not merged`() {
        val first = queue(id = 1, difficulty = difficulty(id = 1, displayName = "Floor One"))
        val second = queue(id = 2, difficulty = difficulty(id = 2, displayName = "Floor Two"))

        assertEquals(listOf(listOf(first), listOf(second)), LoggingSystem.compactCarryEntries(listOf(first, second)))
    }

    @Test
    fun `bulk carries remain separate and break a run of mergeable entries`() {
        val first = queue(id = 1)
        val bulk = queue(id = 2, amount = 2)
        val last = queue(id = 3)

        assertEquals(
            listOf(listOf(first), listOf(bulk), listOf(last)),
            LoggingSystem.compactCarryEntries(listOf(first, bulk, last))
        )
    }

    @Test
    fun `an empty carry collection produces no groups`() {
        assertEquals(emptyList(), LoggingSystem.compactCarryEntries(emptyList()))
    }

    @Test
    fun `grouped carry message contains totals players score and transcript`() {
        val first = queue(
            id = 1,
            playerId = 101,
            attachmentLink = "https://example.test/transcript",
            time = Instant.parse("2026-01-01T10:00:00Z")
        )
        val second = queue(
            id = 2,
            playerId = 102,
            time = Instant.parse("2026-01-01T10:01:00Z")
        )

        val embed = LoggingSystem.loadGroupedCarryEmbed(listOf(first, second))

        assertEquals(Instant.parse("2026-01-01T10:01:00Z").toKotlinInstant(), embed.timestamp)
        assertEquals(EmbedColor.Information.color, embed.color)
        assertEquals("2", embed.fields.single { it.name == "Number of carries" }.value)
        assertEquals("Dungeon - Floor One", embed.fields.single { it.name == "Type of carry" }.value)
        assertEquals("<@101>\n<@102>", embed.fields.single { it.name == "Players" }.value)
        assertEquals("<@10>", embed.fields.single { it.name == "Carrier" }.value)
        assertEquals("10", embed.fields.single { it.name == "Gained score" }.value)
        assertEquals(
            "[Click to open](https://example.test/transcript)",
            embed.fields.single { it.name == "Transcript-Link" }.value
        )
    }

    @Test
    fun `grouped carry message uses singular player label and omits absent transcript`() {
        val carry = queue(id = 1, playerId = 101, amount = 3, attachmentLink = null)

        val embed = LoggingSystem.loadGroupedCarryEmbed(listOf(carry))

        assertEquals("<@101>", embed.fields.single { it.name == "Player" }.value)
        assertEquals("3", embed.fields.single { it.name == "Number of carries" }.value)
        assertEquals("15", embed.fields.single { it.name == "Gained score" }.value)
        assertNull(embed.fields.singleOrNull { it.name == "Players" })
        assertNull(embed.fields.singleOrNull { it.name == "Transcript-Link" })
    }

    @Test
    fun `compaction retains the original queue model instances`() {
        val carry = queue(id = 1)

        assertSame(carry, LoggingSystem.compactCarryEntries(listOf(carry)).single().single())
    }

    @Test
    fun `non-adjacent compatible carries stay separate when another carrier interrupts them`() {
        val first = queue(id = 1, carrierId = 10)
        val interruption = queue(id = 2, carrierId = 11, time = Instant.parse("2026-01-01T10:01:00Z"))
        val last = queue(id = 3, carrierId = 10, time = Instant.parse("2026-01-01T10:02:00Z"))

        assertEquals(listOf(listOf(first), listOf(interruption), listOf(last)), LoggingSystem.compactCarryEntries(listOf(first, interruption, last)))
    }

    @Test
    fun `non-adjacent compatible carries stay separate when another difficulty interrupts them`() {
        val floorOne = difficulty(id = 1)
        val first = queue(id = 1, difficulty = floorOne)
        val interruption = queue(id = 2, difficulty = difficulty(id = 2), time = Instant.parse("2026-01-01T10:01:00Z"))
        val last = queue(id = 3, difficulty = floorOne, time = Instant.parse("2026-01-01T10:02:00Z"))

        assertEquals(3, LoggingSystem.compactCarryEntries(listOf(first, interruption, last)).size)
    }

    @Test
    fun `bulk carry before singles does not prevent following singles from merging`() {
        val bulk = queue(id = 1, amount = 4)
        val first = queue(id = 2, time = Instant.parse("2026-01-01T10:01:00Z"))
        val second = queue(id = 3, time = Instant.parse("2026-01-01T10:02:00Z"))

        assertEquals(listOf(listOf(bulk), listOf(first, second)), LoggingSystem.compactCarryEntries(listOf(bulk, first, second)))
    }

    @Test
    fun `bulk carry after singles does not prevent preceding singles from merging`() {
        val first = queue(id = 1)
        val second = queue(id = 2, time = Instant.parse("2026-01-01T10:01:00Z"))
        val bulk = queue(id = 3, amount = 4, time = Instant.parse("2026-01-01T10:02:00Z"))

        assertEquals(listOf(listOf(first, second), listOf(bulk)), LoggingSystem.compactCarryEntries(listOf(first, second, bulk)))
    }

    @Test
    fun `two adjacent bulk carries are never merged`() {
        val first = queue(id = 1, amount = 2)
        val second = queue(id = 2, amount = 2)

        assertEquals(listOf(listOf(first), listOf(second)), LoggingSystem.compactCarryEntries(listOf(first, second)))
    }

    @Test
    fun `same difficulty id merges even when models are separate instances`() {
        val first = queue(id = 1, difficulty = difficulty(id = 7, displayName = "One"))
        val second = queue(id = 2, difficulty = difficulty(id = 7, displayName = "Two"))

        assertEquals(1, LoggingSystem.compactCarryEntries(listOf(first, second)).size)
    }

    @Test
    fun `same carrier id merges even when user models are separate instances`() {
        val first = queue(id = 1, carrierId = 77)
        val second = queue(id = 2, carrierId = 77)

        assertEquals(2, LoggingSystem.compactCarryEntries(listOf(first, second)).single().size)
    }

    @Test
    fun `equal timestamps retain input order`() {
        val first = queue(id = 1)
        val second = queue(id = 2, carrierId = 11)

        assertEquals(listOf(listOf(second), listOf(first)), LoggingSystem.compactCarryEntries(listOf(second, first)))
    }

    @Test
    fun `entries without timestamps sort before timestamped entries`() {
        val undated = queue(id = 1, time = null)
        val dated = queue(id = 2)

        assertEquals(listOf(undated, dated), LoggingSystem.compactCarryEntries(listOf(dated, undated)).single())
    }

    @Test
    fun `compaction does not alter the input collection`() {
        val late = queue(id = 2, time = Instant.parse("2026-01-01T10:01:00Z"))
        val early = queue(id = 1)
        val input = mutableListOf(late, early)

        LoggingSystem.compactCarryEntries(input)

        assertEquals(listOf(late, early), input)
    }

    @Test
    fun `carrier grouping keeps each carriers entries together`() {
        val first = queue(id = 1, carrierId = 10)
        val second = queue(id = 2, carrierId = 20)
        val third = queue(id = 3, carrierId = 10)

        assertEquals(mapOf(10L to listOf(first, third), 20L to listOf(second)), LoggingSystem.groupCarryEntriesByCarrier(listOf(first, second, third)))
    }

    @Test
    fun `carrier grouping preserves first-seen carrier order`() {
        val first = queue(id = 1, carrierId = 20)
        val second = queue(id = 2, carrierId = 10)

        assertEquals(listOf(20L, 10L), LoggingSystem.groupCarryEntriesByCarrier(listOf(first, second)).keys.toList())
    }

    @Test
    fun `carrier grouping of no carries is empty`() {
        assertEquals(emptyMap(), LoggingSystem.groupCarryEntriesByCarrier(emptyList()))
    }

    @Test
    fun `single denied carry uses singular notification wording`() {
        assertEquals("Your log was denied by <@42>.", LoggingSystem.denialNotificationContent(1, "<@42>"))
    }

    @Test
    fun `multiple denied carries use plural notification wording`() {
        assertEquals("Your logs were denied by <@42>.", LoggingSystem.denialNotificationContent(3, "<@42>"))
    }

    @Test
    fun `grouped carry message lists a repeated player once`() {
        val first = queue(id = 1, playerId = 101)
        val second = queue(id = 2, playerId = 101)

        val embed = LoggingSystem.loadGroupedCarryEmbed(listOf(first, second))

        assertEquals("<@101>", embed.fields.single { it.name == "Player" }.value)
        assertNull(embed.fields.singleOrNull { it.name == "Players" })
    }

    @Test
    fun `grouped carry message uses first carry as type and carrier representative`() {
        val first = queue(id = 1, carrierId = 10, difficulty = difficulty(id = 1, displayName = "First"))
        val second = queue(id = 2, carrierId = 20, difficulty = difficulty(id = 2, displayName = "Second"))

        val embed = LoggingSystem.loadGroupedCarryEmbed(listOf(first, second))

        assertEquals("Dungeon - First", embed.fields.single { it.name == "Type of carry" }.value)
        assertEquals("<@10>", embed.fields.single { it.name == "Carrier" }.value)
    }

    @Test
    fun `grouped carry message takes timestamp from last carry`() {
        val first = queue(id = 1, time = Instant.parse("2026-01-01T10:02:00Z"))
        val second = queue(id = 2, time = Instant.parse("2026-01-01T10:00:00Z"))

        assertEquals(second.time?.toKotlinInstant(), LoggingSystem.loadGroupedCarryEmbed(listOf(first, second)).timestamp)
    }

    @Test
    fun `grouped carry message sums different carry amounts`() {
        val first = queue(id = 1, amount = 2)
        val second = queue(id = 2, amount = 3)

        val embed = LoggingSystem.loadGroupedCarryEmbed(listOf(first, second))

        assertEquals("5", embed.fields.single { it.name == "Number of carries" }.value)
        assertEquals("25", embed.fields.single { it.name == "Gained score" }.value)
    }

    @Test
    fun `grouped carry message only links transcript from representative carry`() {
        val first = queue(id = 1, attachmentLink = null)
        val second = queue(id = 2, attachmentLink = "https://example.test/second")

        assertNull(LoggingSystem.loadGroupedCarryEmbed(listOf(first, second)).fields.singleOrNull { it.name == "Transcript-Link" })
    }

    @Test
    fun `accepted overview includes approver`() {
        val embed = LoggingSystem.createCarryOverview(listOf(listOf(queue(id = 1))), 42)

        assertEquals("<@42>", embed.fields.single { it.name == "Approved by" }.value)
    }

    @Test
    fun `denied overview omits approver`() {
        val embed = LoggingSystem.createCarryOverview(listOf(listOf(queue(id = 1))), null)

        assertNull(embed.fields.singleOrNull { it.name == "Approved by" })
    }

    @Test
    fun `overview describes compacted carries as one line`() {
        val first = queue(id = 1, playerId = 101)
        val second = queue(id = 2, playerId = 102)
        val compacted = LoggingSystem.compactCarryEntries(listOf(first, second))

        val embed = LoggingSystem.createCarryOverview(compacted, 42)

        assertEquals("- 2 Dungeon - Floor One for <@101>, <@102> (10 score)", embed.description)
    }

    @Test
    fun `overview gives separate lines to non-compactable carries`() {
        val first = queue(id = 1, carrierId = 10)
        val second = queue(id = 2, carrierId = 11)
        val compacted = LoggingSystem.compactCarryEntries(listOf(first, second))

        assertEquals(2, LoggingSystem.createCarryOverview(compacted, null).description?.lines()?.size)
    }

    @Test
    fun `overview uses last carry timestamp and transcript`() {
        val first = queue(id = 1, attachmentLink = "https://example.test/first")
        val second = queue(id = 2, attachmentLink = "https://example.test/last", time = Instant.parse("2026-01-01T10:01:00Z"))

        val embed = LoggingSystem.createCarryOverview(listOf(listOf(first), listOf(second)), null)

        assertEquals(second.time?.toKotlinInstant(), embed.timestamp)
        assertEquals("[Click to open](https://example.test/last)", embed.fields.single { it.name == "Transcript-Link" }.value)
    }

    @Test
    fun `overview has information title`() {
        val embed = LoggingSystem.createCarryOverview(listOf(listOf(queue(id = 1))), null)

        assertEquals("Information", embed.title)
    }

    private fun queue(
        id: Long,
        carrierId: Long = 10,
        playerId: Long = 100,
        amount: Int = 1,
        difficulty: CarryDifficultyModel = difficulty(),
        attachmentLink: String? = null,
        time: Instant? = Instant.parse("2026-01-01T10:00:00Z")
    ) = CarryQueueModel(
        id,
        QueueStep.Transcript,
        DiscordUserModel(carrierId, null, null),
        DiscordUserModel(playerId, null, null),
        amount,
        difficulty,
        null,
        attachmentLink,
        time
    )

    private fun difficulty(
        id: Long = 1,
        displayName: String = "Floor One",
        score: Int = 5
    ): CarryDifficultyModel {
        val carryType = CarryTypeModel(1, "dungeon", "Dungeons", DiscordServerModel(1), null, false)
        val tier = CarryTierModel(1, "dungeon", "Dungeon", carryType, null, null, null, null, null)
        return CarryDifficultyModel(
            id,
            "floor-$id",
            displayName,
            tier,
            1,
            null,
            null,
            score,
            null,
            null,
            IngameCarryType.Floor1
        )
    }
}
