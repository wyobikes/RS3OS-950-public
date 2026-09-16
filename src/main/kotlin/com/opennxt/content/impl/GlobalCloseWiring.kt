package com.opennxt.content.impl

import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import mu.KotlinLogging
import com.opennxt.model.entity.player.PlayerInventory

object GlobalCloseWiring {
    private val logger = KotlinLogging.logger { }

    private val legacyBackpackOn1477_8: Boolean
        get() = System.getProperty("opennxt.wiring.legacyBackpackOn1477_8") == "true"

    private var closeSyncWindowStartMs = 0L
    private var closeSyncWindowCount = 0

    fun handleClientClosedInterfaces(world: WorldPlayer): Boolean {
        if (System.getProperty("opennxt.experiment.ui.clientCloseSync") == "false") return false
        val now = System.currentTimeMillis()
        if (now - closeSyncWindowStartMs > 60_000) { closeSyncWindowStartMs = now; closeSyncWindowCount = 0 }
        closeSyncWindowCount++
        val logIt = closeSyncWindowCount <= 10
        if (closeSyncWindowCount == 11) logger.warn {
            "clientCloseSync: more than 10 close events this minute; muting further logs"
        }
        val dropped = world.interfaces.markModalsClosedByClient()
        if (logIt) logger.info {
            if (dropped.isEmpty())
                "clientCloseSync: close event with no modal interfaces open"
            else
                "clientCloseSync: removed ${dropped.size} client-closed interface(s): $dropped"
        }
        return true
    }

    private var replayGuardLogged = false

    fun handleButton(world: WorldPlayer, packet: IfButtonN): Boolean {
        if (com.opennxt.content.impl.Replay949.only &&
            System.getProperty("opennxt.wiring.globalClose.underReplay") != "true"
        ) {
            if (!replayGuardLogged) {
                replayGuardLogged = true
                logger.warn {
                    "GlobalCloseWiring: disabled in replay-only mode; ignored ${packet.interfaceId}:${packet.component}"
                }
            }
            return false
        }
        if (packet.interfaceId == 1477 && packet.component == 8 && !legacyBackpackOn1477_8) {
            logger.info {
                "ribbon Options: IF_BUTTON${packet.buttonOp} on 1477:8, handled client-side"
            }
            return false
        }

        if (packet.interfaceId == 1477 && packet.component == 8) {
            if (world.interfaces.isOpened(1473)) {
                logger.info { "GlobalCloseWiring: User clicked 1477:8. Closing backpack..." }
                world.interfaces.close(1477, 98)
            } else {
                logger.info { "GlobalCloseWiring: User clicked 1477:8. Opening backpack..." }
                world.interfaces.open(id = 1473, parent = 1477, component = 98, walkable = true)
                world.interfaces.events(id = 1473, component = 7, from = 65535, to = 65535, mask = 2097152)
                world.interfaces.events(id = 1473, component = 7, from = 0, to = 27, mask = 15302030)
                world.interfaces.events(id = 1473, component = 25, from = 0, to = 16, mask = 1422)
                world.interfaces.events(id = 1473, component = 1, from = 0, to = 5, mask = 2099198)
                world.interfaces.events(id = 1473, component = 28, from = 0, to = 5, mask = 2099198)
                PlayerInventory.sendBackpack(world)
            }
            return true
        }
        
        if (packet.interfaceId == 1466 && packet.component == 7) {
            if (world.interfaces.isOpened(1466)) {
                logger.info { "GlobalCloseWiring: User clicked 1466:7. Closing skills..." }
                world.interfaces.close(1477, 284)
            } else {
                logger.info { "GlobalCloseWiring: User clicked 1466:7. Opening skills..." }
                world.interfaces.open(id = 1466, parent = 1477, component = 284, walkable = true)
            }
            return true
        }

        if (packet.interfaceId == 1477 && packet.component == 11) {
            if (world.interfaces.isOpened(1464)) {
                logger.info { "GlobalCloseWiring: User clicked 1477:11. Closing equipment..." }
                world.interfaces.close(1477, 109)
            } else {
                logger.info { "GlobalCloseWiring: User clicked 1477:11. Opening equipment..." }
                world.interfaces.open(id = 1464, parent = 1477, component = 109, walkable = true)
                PlayerInventory.sendWorn(world)
            }
            return true
        }

        if (packet.interfaceId == 1433 && packet.component == 79) {
            world.client.write(com.opennxt.net.game.serverprot.RunClientScript(script = 8179, args = arrayOf()))
            world.interfaces.close(id = 1477, component = 808)
            logger.info { "options menu: closed 1433 at 1477:808" }
            return true
        }

        if (packet.interfaceId == 906 && packet.component == 81) {
            logger.info { "GlobalCloseWiring: User clicked 906:81. Closing modals..." }
            world.interfaces.closeModals()
            return true
        }

        if (packet.interfaceId == 1465 && packet.component == 11) {
            logger.info { "GlobalCloseWiring: User clicked 1465:11. Closing modals..." }
            world.interfaces.closeModals()
            return true
        }

        return false
    }
}
