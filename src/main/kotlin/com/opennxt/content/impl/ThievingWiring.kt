package com.opennxt.content.impl

import com.opennxt.api.stat.ExperienceSource
import com.opennxt.content.ActionLock
import com.opennxt.content.ContentPlayer
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.rendering.PlayerUpdates
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.entity.rendering.blocks.PlayerHitsBlock
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.serverprot.MessageGame
import mu.KotlinLogging

object ThievingWiring {
    private val logger = KotlinLogging.logger { }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = SkillingWiring.ownerOf(content)

    fun install(): Boolean {
        if (!Thieving.enabled) {
            logger.warn { "thieving wiring: thieving is disabled; not installed" }
            return false
        }

        Thieving.containerSupplier = { content ->
            val world = ownerOf(content)
            if (world == null) content.inventory else PlayerInventory.backpackOf(world)
        }
        Thieving.levelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat) }.getOrDefault(1)
        }
        Thieving.baseLevelSupplier = { content, stat ->
            val world = ownerOf(content)
            if (world == null) 1 else runCatching { world.stats.getLevel(stat, boosted = false) }.getOrDefault(1)
        }
        Thieving.xpSink = { content, stat, amount ->
            ownerOf(content)?.stats?.addExperience(stat, amount, THIEVING_SOURCE)
        }
        Thieving.inventoryResend = { content ->
            val world = ownerOf(content)
            if (world != null) { PlayerInventory.sendBackpack(world); true } else false
        }
        Thieving.animationSink = { content, ids ->
            ownerOf(content)?.let { world -> PlayerUpdates.animate(world.entity, PlayerAnimationBlock(ids, 0)) }
        }
        Thieving.messageSink = { content, msg ->
            ownerOf(content)?.client?.write(MessageGame(0, msg))
        }
        Thieving.npcSaySink = { index, text ->
            FishingWiring.npcAtIndex(index)?.pendingUpdates?.say(text)
        }
        Thieving.faceSink = { content, index ->
            val world = ownerOf(content)
            val npc = FishingWiring.npcAtIndex(index)
            if (world != null && npc != null) {
                PlayerUpdates.faceNpcForInteraction(world.entity, npc.infoIndex) {
                    val still = FishingWiring.npcAtIndex(index)
                    if (still === npc && !PlayerCombat.isInCombat(npc)) npc.pendingUpdates.faceNothing()
                }
                if (world.entity.index >= 0) npc.pendingUpdates.facePlayer(world.entity.index)
            }
        }
        Thieving.faceReleaseSink = { content ->
            ownerOf(content)?.entity?.let { PlayerUpdates.releaseInteractionFace(it) }
        }
        Thieving.damageSink = { content, amount ->
            val world = ownerOf(content)
            if (world == null || amount <= 0) false
            else {
                PlayerCombat.splatType?.let { type ->
                    PlayerUpdates.hit(world.entity, PlayerHitsBlock.Hit(type, amount, 0))
                }
                val death = world.takeDamage(amount)
                if (death != null) logger.info { "thieving: ${world.name} was killed by a pickpocket retaliation - $death" }
                death != null
            }
        }
        Thieving.holdSink = { content ->
            ownerOf(content)?.entity?.movement?.let { m ->
                ActionLock.holdMovement(content, m)
                if (m.hasSteps) m.reset()
            }
        }
        Thieving.stunIconSink = { content, on ->
            ownerOf(content)?.let { runCatching { com.opennxt.content.ability.StatusEffects.playerStunIcon(it, on) } }
        }
        Thieving.standingStill = { content ->
            val world = ownerOf(content)
            if (world == null) true else runCatching { !world.entity.movement.hasSteps }.getOrDefault(true)
        }
        Thieving.spotAlive = { index -> FishingWiring.npcAtIndex(index) != null }
        Thieving.spotTile = { index ->
            FishingWiring.npcAtIndex(index)?.location?.let { intArrayOf(it.x, it.y, it.plane) }
        }

        logger.info {
            "thieving wiring: installed (sendStats=${com.opennxt.impl.stat.PlayerStatContainer.sendStatsEnabled})"
        }
        return true
    }

    fun uninstall() = Thieving.resetSeams()

    object THIEVING_SOURCE : ExperienceSource(1.0) {
        override fun toString() = "THIEVING"
    }
}
