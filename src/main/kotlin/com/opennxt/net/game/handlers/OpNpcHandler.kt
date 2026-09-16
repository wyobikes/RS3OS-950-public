package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.content.impl.BanksWiring
import com.opennxt.content.impl.Dialogue
import com.opennxt.content.impl.DialogueWiring
import com.opennxt.content.impl.SkillingWiring
import com.opennxt.model.combat.EngageResult
import com.opennxt.model.combat.PlayerCombat
import com.opennxt.model.entity.updating.NpcInfoEncoder
import com.opennxt.model.world.WorldNpc
import com.opennxt.model.world.WorldNpcs
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.OpNpc
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.resources.sqlite.NpcDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteNpcCodec
import mu.KotlinLogging

object OpNpcHandler : GamePacketHandler<WorldPlayer, OpNpc> {
    private val logger = KotlinLogging.logger { }

    internal fun npcAtIndex(index: Int): WorldNpc? {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return null
        return world.npcs.byInfoIndex(index)?.takeIf { it.alive }
    }

    internal fun corpseAtIndex(index: Int): WorldNpc? {
        val world = runCatching { OpenNXT.world }.getOrNull() ?: return null
        return world.npcs.byInfoIndex(index)
            ?.takeIf { !it.alive && com.opennxt.model.combat.NpcDeathTransmission.transmissible(it) }
    }

    fun onInteractionSide(nx: Int, nz: Int, size: Int, px: Int, pz: Int): Boolean {
        val x1 = nx + size - 1
        val z1 = nz + size - 1
        val inX = px in nx..x1
        val inZ = pz in nz..z1
        return (inX && (pz == nz - 1 || pz == z1 + 1)) || (inZ && (px == nx - 1 || px == x1 + 1))
    }

    fun interactionTile(nx: Int, nz: Int, size: Int, plane: Int, fromX: Int, fromZ: Int): IntArray? {
        val s = size.coerceAtLeast(1)
        val x1 = nx + s - 1
        val z1 = nz + s - 1
        val sides = ArrayList<IntArray>()
        for (x in nx..x1) { sides += intArrayOf(x, nz - 1); sides += intArrayOf(x, z1 + 1) }
        for (z in nz..z1) { sides += intArrayOf(nx - 1, z); sides += intArrayOf(x1 + 1, z) }
        val corners = listOf(intArrayOf(nx - 1, nz - 1), intArrayOf(x1 + 1, nz - 1), intArrayOf(nx - 1, z1 + 1), intArrayOf(x1 + 1, z1 + 1))
        val order = compareBy<IntArray>({ maxOf(Math.abs(it[0] - fromX), Math.abs(it[1] - fromZ)) }, { it[1] }, { it[0] })
        for (ring in listOf(sides, corners)) {
            ring.filter { com.opennxt.model.map.CollisionMap.walkable(it[0], it[1], plane) }
                .minWithOrNull(order)?.let { return it }
        }
        return null
    }

    var farClicksRefused: Int = 0
        private set

    private fun optionName(def: NpcDefinition?, option: Int): String? =
        def?.actions?.getOrNull(option - 1)

    const val DISPATCH_SWITCH = "opennxt.experiment.npc.dispatch"

    val dispatchEnabled: Boolean get() = System.getProperty(DISPATCH_SWITCH) == "true"

    override fun handle(context: WorldPlayer, packet: OpNpc) {
        if (com.opennxt.content.ActionLock.refuse(context.contentPlayer, "OPNPC${packet.option} slot ${packet.index}")) return
        val npc = npcAtIndex(packet.index)

        val isDemo = npc == null && packet.index == NpcInfoEncoder.DEMO_INDEX && NpcInfoEncoder.demoEnabled
        val gameId = npc?.gameId ?: if (isDemo) NpcInfoEncoder.demoNpcId else -1

        if (gameId < 0) {
            corpseAtIndex(packet.index)?.let { corpse ->
                logger.info {
                    "OPNPC${packet.option} ${context.name} clicked ${corpse.name ?: "npc"} (${corpse.gameId}, slot " +
                        "${packet.index}), which is dead; respawns in ${corpse.respawnTicksRemaining} tick(s)"
                }
                return
            }
            logger.warn {
                "OPNPC${packet.option} ${context.name} clicked npc index ${packet.index}, which has no live npc; ignored"
            }
            return
        }

        val def =
            if (RsDatabase.available) ContentRegistry.effectiveDefinition(gameId, context.contentPlayerAt()) else null
        val name = def?.name ?: npc?.name ?: "unknown npc"
        val action = optionName(def, packet.option)

        val x = npc?.location?.x ?: NpcInfoEncoder.DEMO_X
        val y = npc?.location?.y ?: NpcInfoEncoder.DEMO_Y
        val plane = npc?.location?.plane ?: NpcInfoEncoder.DEMO_PLANE

        logger.info {
            "OPNPC${packet.option} ${context.name} clicked $name (npc $gameId, slot ${packet.index}) at " +
                "($x,$y,plane $plane) option ${packet.option}" +
                (if (action != null) " = '$action'" else " = <none>") +
                (if (packet.ctrlHeld) " ctrl-held" else "") +
                (if (isDemo) " [demo spawn]" else "") +
                (if (RsDatabase.available) "" else " [no rs3.sqlite]")
        }

        run {
            val me = context.entity.location
            val reach = NpcInfoEncoder.reachFor(context)
            val far = maxOf(Math.abs(x - me.x), Math.abs(y - me.y))
            if (plane != me.plane || far > reach + 1) {
                farClicksRefused++
                logger.warn {
                    "OPNPC${packet.option} ${context.name} clicked $name (npc $gameId, slot ${packet.index}) at " +
                        "($x,$y,plane $plane) from (${me.x},${me.y},plane ${me.plane}): $far tiles, beyond view range $reach; ignored"
                }
                return
            }
        }

        var clickWalk: IntArray? = null
        run {
            val me = context.entity.location
            val attackClick = npc != null && PlayerCombat.shouldEngage(action) && PlayerCombat.enabled
            if (attackClick) {
                val range = PlayerCombat.playerAttackRange(context)
                val d = PlayerCombat.footprintDistance(npc!!, me.x, me.y)
                if (d <= range) return@run
                val tile = PlayerCombat.nearestAttackTile(npc, me.x, me.y, range)
                if (tile != null) {
                    clickWalk = tile
                    val far = maxOf(Math.abs(tile[0] - me.x), Math.abs(tile[1] - me.y))
                    MoveGameClickHandler.walk(context, tile[0], tile[1], "OPNPC${packet.option}", search = (2 * far + 2).coerceIn(8, PlayerCombat.CHASE_SEARCH_CAP), endsFight = false)
                    return@run
                }
            }
            if (npc != null) {
                val size = (npc.combat?.size ?: 1).coerceAtLeast(1)
                if (onInteractionSide(x, y, size, me.x, me.y)) {
                    context.entity.movement.reset()
                    return@run
                }
                val tile = interactionTile(x, y, size, plane, me.x, me.y)
                if (tile != null) {
                    val farT = maxOf(Math.abs(tile[0] - me.x), Math.abs(tile[1] - me.y))
                    MoveGameClickHandler.walk(context, tile[0], tile[1], "OPNPC${packet.option}", search = (2 * farT + 2).coerceIn(8, PlayerCombat.CHASE_SEARCH_CAP))
                    return@run
                }
            }
            val far = maxOf(Math.abs(x - me.x), Math.abs(y - me.y))
            MoveGameClickHandler.walk(context, x, y, "OPNPC${packet.option}", search = (2 * far + 2).coerceIn(8, PlayerCombat.CHASE_SEARCH_CAP))
        }

        if (action == Dialogue.TALK_TO) talkTo(context, packet, gameId, name, action)

        else if (action != null && !PlayerCombat.shouldEngage(action))
            dispatchOther(context, packet, gameId, name, action, x, y, plane)

        if (!PlayerCombat.shouldEngage(action)) return

        if (npc == null) {
            logger.warn {
                "OPNPC${packet.option} ${context.name} chose '$action' on npc $gameId (slot ${packet.index})" +
                    (if (isDemo)
                        ", which is a placeholder npc and cannot be fought"
                    else ", but no npc is in that slot")
            }
            return
        }

        val engageResult = PlayerCombat.engage(context, npc)
        clickWalk?.let { PlayerCombat.recordClickWalk(context, it[0], it[1]) }
        when (val result = engageResult) {
            is EngageResult.Disabled -> logger.info {
                "OPNPC${packet.option} ${context.name} chose '$action' on ${npc.name ?: "npc$gameId"} " +
                    "($gameId), but combat is disabled (-Dopennxt.experiment.combat=true to enable)"
            }

            is EngageResult.Refused -> logger.warn {
                "OPNPC${packet.option} ${context.name} cannot fight ${npc.name ?: "npc$gameId"} " +
                    "($gameId): ${result.reason}"
            }

            is EngageResult.Engaged -> logger.info {
                "OPNPC${packet.option} ${context.name} engaged ${npc.name ?: "npc$gameId"} ($gameId, slot " +
                    "${npc.infoIndex}) at ($x,$y,plane $plane), lp ${npc.currentLifepoints}/" +
                    "${npc.lifepoints?.value}, " +
                    (if (PlayerCombat.realDamageEnabled) "rolled damage"
                     else "${PlayerCombat.damage()} damage") +
                    " every ${PlayerCombat.interval()} tick(s)"
            }
        }
    }

    internal fun dispatchOther(
        context: WorldPlayer,
        packet: OpNpc,
        gameId: Int,
        name: String,
        action: String,
        x: Int,
        y: Int,
        plane: Int
    ): DispatchResult? {
        if (!dispatchEnabled) {
            logger.info {
                "OPNPC${packet.option} '$action' on npc $gameId ('$name') not dispatched; " +
                    "-D$DISPATCH_SWITCH=true to enable"
            }
            return null
        }

        val content = context.contentPlayerAt()
        DialogueWiring.bind(content, context)
        BanksWiring.bind(content, context)
        SkillingWiring.bind(content, context)

        val dispatched = try {
            ContentRegistry.dispatchNpc(content, gameId, action, packet.index, x, y, plane)
        } catch (t: Throwable) {
            logger.error(t) {
                "OPNPC${packet.option} ${context.name}: handler for '$action' on npc $gameId ('$name') failed; click dropped"
            }
            return null
        }

        when (dispatched) {
            is DispatchResult.Handled -> logger.info {
                "OPNPC${packet.option} ${context.name}: content handled '$action' on npc $gameId " +
                    "('$name') at ($x,$y,plane $plane) -> ${dispatched.value}"
            }
            is DispatchResult.NoHandler -> logger.info {
                "OPNPC${packet.option}: no handler for '$action' on npc $gameId ('$name')"
            }
            else -> logger.warn {
                "OPNPC${packet.option}: '$action' on npc $gameId was rejected by the registry ($dispatched)"
            }
        }
        return dispatched
    }

    private fun talkTo(context: WorldPlayer, packet: OpNpc, gameId: Int, name: String, action: String) {
        val content = context.contentPlayerAt()
        DialogueWiring.bind(content, context)

        when (val dispatched = ContentRegistry.dispatchNpc(content, gameId, action, packet.index)) {
            is DispatchResult.Handled -> logger.info {
                "OPNPC${packet.option} ${context.name}: content handled '$action' on npc $gameId " +
                    "('$name') -> ${dispatched.value}"
            }
            is DispatchResult.NoHandler -> logger.info {
                "OPNPC${packet.option}: no handler for '$action' on npc $gameId ('$name'), " +
                    "dialogue ${if (Dialogue.enabled) "enabled" else "disabled"}"
            }
            else -> logger.warn {
                "OPNPC${packet.option}: '$action' on npc $gameId was rejected by the registry ($dispatched)"
            }
        }
    }
}
