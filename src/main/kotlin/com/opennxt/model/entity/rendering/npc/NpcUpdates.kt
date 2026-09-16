package com.opennxt.model.entity.rendering.npc

import com.opennxt.model.entity.rendering.npc.blocks.NpcAnimationBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcAnimationGroupBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcByte25Block
import com.opennxt.model.entity.rendering.npc.blocks.NpcByte34Block
import com.opennxt.model.entity.rendering.npc.blocks.NpcDiscardedBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcFaceCoordinateBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcFaceEntityBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcFlag28Block
import com.opennxt.model.entity.rendering.npc.blocks.NpcForceMovementBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcHitsBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcLifepointsBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcSayBlock
import com.opennxt.model.entity.rendering.npc.blocks.NpcSpotanimBlock950
import com.opennxt.model.entity.rendering.npc.blocks.NpcShort22Block
import com.opennxt.model.entity.rendering.npc.blocks.NpcString17Block
import com.opennxt.net.buf.GamePacketBuilder

class NpcUpdates {
    var animation: NpcAnimationBlock? = null
        private set
    var hits: NpcHitsBlock? = null
        private set
    var faceCoordinate: NpcFaceCoordinateBlock? = null
        private set
    var say: NpcSayBlock? = null
        private set
    var animationGroup: NpcAnimationGroupBlock? = null
        private set
    var faceEntity: NpcFaceEntityBlock? = null
        private set
    var forceMovement: NpcForceMovementBlock? = null
        private set
    var string17: NpcString17Block? = null
        private set
    var short22: NpcShort22Block? = null
        private set
    var byte25: NpcByte25Block? = null
        private set
    var flag28: NpcFlag28Block? = null
        private set
    var byte34: NpcByte34Block? = null
        private set

    var lifepoints: NpcLifepointsBlock? = null
        private set

    var spotanim950: NpcSpotanimBlock950? = null
        private set

    private val discarded = LinkedHashMap<NpcUpdateBlockType, NpcDiscardedBlock>()

    @Volatile
    var offered: Boolean = false
        internal set

    private fun retireIfOffered() {
        if (offered) clear()
    }

    fun animate(block: NpcAnimationBlock?) = apply { retireIfOffered(); animation = block; offered = false }
    fun animate(id: Int, delay: Int = 0) = animate(NpcAnimationBlock.single(id, delay))

    fun hit(block: NpcHitsBlock?) = apply { retireIfOffered(); hits = block; offered = false }
    fun hit(vararg splats: NpcHitsBlock.Hit) = hit(NpcHitsBlock(splats.toList()))

    fun faceTile(block: NpcFaceCoordinateBlock?) = apply { retireIfOffered(); faceCoordinate = block; offered = false }
    fun faceTile(x: Int, y: Int) = faceTile(NpcFaceCoordinateBlock(x, y))

    fun say(block: NpcSayBlock?) = apply { retireIfOffered(); say = block; offered = false }
    fun say(text: String) = say(NpcSayBlock(text))

    fun animationGroup(block: NpcAnimationGroupBlock?) = apply { retireIfOffered(); animationGroup = block; offered = false }
    fun animationGroup(group: Int) = animationGroup(NpcAnimationGroupBlock(group))

    fun faceEntity(block: NpcFaceEntityBlock?) = apply { retireIfOffered(); faceEntity = block; offered = false }
    fun faceNpc(index: Int) = faceEntity(NpcFaceEntityBlock.npc(index))
    fun facePlayer(index: Int) = faceEntity(NpcFaceEntityBlock.player(index))
    fun faceNothing() = faceEntity(NpcFaceEntityBlock.clear())

    fun forceMovement(block: NpcForceMovementBlock?) = apply { retireIfOffered(); forceMovement = block; offered = false }

    fun string17(block: NpcString17Block?) = apply { retireIfOffered(); string17 = block; offered = false }
    fun string17(text: String) = string17(NpcString17Block(text))

    fun short22(block: NpcShort22Block?) = apply { retireIfOffered(); short22 = block; offered = false }
    fun short22(value: Int) = short22(NpcShort22Block(value))

    fun byte25(value: Int) = apply { retireIfOffered(); byte25 = NpcByte25Block(value); offered = false }
    fun flag28(value: Int) = apply { retireIfOffered(); flag28 = NpcFlag28Block(value); offered = false }
    fun byte34(value: Int) = apply { retireIfOffered(); byte34 = NpcByte34Block(value); offered = false }

    fun lifepoints(block: NpcLifepointsBlock?) = apply { retireIfOffered(); lifepoints = block; offered = false }

    fun lifepoints(current: Int, maximum: Int) = lifepoints(NpcLifepointsBlock.single(current, maximum))

    fun spotanim950(block: NpcSpotanimBlock950?) = apply { retireIfOffered(); spotanim950 = block; offered = false }
    fun spotanim950(spotanim: Int, height: Int = 0, delay: Int = 0) = spotanim950(NpcSpotanimBlock950.single(spotanim, height, delay))

    fun discarded(type: NpcUpdateBlockType, f1: Int = 0, f2: Int = 0, f3: Int = 0) = apply {
        retireIfOffered()
        discarded[type] = NpcDiscardedBlock(type, f1, f2, f3)
        offered = false
    }

    fun clear() {
        animation = null
        hits = null
        faceCoordinate = null
        say = null
        animationGroup = null
        faceEntity = null
        forceMovement = null
        string17 = null
        short22 = null
        byte25 = null
        flag28 = null
        byte34 = null
        lifepoints = null
        discarded.clear()
        spotanim950 = null
        offered = false
    }

    fun blocks(): List<NpcUpdateBlock> {
        if (!extendedEnabled) return emptyList()
        val out = ArrayList<NpcUpdateBlock>(16)
        animation?.let { out.add(it) }
        hits?.let { out.add(it) }
        faceCoordinate?.let { out.add(it) }
        say?.let { out.add(it) }
        animationGroup?.let { out.add(it) }
        faceEntity?.let { out.add(it) }
        forceMovement?.let { out.add(it) }
        string17?.let { out.add(it) }
        short22?.let { out.add(it) }
        byte25?.let { out.add(it) }
        flag28?.let { out.add(it) }
        byte34?.let { out.add(it) }
        lifepoints?.let { out.add(it) }
        out.addAll(discarded.values)
        out.removeAll { !blockEnabled(it.type) }
        out.sortBy { it.type.order }
        return out
    }

    fun isEmpty(): Boolean = blocks().isEmpty()

    fun sendable(build950: Boolean): List<NpcUpdateBlock> {
        val all = blocks()
        if (!build950) return all
        return all.filter { NpcUpdateBlockType.NPC_MASK_950.containsKey(it.type) }
            .sortedBy { NpcUpdateBlockType.ORDER_950.getValue(it.type) }
    }

    fun refusedOn950(): List<NpcUpdateBlock> = blocks().filter { !NpcUpdateBlockType.NPC_MASK_950.containsKey(it.type) }

    fun spotanim950Sendable(build950: Boolean): NpcSpotanimBlock950? =
        if (build950 && extendedEnabled && NpcSpotanimBlock950.enabled()) spotanim950 else null

    fun hasSendable(build950: Boolean): Boolean = sendable(build950).isNotEmpty() || spotanim950Sendable(build950) != null

    fun encode(buffer: GamePacketBuilder, build950: Boolean = false): Boolean {
        val blocks = sendable(build950)
        val spot = spotanim950Sendable(build950)
        if (blocks.isEmpty() && spot == null) return false

        buffer.put(com.opennxt.net.buf.DataType.BYTE, SKIPPED_FILLER)
        buffer.put(com.opennxt.net.buf.DataType.BYTE, SKIPPED_FILLER)

        var mask = 0L
        if (build950) {
            for (block in blocks) mask = mask or NpcUpdateBlockType.maskBit950(block.type)!!
            if (spot != null) mask = mask or (1L shl NpcSpotanimBlock950.MASK_BIT_950)
            NpcUpdateBlockType.writeMask950(buffer, mask)
            var spotWritten = spot == null
            for (block in blocks) {
                if (!spotWritten && NpcUpdateBlockType.ORDER_950.getValue(block.type) > NpcSpotanimBlock950.ORDER_950) {
                    spot!!.encode950(buffer); spotWritten = true
                }
                block.encode950(buffer)
            }
            if (!spotWritten) spot!!.encode950(buffer)
        } else {
            for (block in blocks) mask = mask or block.type.maskBit
            NpcUpdateBlockType.writeMask(buffer, mask)
            for (block in blocks) block.encode(buffer)
        }
        return true
    }

    companion object {
        const val SKIPPED_FILLER = 0

        val extendedEnabled: Boolean =
            System.getProperty("opennxt.experiment.npcs.extended") != "false"

        fun blockEnabled(type: NpcUpdateBlockType): Boolean =
            System.getProperty("opennxt.experiment.npcs.block.${type.name.lowercase()}") != "false"
    }
}
