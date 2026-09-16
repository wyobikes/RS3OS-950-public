package com.opennxt.net.game.handlers

import com.opennxt.OpenNXT
import com.opennxt.content.ContentRegistry
import com.opennxt.content.DispatchResult
import com.opennxt.content.impl.Doors
import com.opennxt.content.impl.ResourceNodes
import com.opennxt.content.impl.SkillingWiring
import com.opennxt.model.map.CollisionMap
import com.opennxt.model.map.LocClipping
import com.opennxt.model.map.LocInteraction
import com.opennxt.model.world.GateSwing
import com.opennxt.model.world.LocChanges
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.OpLoc
import com.opennxt.net.game.pipeline.GamePacketHandler
import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.RsDatabase
import com.opennxt.resources.sqlite.SqliteLocCodec
import mu.KotlinLogging

object OpLocHandler : GamePacketHandler<WorldPlayer, OpLoc> {
    private val logger = KotlinLogging.logger { }

    data class Placement(val locId: Int, val type: Int, val rot: Int)

    internal fun placementsAt(x: Int, y: Int, plane: Int): List<Placement> {
        if (!RsDatabase.available) return emptyList()
        val squareId = ((y / 64) shl 7) or (x / 64)
        val sql = "SELECT loc_id, type, rot FROM map_loc WHERE square_id = $squareId " +
            "AND x = ${x % 64} AND y = ${y % 64} AND plane = $plane"
        return RsDatabase.queryAll(sql) { Placement(it.getInt("loc_id"), it.getInt("type"), it.getInt("rot")) }
    }

    private fun optionName(def: LocDefinition?, option: Int): String? =
        def?.actions?.getOrNull(option - 1)

    private fun optionCanBeConfigSlot(option: Int): Boolean = option in 1..5

    override fun handle(context: WorldPlayer, packet: OpLoc) {
        if (com.opennxt.content.ActionLock.refuse(context.contentPlayer, "OPLOC${packet.option} loc ${packet.id} at (${packet.x},${packet.y})")) return
        val playerPlane = context.entity.location.plane
        val plane = if (RsDatabase.available)
            (LocInteraction.placementPlane(packet.id, packet.x, packet.y, playerPlane) ?: playerPlane)
        else playerPlane
        val def = if (RsDatabase.available) SqliteLocCodec.load(packet.id) else null
        val name = def?.name ?: "unknown loc"
        val action = optionName(def, packet.option)

        val cachePlaced = placementsAt(packet.x, packet.y, plane)
        val cacheAgrees = cachePlaced.any { it.locId == packet.id }
        val gateLeaf = if (cacheAgrees) null else (GateSwing.openLeafAt(plane, packet.x, packet.y, packet.id)
            ?: GateSwing.openDoorLeafAt(plane, packet.x, packet.y, packet.id))
        val placed = if (gateLeaf != null) cachePlaced + Placement(gateLeaf.id, gateLeaf.shape, gateLeaf.rot) else cachePlaced
        val isVerified = cacheAgrees || gateLeaf != null

        logger.info {
            "OPLOC${packet.option} ${context.name} clicked $name (loc ${packet.id}) at " +
                "(${packet.x},${packet.y},plane $plane) option ${packet.option}" +
                (if (plane != playerPlane) " [player on plane $playerPlane]" else "") +
                (if (action != null) " = '$action'"
                else if (!optionCanBeConfigSlot(packet.option))
                    " = <client menu option>"
                else " = <no action>") +
                (if (packet.ctrlHeld) " ctrl-held" else "") +
                (if (RsDatabase.available) {
                    if (gateLeaf != null) " [open gate leaf]"
                    else if (isVerified) " [verified]"
                    else " [map_loc has ${placed.size} loc(s) there: " +
                        "${placed.joinToString(", ") { it.locId.toString() }}]"
                } else " [unverified, no rs3.sqlite]")
        }

        if (RsDatabase.available && !isVerified) {
            val covering = LocInteraction.placementsCovering(packet.x, packet.y, plane)
            val mine = covering.firstOrNull { it.locId == packet.id }
            if (mine != null) {
                logger.info {
                    "OPLOC${packet.option}: (${packet.x},${packet.y}) is inside loc ${packet.id}'s footprint, " +
                        "origin (${mine.originX},${mine.originZ}), ${mine.dx}x${mine.dz}, shape ${mine.type} rot ${mine.rot}"
                }
            } else {
                logger.warn {
                    "OPLOC${packet.option}: loc ${packet.id} not found at (${packet.x},${packet.y},plane $plane) " +
                        "within ${LocInteraction.radius} tiles (${covering.size} other loc(s) there); ignored"
                }
                return
            }
        }

        val walkResult = MoveGameClickHandler.walkToLoc(context, packet.x, packet.y, if (gateLeaf != null) null else packet.id, "OPLOC${packet.option}")

        val executeAction = {
            closeIfGate(context, packet, def)
            openIfDoor(context, packet, def, placed)
            gatherIfResource(context, packet, def)

            com.opennxt.content.impl.Lodestones.handleLocClick(
                context, packet.id, packet.option, packet.x, packet.y, context.entity.location.plane
            )
            val portal = com.opennxt.content.impl.UnderworldPortals.handleLocClick(
                context, packet.id, packet.option, packet.x, packet.y, plane
            )
            val routeAction = def?.actions?.getOrNull(packet.option - 1)
            if (routeAction != null && !portal) com.opennxt.content.impl.BanksWiring.handleLocClick(context, packet.id, routeAction, packet.x, packet.y, context.entity.location.plane)
        }

        when (walkResult) {
            is MoveGameClickHandler.LocWalk.AlreadyInRange -> executeAction()
            is MoveGameClickHandler.LocWalk.Walked,
            is MoveGameClickHandler.LocWalk.Approached -> context.entity.movement.onArrival = {
                if (adjacentToFootprint(context, packet, plane)) executeAction()
                else logger.info {
                    "OPLOC${packet.option}: walk to loc ${packet.id} at (${packet.x},${packet.y}) ended at " +
                        "(${context.entity.location.x},${context.entity.location.y}), not adjacent"
                }
            }
            is MoveGameClickHandler.LocWalk.Unreachable,
            is MoveGameClickHandler.LocWalk.NoPlacement -> {
                if (adjacentToFootprint(context, packet, plane)) {
                    executeAction()
                } else {
                    context.entity.movement.onArrival = null
                    logger.info {
                        "OPLOC${packet.option}: no route to loc ${packet.id} at (${packet.x},${packet.y}) - " +
                            "$walkResult, and ${context.name} is not adjacent"
                    }
                }
            }
        }
    }

    private fun adjacentToFootprint(context: WorldPlayer, packet: OpLoc, plane: Int): Boolean =
        LocInteraction.adjacentToFootprint(context.entity.location.x, context.entity.location.y, packet.id, packet.x, packet.y, plane)

    private fun gatherIfResource(context: WorldPlayer, packet: OpLoc, def: LocDefinition?) {
        if (def == null) return
        val action = optionName(def, packet.option) ?: return
        if (!ResourceNodes.isGatherAction(action)) return

        val plane = context.entity.location.plane
        val content = context.contentPlayerAt()
        SkillingWiring.bind(content, context)

        val dispatched = ContentRegistry.dispatchLoc(content, packet.id, action, packet.x, packet.y, plane)
        when (dispatched) {
            is DispatchResult.Handled -> logger.info {
                "OPLOC${packet.option} ${context.name}: content handled '$action' on loc ${packet.id} " +
                    "('${def.name}') at (${packet.x},${packet.y},plane $plane) -> ${dispatched.value}"
            }
            is DispatchResult.NoHandler -> logger.info {
                "OPLOC${packet.option}: no handler for '$action' on loc ${packet.id} ('${def.name}'), " +
                    "skilling ${if (com.opennxt.content.impl.Skilling.enabled) "enabled" else "disabled"}"
            }
            else -> logger.warn {
                "OPLOC${packet.option}: '$action' on loc ${packet.id} rejected by the registry ($dispatched)"
            }
        }
    }

    private fun openIfDoor(
        context: WorldPlayer,
        packet: OpLoc,
        def: LocDefinition?,
        placed: List<Placement>
    ) {
        if (!LocChanges.enabled) return
        if (def == null) return
        if (optionName(def, packet.option) != LocChanges.OPEN) return

        val plane = context.entity.location.plane
        val placement = placed.firstOrNull { it.locId == packet.id }
            ?: LocInteraction.placementOf(packet.id, packet.x, packet.y, plane)
                ?.let { Placement(it.locId, it.type, it.rot) }
        if (placement == null) {
            logger.warn {
                "OPLOC${packet.option}: cannot open loc ${packet.id} ('${def.name}') at " +
                    "(${packet.x},${packet.y},plane $plane), not in map_loc"
            }
            return
        }

        if (GateSwing.enabled && GateSwing.isSwungAt(plane, packet.x, packet.y)) {
            logger.info {
                "OPLOC${packet.option} ${context.name}: '${def.name}' ${packet.id} at (${packet.x},${packet.y},plane $plane) is " +
                    "already open; ignored"
            }
            return
        }

        openCollision("OPLOC${packet.option}", context.contentPlayerAt(), packet.id,
            packet.x, packet.y, plane, placement.type, placement.rot)

        val partner = LocChanges.doubleDoorPartner(
            plane, packet.x, packet.y, placement.type, packet.id, placement.rot
        )
        if (partner != null && GateSwing.enabled) {
            val (gx, gy, partnerId) = partner
            val pp = placementsAt(gx, gy, plane).firstOrNull { it.locId == partnerId }
            val mineOpen = LocChanges.openVariant(packet.id)?.openId
            val partnerOpen = LocChanges.openVariant(partnerId)?.openId
            if (pp != null && mineOpen != null && partnerOpen != null) {
                val gate = GateSwing.open(
                    context, plane,
                    GateSwing.Leaf(packet.x, packet.y, placement.type, placement.rot, packet.id),
                    GateSwing.Leaf(gx, gy, pp.type, pp.rot, partnerId),
                    mineOpen, partnerOpen
                )
                if (gate != null) {
                    openCollision("OPLOC${packet.option}-partner", context.contentPlayerAt(), partnerId, gx, gy, plane, pp.type, pp.rot)
                    logger.info {
                        "OPLOC${packet.option} ${context.name} opened '${def.name}' as a two-leaf gate: ${packet.id}+$partnerId -> " +
                            "${gate.hingeOpen.id}@(${gate.hingeOpen.x},${gate.hingeOpen.y}) r${gate.hingeOpen.rot} + " +
                            "${gate.partnerOpen.id}@(${gate.partnerOpen.x},${gate.partnerOpen.y}) r${gate.partnerOpen.rot} swing[GATE]"
                    }
                    return
                }
            }
        }
        if (partner != null) {
            val (px, py, partnerLocId) = partner
            val partnerPlacement = placementsAt(px, py, plane).firstOrNull { it.locId == partnerLocId }
            if (partnerPlacement != null) {
                openCollision("OPLOC${packet.option}-partner", context.contentPlayerAt(),
                    partnerLocId, px, py, plane, partnerPlacement.type, partnerPlacement.rot)
                logger.info {
                    "OPLOC${packet.option}: also opened partner leaf loc $partnerLocId at ($px,$py,plane $plane)"
                }
                val partnerVariant = LocChanges.openVariant(partnerLocId)
                if (partnerVariant != null) {
                    LocChanges.change(
                        plane = plane,
                        x = px,
                        y = py,
                        shape = partnerPlacement.type,
                        rotation = partnerPlacement.rot,
                        originalId = partnerLocId,
                        newId = partnerVariant.openId,
                        forcedSwingMode = if (com.opennxt.model.world.DoorSwing.mode == com.opennxt.model.world.DoorSwing.Mode.OFF) null else com.opennxt.model.world.DoorSwing.Mode.TURN_BACK
                    )
                }
            }
        }

        if (partner == null && GateSwing.enabled && placement.type in LocChanges.WALL_SHAPES) {
            val openId = LocChanges.openVariant(packet.id)?.openId
            if (openId != null) {
                val door = GateSwing.openDoor(context, plane, GateSwing.Leaf(packet.x, packet.y, placement.type, placement.rot, packet.id), openId)
                if (door != null) {
                    logger.info {
                        "OPLOC${packet.option} ${context.name} opened '${def.name}' as a door: ${packet.id} -> $openId placed at " +
                            "(${door.open.x},${door.open.y}) rot ${door.open.rot} instead of (${packet.x},${packet.y}) rot ${placement.rot} swing[DOOR]"
                    }
                    return
                }
            }
        }
        val variant = LocChanges.openVariant(packet.id)
        if (variant == null) {
            logger.warn {
                "OPLOC${packet.option}: no open variant found for '${def.name}' (loc ${packet.id}) at " +
                    "(${packet.x},${packet.y},plane $plane)"
            }
            return
        }
        val openId = variant.openId

        val change = LocChanges.change(
            plane = plane,
            x = packet.x,
            y = packet.y,
            shape = placement.type,
            rotation = placement.rot,
            originalId = packet.id,
            newId = openId,
            forcedSwingMode = if (partner != null && com.opennxt.model.world.DoorSwing.mode != com.opennxt.model.world.DoorSwing.Mode.OFF) com.opennxt.model.world.DoorSwing.Mode.TURN else null
        )
        if (change == null) {
            logger.warn {
                "OPLOC${packet.option}: could not send LOC_ADD_CHANGE for '${def.name}' -> $openId " +
                    "(disabled or unsupported on build ${OpenNXT.protocol.effectiveBuild})"
            }
            return
        }
        logger.info {
            "OPLOC${packet.option} ${context.name} opened '${def.name}': loc ${packet.id} -> $openId at " +
                "(${packet.x},${packet.y},plane $plane) shape ${placement.type} rotation ${placement.rot} " +
                "[${variant.rule} rule, ${variant.candidates} candidate(s)] " +
                change.swingToken +
                (if (change.swingMoved)
                    " leaf placed at (${change.swing.x},${change.swing.y}) rot ${change.swing.rotation} " +
                        "instead of (${packet.x},${packet.y}) rot ${placement.rot}"
                else " leaf stays at (${packet.x},${packet.y}) rot ${placement.rot}") +
                (if (variant.modelsIdentical) {
                    " (open variant uses the same models)"
                } else "")
        }
    }

    private fun closeIfGate(context: WorldPlayer, packet: OpLoc, def: LocDefinition?) {
        if (!GateSwing.enabled || def == null) return
        if (optionName(def, packet.option) != LocChanges.CLOSE) return
        val plane = context.entity.location.plane
        GateSwing.doorAt(plane, packet.x, packet.y)?.let { d ->
            val closedDoor = GateSwing.closeDoor(context, plane, packet.x, packet.y) ?: return
            val r = ContentRegistry.dispatchLoc(context.contentPlayerAt(), closedDoor.open.id, LocChanges.CLOSE, closedDoor.shut.x, closedDoor.shut.y, plane)
            logger.info { "OPLOC${packet.option} ${context.name} closed '${def.name}' (door ${d.shut.id} at (${d.shut.x},${d.shut.y},plane $plane)) -> $r" }
            return
        }
        val gate = GateSwing.gateAt(plane, packet.x, packet.y) ?: return
        val closed = GateSwing.close(context, plane, packet.x, packet.y) ?: return
        for ((leaf, open) in listOf(closed.hinge to closed.hingeOpen, closed.partner to closed.partnerOpen)) {
            val r = ContentRegistry.dispatchLoc(context.contentPlayerAt(), open.id, LocChanges.CLOSE, leaf.x, leaf.y, plane)
            logger.info { "OPLOC${packet.option}: content handled '${LocChanges.CLOSE}' on loc ${open.id} (shut ${leaf.id}) at (${leaf.x},${leaf.y}) -> $r" }
        }
        logger.info { "OPLOC${packet.option} ${context.name} closed '${def.name}' (gate ${gate.hinge.id}+${gate.partner.id} at (${gate.hinge.x},${gate.hinge.y},plane $plane))" }
    }

    internal fun openCollision(
        tag: String,
        player: com.opennxt.content.ContentPlayer,
        locId: Int,
        x: Int,
        y: Int,
        plane: Int,
        type: Int,
        rot: Int
    ): DispatchResult {
        val squares = LocClipping.applySceneAt(x, y, plane)
        if (squares > 0) {
            logger.info {
                "$tag: applied loc clipping around ($x,$y,plane $plane) - $squares square(s), " +
                    "${LocClipping.loadedSquares()} loaded, ${CollisionMap.walledTiles()} walled tile(s), " +
                    "${CollisionMap.occupiedTiles()} occupied tile(s)"
            }
        }

        Doors.placeIfAbsent(locId, x, y, plane, open = false, type = type, rot = rot)
        val edgeMask = Doors.at(x, y, plane)?.edgeMask ?: 0
        val maskBefore = CollisionMap.wallMask(x, y, plane)

        val dispatched = ContentRegistry.dispatchLoc(
            player, locId, LocChanges.OPEN, x, y, plane
        )
        val maskAfter = CollisionMap.wallMask(x, y, plane)
        when (dispatched) {
            is DispatchResult.Handled -> logger.info {
                "$tag: content handled '${LocChanges.OPEN}' on loc $locId -> '${dispatched.value}'. " +
                    "Wall edges on ($x,$y,plane $plane): 0x%02x -> 0x%02x (this door's edge 0x%02x)"
                        .format(maskBefore, maskAfter, edgeMask) +
                    if (maskBefore == maskAfter && edgeMask != 0) {
                        " (unchanged)"
                    } else ""
            }
            else -> logger.warn {
                "$tag: '${LocChanges.OPEN}' on loc $locId not handled ($dispatched); collision unchanged"
            }
        }
        return dispatched
    }
}
