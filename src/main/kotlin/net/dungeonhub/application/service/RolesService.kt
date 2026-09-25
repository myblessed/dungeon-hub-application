package net.dungeonhub.application.service

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Member
import dev.kord.core.entity.Role
import dev.kord.core.entity.User
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import net.dungeonhub.application.connection.getMutualServers
import net.dungeonhub.application.misc.DhScheduler
import net.dungeonhub.application.misc.suspendLazy
import net.dungeonhub.connection.*
import net.dungeonhub.enums.RoleRequirementType
import net.dungeonhub.enums.ScoreType
import net.dungeonhub.hypixel.connection.HypixelApiConnection
import net.dungeonhub.hypixel.entities.player.KnownRank
import net.dungeonhub.hypixel.entities.skyblock.CurrentMember
import net.dungeonhub.hypixel.entities.skyblock.KnownSkill
import net.dungeonhub.model.discord_role.DiscordRoleModel
import net.dungeonhub.model.discord_role_group.DiscordRoleGroupModel
import net.dungeonhub.model.role_requirement.RoleRequirementModel
import java.util.stream.Collectors
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant

@OptIn(ExperimentalTime::class)
object RolesService {
    val scheduler = DhScheduler()

    suspend fun updateRoles(user: User, cacheExpiration: Int = 60 * 3): Map<Long, List<Role>> {
        return scheduler.async {
            user.getMutualServers().map { member ->
                member.guildId.value.toLong() to updateRoles(member, cacheExpiration) // TODO rather think about mass-syncing
            }.toList().toMap()
        }.await()
    }

    suspend fun updateRoles(member: Member, cacheExpiration: Int = 60 * 3): List<Role> {
        val newRoles = calculateRoles(member, cacheExpiration)

        member.edit {
            roles = newRoles.map { role -> role.id }.toMutableSet()
        }

        return newRoles
    }

    suspend fun calculateRoles(member: Member, cacheExpiration: Int): List<Role> {
        val serverRoles = (DiscordRoleConnection[member.guildId.value.toLong()].authenticated().getAllRoles() ?: emptyList())
            .stream()
            .collect(
                Collectors.toMap(
                    { obj: DiscordRoleModel -> obj.id },
                    { discordRoleModel: DiscordRoleModel -> discordRoleModel })
            )

        var discordRoles: MutableSet<Snowflake> = member.roleIds.toMutableSet()

        val isVerified = DiscordUserConnection.authenticated().getLinkedById(member.id.value.toLong()) != null

        val rolesToAdd = serverRoles.values.stream()
            .filter { roleModel -> roleModel.shouldBeAdded(isVerified) }
            .map {
                scheduler.async {
                    member.guild.getRoleOrNull(
                        Snowflake(it.id)
                    )
                }
            }
            .toList()

        val rolesToRemove = serverRoles.values.stream()
            .filter { roleModel -> roleModel.shouldBeRemoved(isVerified) }
            .map { obj: DiscordRoleModel -> obj.id }
            .map { id: Long? ->
                scheduler.async {
                    member.guild.getRoleOrNull(
                        Snowflake(id!!)
                    )
                }
            }
            .toList()

        discordRoles.addAll(rolesToAdd.filter { it.await() != null }.map { role -> role.await()?.id!! })
        discordRoles.removeAll(rolesToRemove.filter { it.await() != null }.map { role -> role.await()?.id!! }.toSet())

        val roleRequirements = calculateRoleRequirements(member, cacheExpiration)

        discordRoles.addAll(roleRequirements.filter { it.value }.map { Snowflake(it.key.discordRole.id) })
        discordRoles.removeAll(roleRequirements.filter { !it.value }.map { Snowflake(it.key.discordRole.id) }.toSet())

        var lastRoles = 0
        while (lastRoles != discordRoles.size) {
            lastRoles = discordRoles.size
            discordRoles = applyRoleGroups(member.guild, discordRoles)
        }

        return discordRoles.map { id -> member.guild.getRole(id) }
    }

    suspend fun calculateRoleRequirements(member: Member, cacheExpiration: Int): Map<RoleRequirementModel, Boolean> {
        val roleRequirements =
            RoleRequirementConnection[member.guild.id.value.toLong()].authenticated().getAllRoleRequirements() ?: emptyList()

        return roleRequirements.associateWith {
            checkRoleRequirement(it, member, cacheExpiration)
        }
    }

    //TODO maybe make profile loading lazy? -> would then only be loaded if needed
    suspend fun checkRoleRequirement(roleRequirement: RoleRequirementModel, member: Member, cacheExpiration: Int): Boolean {
        if (!roleRequirement.checkExtraData()) return false

        val hypixelApiConnection = HypixelApiConnection().withCacheExpiration(cacheExpiration)

        val discordServer = suspendLazy {
            DiscordServerConnection.authenticated().findServerById(member.guild.id.value.toLong())
        }

        val discordUser = suspendLazy {
            DiscordUserConnection.authenticated().getLinkedById(member.id.value.toLong())
        }

        val uuid = suspendLazy {
            discordUser.get()?.minecraftId
        }

        val profiles = suspendLazy {
            val uuid = uuid.get() ?: return@suspendLazy null
            hypixelApiConnection.getSkyblockProfiles(uuid).valueOrNull
        }

        val selectedProfiles = suspendLazy {
            val discordUser = discordUser.get() ?: return@suspendLazy emptyList()
            val profiles = profiles.get() ?: return@suspendLazy emptyList()

            profiles.profiles
                .filter { discordUser.primarySkyblockProfile == null || it.profileId == discordUser.primarySkyblockProfile }
                .takeIf { it.isNotEmpty() }
                ?: profiles.profiles
        }

        val profileMembers = suspendLazy {
            selectedProfiles.get().mapNotNull { it.members.firstOrNull { member -> member.uuid == uuid.get() } }
                .filterIsInstance<CurrentMember>().takeIf { it.isNotEmpty() }
        }

        val playerData = suspendLazy {
            val uuid = uuid.get() ?: return@suspendLazy null
            hypixelApiConnection.getPlayerData(uuid).valueOrNull
        }

        val roleRequirementGuild by lazy {
            roleRequirement.extraData?.let { HypixelApiConnection().withCacheExpiration(5).getGuild(it).valueOrNull }
        }

        val usersGuild = suspendLazy {
            val uuid = uuid.get() ?: return@suspendLazy null
            hypixelApiConnection.getPlayerGuild(uuid).valueOrNull
        }

        val bingoData = suspendLazy {
            val uuid = uuid.get() ?: return@suspendLazy null
            hypixelApiConnection.getBingoData(uuid).valueOrNull
        }

        //TODO add check for legendary griffin pet
        return when (roleRequirement.requirementType) {
            RoleRequirementType.SkyblockLevel -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf {
                        it.leveling.level
                    }
                )
            }

            RoleRequirementType.CatacombsLevel -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf { it.dungeons?.catacombsLevel ?: 0 }
                )
            }

            RoleRequirementType.FarmingLevel -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf {
                        KnownSkill.Farming.calculateLevel(
                            it.playerData.nonCosmeticExperience?.get(
                                KnownSkill.Farming
                            ) ?: 0.0
                        )
                    }
                )
            }

            RoleRequirementType.MiningLevel -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf {
                        KnownSkill.Mining.calculateLevel(
                            it.playerData.nonCosmeticExperience?.get(
                                KnownSkill.Mining
                            ) ?: 0.0
                        )
                    }
                )
            }

            RoleRequirementType.CombatLevel -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf {
                        KnownSkill.Combat.calculateLevel(
                            it.playerData.nonCosmeticExperience?.get(
                                KnownSkill.Combat
                            ) ?: 0.0
                        )
                    }
                )
            }

            RoleRequirementType.FishingLevel -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf {
                        KnownSkill.Fishing.calculateLevel(
                            it.playerData.nonCosmeticExperience?.get(
                                KnownSkill.Fishing
                            ) ?: 0.0
                        )
                    }
                )
            }

            RoleRequirementType.SkillAverage -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf { it.playerData.skillAverage }.toInt()
                )
            }

            RoleRequirementType.HighestSkill -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf {
                        it.playerData.nonCosmeticExperience?.maxOf { skill ->
                            skill.key.calculateLevel(
                                skill.value
                            )
                        } ?: 0
                    }
                )
            }

            //TODO check for carry type
            RoleRequirementType.CurrentScore -> {
                roleRequirement.compare(
                    DiscordServerConnection.authenticated().getScores(discordServer.get() ?: return false, member.id.value.toLong())?.filter {
                        it.scoreType == ScoreType.Default
                    }?.sumOf { it.scoreAmount ?: 0 }?.toInt() ?: 0
                )
            }

            //TODO check for carry type
            RoleRequirementType.AlltimeScore -> {
                roleRequirement.compare(
                    DiscordServerConnection.authenticated().getScores(discordServer.get() ?: return false, member.id.value.toLong())?.filter {
                        it.scoreType == ScoreType.Alltime
                    }?.sumOf { it.scoreAmount ?: 0 }?.toInt() ?: 0
                )
            }

            RoleRequirementType.TotalCarries -> {
                //TODO implement
                false
            }

            RoleRequirementType.TotalCarriesInTimeFrame -> {
                //TODO implement
                false
            }

            //TODO maybe also add money earned?
            RoleRequirementType.MoneySpent -> {
                roleRequirement.compare(
                    DiscordServerConnection.authenticated().getTotalAmountOfMoneySpent(
                        member.guild.id.value.toLong(),
                        userId = member.id.value.toLong()
                    )?.toInt() ?: 0
                )
            }

            RoleRequirementType.MoneySpentInTimeFrame -> {
                val duration = roleRequirement.extraData?.let(Duration::parse)
                    ?: return false

                roleRequirement.compare(
                    DiscordServerConnection.authenticated().getTotalAmountOfMoneySpent(
                        member.guild.id.value.toLong(),
                        userId = member.id.value.toLong(),
                        since = Clock.System.now().minus(duration).toJavaInstant()
                    )?.toInt() ?: 0
                )
            }

            RoleRequirementType.HypixelRank -> {
                val rank = playerData.get()?.rank ?: return false

                if (rank !is KnownRank) return false

                roleRequirement.compare(
                    rank.ordinal
                )
            }

            RoleRequirementType.GuildMembership -> {
                val guild = usersGuild.get()?.takeIf { it.name == roleRequirement.extraData } ?: roleRequirementGuild

                roleRequirement.compare(
                    if (guild?.members?.any { it.uuid == uuid.get() } == true) 1 else 0
                )
            }

            RoleRequirementType.GuildRank -> {
                val guild = usersGuild.get()?.takeIf { it.name == roleRequirement.extraData } ?: roleRequirementGuild

                roleRequirement.compare(
                    guild?.members?.firstOrNull { it.uuid == uuid.get() }?.rank?.priority ?: 0
                )
            }

            RoleRequirementType.MagicalPower -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(profileMembers.maxOf { it.accessoryBag?.highestMagicalPower ?: 0 })
            }

            RoleRequirementType.ClassAverage -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf {
                        it.dungeons?.classAverage ?: 0.0
                    }.toInt()
                )
            }

            RoleRequirementType.HighestCritDamage -> {
                val profileMembers = profileMembers.get() ?: return false
                roleRequirement.compare(
                    profileMembers.maxOf { it.playerStats?.highestCritDamage ?: 0.0 }.roundToInt()
                )
            }

            RoleRequirementType.BingoRank -> {
                roleRequirement.compare(
                    (profiles.get() ?: return false).bingoRank?.ordinal ?: 0
                )
            }

            RoleRequirementType.TotalBingoPoints -> {
                roleRequirement.compare(
                    bingoData.get()?.totalPoints ?: 0
                )
            }

            RoleRequirementType.Reputation -> {
                roleRequirement.compare(
                    ReputationConnection[member].authenticated().calculateReputation()?.toInt() ?: 0
                )
            }

            RoleRequirementType.ScoreLeaderboardRank -> {
                val extraData = roleRequirement.extraData?.split(";") ?: return false

                val scoreType = try {
                    ScoreType.valueOf(extraData[0])
                } catch (_: IllegalArgumentException) {
                    return false
                }

                val carryTypeIdentifier = extraData.getOrNull(1)

                val carryType = if (carryTypeIdentifier != null) {
                    CarryTypeConnection[member.guild.id.value.toLong()].authenticated().getByIdentifier(carryTypeIdentifier) ?: return false
                } else null

                val leaderboard = if(carryType != null) {
                    ScoreConnection[carryType].authenticated().loadLeaderboard(
                        scoreType = scoreType,
                        userId = member.id.value.toLong()
                    )
                } else {
                    DiscordServerConnection.authenticated().loadTotalLeaderboard(
                        member.guild.id.value.toLong(),
                        scoreType = scoreType,
                        userId = member.id.value.toLong()
                    )
                }

                leaderboard?.playerPosition?.takeIf { it != -1 }?.let {
                    roleRequirement.compare(
                        it + 1
                    )
                } ?: false
            }

            RoleRequirementType.ReputationLeaderboardRank -> {
                val leaderboard = DiscordServerConnection.authenticated().loadReputationLeaderboard(
                    member.guild.id.value.toLong(),
                    userId = member.id.value.toLong()
                )

                leaderboard?.playerPosition?.takeIf { it != -1 }?.let {
                    roleRequirement.compare(
                        it + 1
                    )
                } ?: false
            }
        }
    }

    suspend fun applyRoleGroups(server: GuildBehavior, roles: Set<Snowflake>): MutableSet<Snowflake> {
        var mutableRoles = roles.toMutableSet()
        val roleGroups = DiscordRoleGroupConnection[server.id.value.toLong()].authenticated().getAll() ?: emptyList()

        var lastRoles = 0
        while (lastRoles != mutableRoles.size) {
            lastRoles = mutableRoles.size

            mutableRoles = applyRoleGroups(mutableRoles, roleGroups)
        }

        return mutableRoles
    }

    fun applyRoleGroups(
        roles: MutableSet<Snowflake>,
        roleGroups: List<DiscordRoleGroupModel>
    ): MutableSet<Snowflake> {
        roleGroups.stream()
            .filter { discordRoleGroupModel: DiscordRoleGroupModel ->
                roles.stream().anyMatch { role ->
                    role.value.toLong() == discordRoleGroupModel.discordRole.id
                }
            }
            .map { obj: DiscordRoleGroupModel -> obj.roleGroup }
            .map { obj: DiscordRoleModel -> obj.id }
            .map { id: Long? ->
                Snowflake(id!!)
            }
            .forEach { e ->
                roles.add(
                    e
                )
            }

        val roleModels = java.util.List.copyOf(roleGroups).stream()
            .collect(
                Collectors.toMap(
                    { discordRoleGroupModel: DiscordRoleGroupModel ->
                        discordRoleGroupModel.roleGroup.id
                    },
                    { discordRoleGroupModel: DiscordRoleGroupModel ->
                        mutableListOf(
                            discordRoleGroupModel.discordRole
                        )
                    },
                    { o: MutableList<DiscordRoleModel>, o2: List<DiscordRoleModel>? ->
                        o.addAll(
                            o2!!
                        )
                        o
                    }
                ))

        var rolesBefore = 0
        while (rolesBefore != roles.size) {
            rolesBefore = roles.size

            for ((key, value) in roleModels) {
                if (roles.stream().anyMatch { role -> role.value.toLong() == key }
                    && value.stream()
                        .allMatch { discordRoleModel: DiscordRoleModel ->
                            roles.stream()
                                .noneMatch { role -> role.value.toLong() == discordRoleModel.id }
                        }
                ) {
                    roles.removeIf { role -> role.value.toLong() == key }
                }
            }
        }

        return roles
    }

    suspend fun removeRoleGroup(member: Member, roleGroupId: Long) {
        var userRoles = member.roleIds.toMutableSet()

        val roleGroups = DiscordRoleGroupConnection[member.guild.id.value.toLong()].authenticated().getAll() ?: listOf()

        var lastRoles = 0
        while (lastRoles != userRoles.size) {
            lastRoles = userRoles.size

            userRoles = removeRoleGroups(userRoles, roleGroups, setOf(roleGroupId))
        }

        member.edit {
            roles = userRoles
        }
    }

    private fun removeRoleGroups(
        userRoles: MutableSet<Snowflake>,
        roleGroups: List<DiscordRoleGroupModel>,
        removeRoleGroups: Set<Long>
    ): MutableSet<Snowflake> {
        if (removeRoleGroups.isEmpty()) {
            return userRoles
        }

        val newRoleGroups = roleGroups.stream().filter {
            userRoles.contains(Snowflake(it.discordRole.id)) || userRoles.contains(Snowflake(it.roleGroup.id))
        }.toList()

        val toRemove = roleGroups.stream()
            .filter { roleGroup ->
                removeRoleGroups.any { roleGroup.roleGroup.id == it }
            }
            .map { Snowflake(it.discordRole.id) }
            .collect(Collectors.toSet())

        userRoles.removeAll(toRemove)

        return removeRoleGroups(userRoles, newRoleGroups, toRemove.map { it.value.toLong() }.toSet())
    }
}