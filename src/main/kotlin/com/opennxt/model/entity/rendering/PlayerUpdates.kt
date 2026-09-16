package com.opennxt.model.entity.rendering

import com.opennxt.model.entity.Entity
import com.opennxt.model.entity.rendering.blocks.PlayerAnimationBlock
import com.opennxt.model.entity.rendering.blocks.PlayerByte11Block
import com.opennxt.model.entity.rendering.blocks.PlayerDiscardedBlock
import com.opennxt.model.entity.rendering.blocks.PlayerFaceDirectionBlock
import com.opennxt.model.entity.rendering.blocks.PlayerFaceEntityBlock
import com.opennxt.model.entity.rendering.blocks.PlayerFlag21Block
import com.opennxt.model.entity.rendering.blocks.PlayerForceMovementBlock
import com.opennxt.model.entity.rendering.blocks.PlayerHitsBlock
import com.opennxt.model.entity.rendering.blocks.PlayerSayBlock
import com.opennxt.model.entity.rendering.blocks.PlayerStringFlagBlock

object PlayerUpdates {
    val EXPERIMENTAL: Set<UpdateBlockType> =
        UpdateBlockType.values().filter { it != UpdateBlockType.APPEARANCE }.toSet()

    val extendedEnabled: Boolean
        get() = System.getProperty("opennxt.experiment.playerExtended") != "false"

    fun blockEnabled(type: UpdateBlockType): Boolean {
        if (type !in EXPERIMENTAL) return true
        if (!extendedEnabled) return false
        return System.getProperty("opennxt.experiment.player.block.${type.name.lowercase()}") != "false"
    }

    fun queue(entity: Entity, block: UpdateBlock) {
        entity.renderer.blocks[block.type.playerPos] = block
    }

    fun remove(entity: Entity, type: UpdateBlockType) {
        entity.renderer.blocks[type.playerPos] = null
    }

    fun clear(entity: Entity) {
        for (type in EXPERIMENTAL) entity.renderer.blocks[type.playerPos] = null
        com.opennxt.model.entity.rendering.blocks.PlayerSpotanimBlock950.clear(entity)
    }

    fun animate(entity: Entity, block: PlayerAnimationBlock) = queue(entity, block)
    fun animate(entity: Entity, id: Int, delay: Int = 0) = queue(entity, PlayerAnimationBlock.single(id, delay))

    fun hit(entity: Entity, block: PlayerHitsBlock) = queue(entity, block)
    fun hit(entity: Entity, vararg splats: PlayerHitsBlock.Hit) = queue(entity, PlayerHitsBlock(splats.toList()))

    fun say(entity: Entity, block: PlayerSayBlock) = queue(entity, block)
    fun say(entity: Entity, text: String) = queue(entity, PlayerSayBlock(text))

    fun faceEntity(entity: Entity, block: PlayerFaceEntityBlock) { interactionFaceLocks.remove(entity); queue(entity, block) }
    fun faceNpc(entity: Entity, index: Int) { interactionFaceLocks.remove(entity); queue(entity, PlayerFaceEntityBlock.npc(index)) }
    fun facePlayer(entity: Entity, index: Int) { interactionFaceLocks.remove(entity); queue(entity, PlayerFaceEntityBlock.player(index)) }
    fun faceNothing(entity: Entity) { interactionFaceLocks.remove(entity); queue(entity, PlayerFaceEntityBlock.clear()) }

    private val interactionFaceLocks: MutableMap<Entity, () -> Unit> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    fun faceNpcForInteraction(entity: Entity, index: Int, onRelease: () -> Unit = {}) {
        faceNpc(entity, index)
        interactionFaceLocks[entity] = onRelease
    }

    fun hasInteractionFace(entity: Entity): Boolean = interactionFaceLocks.containsKey(entity)

    fun releaseInteractionFace(entity: Entity): Boolean {
        val onRelease = interactionFaceLocks.remove(entity) ?: return false
        queue(entity, PlayerFaceEntityBlock.clear())
        runCatching { onRelease() }
        return true
    }

    fun faceDirection(entity: Entity, block: PlayerFaceDirectionBlock) = queue(entity, block)
    fun faceDirection(entity: Entity, angle: Int) = queue(entity, PlayerFaceDirectionBlock(angle))

    fun forceMovement(entity: Entity, block: PlayerForceMovementBlock) = queue(entity, block)

    fun stringFlag(entity: Entity, block: PlayerStringFlagBlock) = queue(entity, block)
    fun stringFlag(entity: Entity, text: String, flag: Int = PlayerStringFlagBlock.FLAG_ENABLE) =
        queue(entity, PlayerStringFlagBlock(text, flag))

    fun byte11(entity: Entity, value: Int) = queue(entity, PlayerByte11Block(value))
    fun flag21(entity: Entity, value: Int) = queue(entity, PlayerFlag21Block(value))

    fun discarded(entity: Entity, type: UpdateBlockType, f1: Int = 0, f2: Int = 0, f3: Int = 0) =
        queue(entity, PlayerDiscardedBlock(type, f1, f2, f3))

    object Demo {
        private const val PREFIX = "opennxt.experiment.player.demo."

        private val fired = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Entity, Boolean>()
        )

        val armed: Boolean
            get() = KEYS.any { System.getProperty(PREFIX + it) != null }

        val KEYS = listOf(
            "say", "anim", "hit", "facedir", "facenpc", "faceplayer", "force", "string18"
        )

        fun property(key: String): String = PREFIX + key

        @Synchronized
        fun apply(entity: Entity) {
            if (!extendedEnabled || !armed) return
            if (!fired.add(entity)) return

            System.getProperty(PREFIX + "say")?.let { say(entity, it) }
            System.getProperty(PREFIX + "anim")?.let {
                val parts = it.split(',')
                animate(entity, parts[0].trim().toInt(), parts.getOrNull(1)?.trim()?.toInt() ?: 0)
            }
            System.getProperty(PREFIX + "hit")?.let {
                val parts = it.split(',')
                require(parts.size >= 2) { "player.demo.hit wants <type>,<amount>[,<delay>]" }
                hit(
                    entity, PlayerHitsBlock.Hit(
                        parts[0].trim().toInt(), parts[1].trim().toInt(),
                        parts.getOrNull(2)?.trim()?.toInt() ?: 0
                    )
                )
            }
            System.getProperty(PREFIX + "facedir")?.let { faceDirection(entity, it.trim().toInt()) }
            System.getProperty(PREFIX + "facenpc")?.let { faceNpc(entity, it.trim().toInt()) }
            System.getProperty(PREFIX + "faceplayer")?.let { facePlayer(entity, it.trim().toInt()) }
            System.getProperty(PREFIX + "force")?.let {
                val p = it.split(',').map { v -> v.trim().toInt() }
                require(p.size == 9) {
                    "player.demo.force wants nine numbers: a1,a2,a3,b1,b2,b3,c1,c2,angle"
                }
                forceMovement(
                    entity,
                    PlayerForceMovementBlock(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8])
                )
            }
            System.getProperty(PREFIX + "string18")?.let {
                val comma = it.lastIndexOf(',')
                val flag = if (comma > 0) it.substring(comma + 1).trim().toIntOrNull() else null
                if (flag != null) stringFlag(entity, it.substring(0, comma), flag)
                else stringFlag(entity, it)
            }
        }

        @Synchronized
        fun reset() = fired.clear()
    }
}
