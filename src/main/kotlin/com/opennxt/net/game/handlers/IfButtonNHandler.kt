package com.opennxt.net.game.handlers

import com.opennxt.model.entity.BasePlayer
import com.opennxt.net.game.clientprot.IfButtonN
import com.opennxt.net.game.pipeline.GamePacketHandler
import mu.KotlinLogging

object IfButtonNHandler : GamePacketHandler<BasePlayer, IfButtonN> {
    private val logger = KotlinLogging.logger { }

    override fun handle(context: BasePlayer, packet: IfButtonN) {
        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.DialogueWiring.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.Teleports.handleWornButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.ItemOps.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.LogoutWiring.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == 1431 && packet.component == 0 &&
            com.opennxt.content.impl.ParentWindows.handleRibbonClick(context, packet.arg2)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer && packet.interfaceId == 1477 &&
            ((packet.component == 714 && com.opennxt.content.impl.ParentWindows.handleTabClick(context, packet.arg2)) ||
                (packet.component == 717 && com.opennxt.content.impl.ParentWindows.handleCloseClick(context, packet.arg2)))
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            ((packet.interfaceId == com.opennxt.content.impl.ParentWindows.SKILLS_IFACE &&
                packet.component == com.opennxt.content.impl.ParentWindows.SKILLS_BUTTON_LAYER) ||
                (packet.interfaceId == com.opennxt.content.impl.ParentWindows.SKILLS_IFACE_ALT &&
                    packet.component == com.opennxt.content.impl.ParentWindows.SKILLS_BUTTON_LAYER_ALT)) &&
            com.opennxt.content.impl.ParentWindows.handleSkillClick(context, packet.arg2)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.ParentWindows.SKILLGUIDE_IFACE &&
            com.opennxt.content.impl.ParentWindows.handleSkillGuideClick(context, packet.component)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.ParentWindows.handlePanelButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.RunToggle.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.ToolBeltPanel.isOpenButton(
                packet.interfaceId, packet.component, packet.arg2
            )
        ) {
            com.opennxt.content.impl.ToolBeltPanel.open(context)
            return
        }
        if (context is com.opennxt.model.world.WorldPlayer && packet.interfaceId == 1944 &&
            packet.component == 7
        ) {
            com.opennxt.content.impl.ToolBeltPanel.handleSlotClick(context, packet.arg2)
            return
        }
        if (context is com.opennxt.model.world.WorldPlayer && packet.interfaceId == 1944 &&
            packet.component == com.opennxt.content.impl.ToolBeltPanel.CLOSE_COMPONENT
        ) {
            com.opennxt.content.impl.ToolBeltPanel.close(context)
            return
        }

        if (context is com.opennxt.model.world.WorldPlayer) {
            val stripRow = com.opennxt.content.impl.ToolBeltPanel.noteStripClick(
                context, packet.interfaceId, packet.component, packet.arg2
            )
            if (stripRow != null &&
                com.opennxt.content.impl.ToolBeltPanel.answerStripRow(context, packet.interfaceId, stripRow)
            ) return
        }

        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.SmithingPanel.IFACE &&
            com.opennxt.content.impl.SmithingPanel.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.MakeXPanel.CONTROLS_IFACE
        ) {
            when (packet.component) {
                com.opennxt.content.impl.MakeXPanel.PRODUCT_GRID ->
                    if (com.opennxt.content.impl.MakeXPanel.handleProductClick(context, packet.arg2)) return
                com.opennxt.content.impl.MakeXPanel.QUANTITY ->
                    if (com.opennxt.content.impl.MakeXPanel.handleQuantityClick(context, packet.arg2)) return
                com.opennxt.content.impl.MakeXPanel.CATEGORY_BUTTON ->
                    if (com.opennxt.content.impl.MakeXPanel.handleCategoryButton(context)) return
            }
        }
        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.MakeXPanel.TOPLEVEL &&
            packet.component == com.opennxt.content.impl.MakeXPanel.CATEGORY_LIST &&
            com.opennxt.content.impl.MakeXPanel.handleCategoryChoice(context, packet.arg2)
        ) return
        if (context is com.opennxt.model.world.WorldPlayer &&
            packet.interfaceId == com.opennxt.content.impl.MakeXPanel.IFACE &&
            packet.component == com.opennxt.content.impl.MakeXPanel.CLOSE &&
            com.opennxt.content.impl.MakeXPanel.handleClose(context)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.LootWindow.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.WorldMapWindow.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.BanksWiring.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.OptionsMenu.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            (com.opennxt.content.impl.OptionsMenu.handleLayoutExit(context, packet) ||
                com.opennxt.content.impl.OptionsMenu.handleSaveConfirm(context, packet) ||
                com.opennxt.content.impl.OptionsMenu.handleLayoutDropdown(context, packet))
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.PanelToggles.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.ActionBarLock.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.CustomisationLock.handleButton(context, packet)
        ) return

        if (context is com.opennxt.model.world.WorldPlayer && com.opennxt.content.combat.CombatSpells.handleButton(context, packet)) return

        if (context is com.opennxt.model.world.WorldPlayer && com.opennxt.content.impl.PrayerBook.handleButton(context, packet)) return

        if (context is com.opennxt.model.world.WorldPlayer && com.opennxt.content.ability.AbilityActivation.handleButton(context, packet)) return
        if (context is com.opennxt.model.world.WorldPlayer && com.opennxt.content.combat.Revolution.handleButton(context, packet)) return

        if (context is com.opennxt.model.world.WorldPlayer &&
            com.opennxt.content.impl.GlobalCloseWiring.handleButton(context, packet)
        ) return

        logger.info {
            "IF_BUTTON${packet.buttonOp} " +
                com.opennxt.resources.Names949.component(packet.interfaceId, packet.component) + " " +
                "| item=${packet.mid} slot=${packet.arg2} " +
                "| as-ifbutton1[slot=${packet.altSlot} item=${packet.altItem} " +
                "flags=0x${packet.altFlags.toString(16).padStart(2, '0')}]"
        }
    }
}
