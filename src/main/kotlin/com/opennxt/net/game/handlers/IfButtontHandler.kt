package com.opennxt.net.game.handlers

import com.opennxt.content.skills.ProductionActions
import com.opennxt.content.skills.SkillsWiring
import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.generated.IfButtont
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object IfButtontHandler : GamePacketHandler<BasePlayer, IfButtont> {
    private val logger = KotlinLogging.logger { }

    const val NO_SELECTION = 0xffffff

    override fun handle(context: BasePlayer, packet: IfButtont) {
        if (context !is WorldPlayer) { DecodedPacketLogHandler.handle(context, packet); return }
        if (com.opennxt.content.ActionLock.refuse(context.contentPlayer, "IF_BUTTONT item ${packet.selobj} on ${packet.targetobj}")) return
        val fields = "selhash ${packet.selhash ushr 16}:${packet.selhash and 0xffff} selsub ${packet.selsub} selobj ${packet.selobj} -> " +
            "targethash ${packet.targethash ushr 16}:${packet.targethash and 0xffff} targetsub ${packet.targetsub} targetobj ${packet.targetobj}"
        val selIface = packet.selhash ushr 16
        if (selIface in com.opennxt.content.skills.MagicSpells.BOOK_INTERFACES &&
            (packet.selhash and 0xffff) == com.opennxt.content.skills.MagicSpells.BOOK_COMPONENT &&
            packet.targethash == OpLocTHandler.BACKPACK_HASH) {
            val content = context.contentPlayerAt()
            SkillsWiring.bind(content, context)
            val cast = com.opennxt.content.skills.MagicSpells.castOnItem(content, packet.selsub, packet.targetsub, packet.targetobj)
            logger.info { "IF_BUTTONT ${context.name}: spell cast ($fields) -> ${cast.outcome} ${cast.spell?.name ?: ""} ${cast.detail}" }
            return
        }
        if (packet.selhash != OpLocTHandler.BACKPACK_HASH || packet.targethash != OpLocTHandler.BACKPACK_HASH) {
            logger.info { "IF_BUTTONT ${context.name}: not backpack-on-backpack ($fields); ignored" }
            DecodedPacketLogHandler.handle(context, packet)
            return
        }
        if (packet.selobj <= 0 || packet.selobj == NO_SELECTION || packet.targetobj <= 0 || packet.targetobj == NO_SELECTION) {
            logger.warn { "IF_BUTTONT ${context.name}: invalid item id ($fields); rejected" }
            return
        }
        val backpack = PlayerInventory.backpackOf(context)
        val selHeld = if (packet.selsub in 0 until backpack.size) backpack[packet.selsub]?.id else null
        val tgtHeld = if (packet.targetsub in 0 until backpack.size) backpack[packet.targetsub]?.id else null
        if (selHeld != packet.selobj || tgtHeld != packet.targetobj) {
            logger.warn {
                "IF_BUTTONT ${context.name}: items do not match the backpack ($fields; held $selHeld / $tgtHeld); rejected"
            }
            return
        }
        val content = context.contentPlayerAt()
        SkillsWiring.bind(content, context)
        val result = ProductionActions.onItemOnItem(content, packet.selobj, packet.selsub, packet.targetobj, packet.targetsub)
        logger.info { "IF_BUTTONT ${context.name}: $fields -> ${result.outcome} ${result.recipe ?: ""} ${result.detail}" }
    }
}
