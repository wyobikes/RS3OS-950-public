package com.opennxt.model.entity.updating

import com.opennxt.OpenNXT
import com.opennxt.model.combat.NpcDeathTransmission
import com.opennxt.model.entity.movement.CompassPoint
import com.opennxt.model.entity.rendering.npc.NpcUpdates
import com.opennxt.model.entity.rendering.npc.blocks.NpcForceMovementBlock
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.game.pipeline.OpcodeWithBuffer
import com.opennxt.net.proxy.UnidentifiedPacket
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

object NpcInfoEncoder {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.npcs") != "false"

    val demoEnabled: Boolean = System.getProperty("opennxt.experiment.npcs.demo") != "false"

    val demoNpcId: Int = System.getProperty("opennxt.experiment.npcs.demoId")?.toIntOrNull() ?: 41

    const val DEMO_X = com.opennxt.model.account.PlayerSave.SPAWN_X + 2
    const val DEMO_Y = com.opennxt.model.account.PlayerSave.SPAWN_Y
    const val DEMO_PLANE = 0

    const val DEMO_INDEX = 1

    const val TERMINATOR = 0xFFFF

    const val MAX_LOCAL = 255

    const val MAX_ADDS_PER_TICK = 32

    fun maxBitSectionBytes(npcBits: Int): Int =
        (8 + MAX_LOCAL * 11 + MAX_ADDS_PER_TICK * (36 + 2 * npcBits) + 16 + 7) / 8

    const val ADD_TRAILING_BIT = 1

    const val ADD_SNAP_950_PROPERTY = "opennxt.npc.add.snap950"

    fun addTrailingBit950(reAdd: Boolean): Int =
        if (reAdd || System.getProperty(ADD_SNAP_950_PROPERTY) == "true") 1 else 0

    const val ADD_FACING = 0

    fun build950(): Boolean = com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()

    @Volatile
    var dropped950: Long = 0
        private set

    @Volatile
    private var dropped950Warned = false

    private fun refuse950(index: Int, updates: NpcUpdates) {
        val refused = updates.refusedOn950()
        if (refused.isEmpty()) return
        dropped950 += refused.size
        if (!updates.hasSendable(true)) updates.offered = true
        if (!dropped950Warned) {
            dropped950Warned = true
            logger.warn {
                "Build 950: dropped unsupported NPC_INFO block(s) ${refused.map { it.type.name }} for npc $index; supported: " +
                    "${com.opennxt.model.entity.rendering.npc.NpcUpdateBlockType.NPC_MASK_950.keys.map { it.name }} (logged once)"
            }
        }
    }

    enum class Gait(val opcode: Int, val subBit: Int?, val steps: Int, val speed: Int) {
        WALK(opcode = 1, subBit = null, steps = 1, speed = 1),

        RUN(opcode = 2, subBit = 1, steps = 2, speed = 2),

        CRAWL(opcode = 2, subBit = 0, steps = 1, speed = 0)
    }

    data class NpcView(
        val index: Int,
        val npcId: Int,
        val x: Int,
        val y: Int,
        val plane: Int,
        val updates: NpcUpdates? = null,
        val corpsePose: Int? = null,
        val steps: List<Int> = emptyList(),
        val gait: Gait = Gait.WALK
    ) {
        fun hasExtendedInfo(build950: Boolean = false): Boolean =
            updates != null && updates.hasSendable(build950)
    }

    class State {
        val local = ArrayList<Int>()

        val believed = HashMap<Int, Triple<Int, Int, Int>>()

        var sceneBaseX: Int? = null
        var sceneBaseY: Int? = null

        class Snapshot(val local: List<Int>, val believed: Map<Int, Triple<Int, Int, Int>>,
                       val sceneBaseX: Int?, val sceneBaseY: Int?)

        fun snapshot(): Snapshot = Snapshot(ArrayList(local), HashMap(believed), sceneBaseX, sceneBaseY)

        fun restore(s: Snapshot) {
            local.clear(); local.addAll(s.local)
            believed.clear(); believed.putAll(s.believed)
            sceneBaseX = s.sceneBaseX
            sceneBaseY = s.sceneBaseY
        }
    }

    private val states: MutableMap<WorldPlayer, State> =
        Collections.synchronizedMap(WeakHashMap<WorldPlayer, State>())

    fun stateOf(player: WorldPlayer): State = states.getOrPut(player) { State() }

    fun forget(player: WorldPlayer) {
        states.remove(player)
    }

    fun indexHeldByAnyMirror(index: Int): Boolean = heldIndexes().contains(index)

    fun heldIndexes(): it.unimi.dsi.fastutil.ints.IntSet {
        val out = it.unimi.dsi.fastutil.ints.IntOpenHashSet()
        synchronized(states) {
            for (s in states.values) {
                for (i in s.local) out.add(i)
                for (i in s.believed.keys) out.add(i)
            }
        }
        return out
    }

    @Volatile
    var mirrorRollbacks: Int = 0
        private set

    fun resetMirrorRollbacks() { mirrorRollbacks = 0 }

    fun mirrorCount(): Int = synchronized(states) { states.size }

    fun allNpcs(): List<NpcView> = collect(null, 0, 0, 0)

    internal fun collect(reach: Int?, cx: Int, cy: Int, plane: Int): List<NpcView> {
        val out = ArrayList<NpcView>()
        fun inRange(x: Int, y: Int, p: Int): Boolean =
            reach == null || (p == plane && abs(x - cx) <= reach && abs(y - cy) <= reach)

        val world = runCatching { OpenNXT.world }.getOrNull()

        val backedByRealNpc = world?.npcs?.combatDemoNpc() != null
        if (demoEnabled && !backedByRealNpc && inRange(DEMO_X, DEMO_Y, DEMO_PLANE)) {
            out.add(NpcView(DEMO_INDEX, demoNpcId, DEMO_X, DEMO_Y, DEMO_PLANE, demoUpdates()))
        }
        if (world == null) return out
        for (npc in world.npcs.all()) {
            if (!npc.alive && !NpcDeathTransmission.transmissible(npc)) continue
            if (npc.infoIndex < 0) continue
            val loc = npc.location
            if (!inRange(loc.x, loc.y, loc.plane)) continue

            val move = npc.movement
            val steps = when {
                move.nextWalkDirection == null -> emptyList()
                move.nextRunDirection == null -> listOf(move.nextWalkDirection!!.id)
                else -> listOf(move.nextWalkDirection!!.id, move.nextRunDirection!!.id)
            }
            val gait = if (steps.size == 2) Gait.RUN
                else com.opennxt.model.world.NpcMovementDef.singleStepGait(npc.gameId)
            out.add(
                NpcView(
                    npc.infoIndex, npc.gameId, loc.x, loc.y, loc.plane,
                    updates = npc.pendingUpdates,
                    corpsePose = if (npc.alive) null else npc.deathAnimation?.sequence,
                    steps = steps, gait = gait
                )
            )
        }
        return out
    }

    private fun demoUpdates(): NpcUpdates? {
        val anim = System.getProperty("opennxt.experiment.npcs.demoAnim")?.toIntOrNull()
        val text = System.getProperty("opennxt.experiment.npcs.demoSay")
        val face = System.getProperty("opennxt.experiment.npcs.demoFace")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 2 }
        val faceEntity = System.getProperty("opennxt.experiment.npcs.demoFaceEntity")
        val force = System.getProperty("opennxt.experiment.npcs.demoForce")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 9 }
        if (anim == null && text == null && face == null && faceEntity == null && force == null) {
            return null
        }

        val updates = NpcUpdates()
        if (anim != null) updates.animate(anim)
        if (text != null) updates.say(text)
        if (face != null) updates.faceTile(face[0], face[1])
        if (faceEntity != null) {
            val parts = faceEntity.split(',').map { it.trim() }
            when (parts[0].lowercase()) {
                "npc" -> updates.faceNpc(parts[1].toInt())
                "player" -> updates.facePlayer(parts[1].toInt())
                "clear" -> updates.faceNothing()
                else -> throw IllegalArgumentException(
                    "npcs.demoFaceEntity wants npc,<index> | player,<index> | clear"
                )
            }
        }
        if (force != null) {
            updates.forceMovement(
                NpcForceMovementBlock(
                    force[0], force[1], force[2], force[3], force[4], force[5],
                    force[6], force[7], force[8]
                )
            )
        }
        return updates
    }

    private fun half(npcBits: Int): Int = 1 shl (npcBits - 1)

    private fun withCorpsePose(npc: NpcView): NpcUpdates? {
        val shared = npc.updates?.takeIf { !it.isEmpty() }
        val pose = npc.corpsePose ?: return shared
        if (shared?.animation != null) return shared
        corpsePosesAdded++
        val local = NpcUpdates()
        if (shared != null) {
            shared.hits?.let { local.hit(it) }
            shared.faceCoordinate?.let { local.faceTile(it) }
            shared.say?.let { local.say(it) }
            shared.animationGroup?.let { local.animationGroup(it) }
            shared.faceEntity?.let { local.faceEntity(it) }
            shared.forceMovement?.let { local.forceMovement(it) }
            shared.string17?.let { local.string17(it) }
            shared.short22?.let { local.short22(it) }
        }
        local.animate(pose)
        return local
    }

    var corpsePosesAdded: Int = 0
        private set

    fun npcBitsFor(player: WorldPlayer): Int = player.viewport.sceneRadius

    fun visible(player: WorldPlayer, npcBits: Int): List<NpcView> {
        val loc = player.entity.location
        val reach = reachFor(player, npcBits)
        return collect(reach, loc.x, loc.y, loc.plane)
    }

    fun reachFor(player: WorldPlayer, npcBits: Int = npcBitsFor(player)): Int =
        minOf(player.viewport.playerViewingDistance, half(npcBits) - 1)

    private fun deltaOf(dir: Int): Pair<Int, Int>? =
        CompassPoint.values().firstOrNull { it.id == dir }?.let { it.dx to it.dy }

    private fun movementOf(believed: Triple<Int, Int, Int>, npc: NpcView): List<Int>? {
        val (bx, by, bp) = believed
        if (bp != npc.plane) return null
        if (npc.steps.size > 2) return null
        if (npc.steps.size == 2 && npc.gait != Gait.RUN) return null
        if (npc.steps.size == 1 && npc.gait == Gait.RUN) return null

        var x = bx
        var y = by
        for (dir in npc.steps) {
            val (dx, dy) = deltaOf(dir) ?: return null
            x += dx
            y += dy
        }
        if (x != npc.x || y != npc.y) return null
        return npc.steps
    }

    fun createBufferFor(player: WorldPlayer): ByteBuf =
        createBufferFor(player, visible(player, npcBitsFor(player)))

    fun createBufferFor(player: WorldPlayer, views: List<NpcView>, build950: Boolean = build950()): ByteBuf {
        val npcBits = npcBitsFor(player)
        require(npcBits in 1..16) {
            "npcBits must be 1..16, got $npcBits"
        }

        val state = stateOf(player)

        val base = player.viewport.baseTile
        if (state.sceneBaseX != base.x || state.sceneBaseY != base.y) {
            state.local.clear()
            state.believed.clear()
            state.sceneBaseX = base.x
            state.sceneBaseY = base.y
        }

        val out = Unpooled.buffer()
        val buf = GamePacketBuilder(out)

        val inView = views.associateBy { it.index }
        val loc = player.entity.location
        val mask = (1 shl npcBits) - 1

        buf.switchToBitAccess()

        val extended = ArrayList<Pair<Int, NpcUpdates>>()

        val previous = ArrayList(state.local)
        check(previous.size <= MAX_LOCAL) {
            "mirrored local list is ${previous.size}, but the local count is " +
                "getBits(8) and cannot describe more than $MAX_LOCAL"
        }
        buf.putBits(8, previous.size)
        val kept = ArrayList<Int>(previous.size)
        val removedHere = HashSet<Int>()
        for (index in previous) {
            val npc = inView[index]
            val believed = state.believed[index]
            val steps = if (npc == null || believed == null) null else movementOf(believed, npc)

            if (npc == null || believed == null || steps == null) {
                buf.putBits(1, 1)
                buf.putBits(2, 3)
                state.believed.remove(index)
                removedHere.add(index)
                continue
            }

            val ext = npc.hasExtendedInfo(build950)
            if (build950 && npc.updates != null) refuse950(index, npc.updates)
            if (steps.isEmpty()) {
                if (ext) {
                    buf.putBits(1, 1)
                    buf.putBits(2, 0)
                    extended.add(index to npc.updates!!)
                } else {
                    buf.putBits(1, 0)
                }
            } else {
                val gait = if (steps.size == 2) Gait.RUN else npc.gait
                buf.putBits(1, 1)
                buf.putBits(2, gait.opcode)
                gait.subBit?.let { buf.putBits(1, it) }
                for (dir in steps) buf.putBits(3, dir)
                buf.putBits(1, if (ext) 1 else 0)
                if (ext) extended.add(index to npc.updates!!)
            }
            state.believed[index] = Triple(npc.x, npc.y, npc.plane)
            kept.add(index)
        }

        var added = 0
        for (npc in inView.values) {
            if (kept.contains(npc.index)) continue
            if (kept.size >= MAX_LOCAL) break
            if (added >= MAX_ADDS_PER_TICK) break
            val addUpdates: NpcUpdates? = withCorpsePose(npc)
            val ext = addUpdates != null && addUpdates.hasSendable(build950)
            if (build950 && addUpdates != null) refuse950(npc.index, addUpdates)
            if (build950) {
                buf.putBits(16, npc.index)
                buf.putBits(npcBits, (npc.y - loc.y) and mask)
                buf.putBits(1, addTrailingBit950(npc.index in removedHere))
                buf.putBits(3, ADD_FACING)
                buf.putBits(16, npc.npcId)
                buf.putBits(2, npc.plane)
                buf.putBits(1, if (ext) 1 else 0)
                buf.putBits(npcBits, (npc.x - loc.x) and mask)
            } else {
                buf.putBits(16, npc.index)
                buf.putBits(npcBits, (npc.x - loc.x) and mask)
                buf.putBits(16, npc.npcId)
                buf.putBits(1, if (ext) 1 else 0)
                buf.putBits(2, npc.plane)
                buf.putBits(3, ADD_FACING)
                buf.putBits(npcBits, (npc.y - loc.y) and mask)
                buf.putBits(1, ADD_TRAILING_BIT)
            }
            if (ext) extended.add(npc.index to addUpdates!!)
            state.believed[npc.index] = Triple(npc.x, npc.y, npc.plane)
            kept.add(npc.index)
            added++
        }
        buf.putBits(16, TERMINATOR)

        buf.switchToByteAccess()

        val bitSectionBytes = out.readableBytes()
        check(bitSectionBytes <= maxBitSectionBytes(npcBits)) {
            "bit section is $bitSectionBytes bytes, over the ${maxBitSectionBytes(npcBits)} the " +
                "field widths allow at npcBits=$npcBits with MAX_LOCAL=$MAX_LOCAL and " +
                "MAX_ADDS_PER_TICK=$MAX_ADDS_PER_TICK"
        }

        val traced = ArrayList<ExtendedInfoTrace.Entry>(extended.size)
        for ((index, updates) in extended) {
            val names = updates.blocks().map { it.type.name }
            val before = out.readableBytes()
            updates.offered = true
            updates.encode(buf, build950)
            traced.add(
                ExtendedInfoTrace.Entry(
                    ExtendedInfoTrace.NPC, index, names, out.readableBytes() - before
                )
            )
        }
        lastReport = ExtendedInfoTrace.Report(
            ExtendedInfoTrace.NPC, player.name, traced, out.readableBytes() - bitSectionBytes
        )
        ExtendedInfoTrace.record(lastReport!!)

        state.local.clear()
        state.local.addAll(kept)
        state.believed.keys.retainAll(kept.toSet())
        encodeFault?.invoke()
        return out
    }

    @Volatile
    var encodeFault: (() -> Unit)? = null

    fun writeTo(player: WorldPlayer) {
        if (!enabled) return

        val opcode = OpenNXT.protocol.serverProtNames.values["NPC_INFO"]
        if (opcode == null) {
            if (!unmappedWarned) {
                unmappedWarned = true
                logger.warn {
                    "Build ${OpenNXT.protocol.effectiveBuild} has no NPC_INFO opcode in " +
                        "data/prot/<build>/serverProtNames.toml; npcs disabled (logged once)"
                }
            }
            return
        }

        val state = stateOf(player)
        val snapshot = state.snapshot()

        val buf = try {
            createBufferFor(player)
        } catch (t: Throwable) {
            state.restore(snapshot)
            mirrorRollbacks++
            if (!encodeFailureWarned) {
                encodeFailureWarned = true
                logger.error(t) {
                    "NPC_INFO encode failed for ${player.name}; local npc list rolled back (logged once)"
                }
            }
            return
        }

        if (sends < 3) {
            sends++
            logger.info {
                "NPC_INFO send #$sends: ${buf.readableBytes()} byte(s), local=${stateOf(player).local.size} " +
                    "npcBits=${npcBitsFor(player)}"
            }
        }

        lastReport?.let { report ->
            if (!report.isEmpty()) {
                logger.info {
                    "NPC_INFO ext-info -> ${player.name}: $report " +
                        "[packet ${buf.readableBytes()}B; cumulative ${ExtendedInfoTrace.counts()}]"
                }
            }
        }

        val delivered = try {
            player.client.write(UnidentifiedPacket(OpcodeWithBuffer(opcode, buf)))
        } catch (t: Throwable) {
            state.restore(snapshot)
            mirrorRollbacks++
            logger.error(t) {
                "NPC_INFO write failed for ${player.name}; local npc list rolled back"
            }
            throw t
        }
        if (!delivered) {
            state.restore(snapshot)
            mirrorRollbacks++
            logger.error {
                "NPC_INFO for ${player.name} was not delivered; local npc list rolled back (rollbacks=$mirrorRollbacks)"
            }
        }
    }

    @Volatile
    var lastReport: ExtendedInfoTrace.Report? = null
        private set

    @Volatile
    private var unmappedWarned = false

    @Volatile
    private var encodeFailureWarned = false

    @Volatile
    private var sends = 0
}
