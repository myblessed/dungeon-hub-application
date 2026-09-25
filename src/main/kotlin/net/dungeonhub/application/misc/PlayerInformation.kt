package net.dungeonhub.application.misc

import dev.kord.core.entity.User
import dev.kord.core.entity.effectiveName
import net.dungeonhub.application.exceptions.NotLinkedWarning
import net.dungeonhub.hypixel.connection.HypixelApiConnection
import net.dungeonhub.model.discord_user.DiscordUserModel
import net.dungeonhub.mojang.connection.MojangConnection

class PlayerInformation(
    private val user: User,
    private val discordUserModel: DiscordUserModel,
    private val cacheExpiration: Int
) {
    private val apiConnection = HypixelApiConnection().withCacheExpiration(cacheExpiration)

    val profiles by lazy {
        apiConnection.getSkyblockProfiles(
            discordUserModel.minecraftId ?: throw NotLinkedWarning()
        ).valueOrNull?.profiles
    }

    val selectedProfiles by lazy {
        profiles?.filter { discordUserModel.primarySkyblockProfile == null || it.profileId == discordUserModel.primarySkyblockProfile }
            ?.takeIf { it.isNotEmpty() }
            ?: profiles
    }

    val replacements: Map<String, () -> String>
        get() {
            val replacements: MutableMap<String, () -> String> = HashMap()

            replacements["discord.name"] = { user.username }
            replacements["discord.displayname"] = { user.effectiveName }
            replacements["minecraft.name"] = {
                MojangConnection.getNameByUUID(discordUserModel.minecraftId ?: throw NotLinkedWarning())
            }
            replacements["skyblock.catacombs.level"] =
                {
                    selectedProfiles?.maxOfOrNull {
                        it.getCurrentMember(discordUserModel.minecraftId!!)?.dungeons?.catacombsLevel ?: 0
                    }?.toString() ?: "?"
                }
            replacements["skyblock.level"] = {
                selectedProfiles?.maxOfOrNull {
                    it.getCurrentMember(discordUserModel.minecraftId ?: throw NotLinkedWarning())?.leveling?.level
                        ?: 0
                }?.toString() ?: "?"
            }

            return replacements
        }
}