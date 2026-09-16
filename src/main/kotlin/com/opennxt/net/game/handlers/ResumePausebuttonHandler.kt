package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.generated.ResumePausebutton
import com.opennxt.net.game.pipeline.GamePacketHandler

object ResumePausebuttonHandler : GamePacketHandler<BasePlayer, ResumePausebutton> {
    override fun handle(context: BasePlayer, packet: ResumePausebutton) {
        val interfaceId = (packet.component ushr 16) and 0xffff
        val component = packet.component and 0xffff
        if (context is WorldPlayer &&
            com.opennxt.content.impl.DialogueWiring.handleUndecodedComponentClick(context, interfaceId, component)
        ) return
        if (context is WorldPlayer &&
            interfaceId == com.opennxt.content.impl.MakeXPanel.IFACE &&
            component == com.opennxt.content.impl.MakeXPanel.CONFIRM &&
            com.opennxt.content.impl.MakeXPanel.handleConfirm(context)
        ) return
        DecodedPacketLogHandler.handle(context, packet)
    }
}
