package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.game.clientprot.IfButtonN
import mu.KotlinLogging

object DialogueWiring {
    private val logger = KotlinLogging.logger { }

    private val owners: MutableMap<ContentPlayer, java.lang.ref.WeakReference<WorldPlayer>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    fun bind(content: ContentPlayer, world: WorldPlayer) {
        val existing = owners[content]?.get()
        if (existing === world) return
        owners[content] = java.lang.ref.WeakReference(world)
    }

    fun ownerOf(content: ContentPlayer): WorldPlayer? = owners[content]?.get()

    fun boundPlayers(): Int = synchronized(owners) { owners.values.count { it.get() != null } }

    internal fun clear() = synchronized(owners) { owners.clear() }

    private class LiveSink(val world: WorldPlayer) : Dialogue.Sink {
        override fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean) =
            world.interfaces.open(id = interfaceId, parent = parent, component = component, walkable = walkable)

        override fun setText(interfaceId: Int, component: Int, text: String) =
            world.interfaces.text(interfaceId, component, text)

        override fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int) =
            world.interfaces.events(interfaceId, component, fromSlot, toSlot, mask)

        override fun setHide(interfaceId: Int, component: Int, hidden: Boolean) =
            world.interfaces.hide(interfaceId, component, hidden)

        override fun setModel(interfaceId: Int, component: Int, kind: Int, value: Int) =
            world.interfaces.model(interfaceId, component, kind, value)

        override fun setAnim(interfaceId: Int, component: Int, anim: Int) {
            world.client.write(
                com.opennxt.net.game.serverprot.generated.IfSetanim(
                    com.opennxt.model.InterfaceHash(interfaceId, component).hash, anim
                )
            )
        }

        override fun closeSub(parent: Int, component: Int) =
            world.interfaces.close(parent, component)

        override fun runClientScript(scriptId: Int, vararg args: Any) {
            world.client.write(com.opennxt.net.game.serverprot.RunClientScript(scriptId, arrayOf(*args)))
        }
    }

    fun install(): Boolean {
        if (!Dialogue.enabled) {
            logger.warn { "dialogue wiring: dialogue is disabled, leaving the no-op sink in place" }
            return false
        }
        Dialogue.sinkSupplier = { content -> ownerOf(content)?.let { LiveSink(it) } }
        logger.info {
            "dialogue wiring installed" + (if (Dialogue.sethideEnabled) " (with IF_SETHIDE)" else "")
        }
        return true
    }

    fun uninstall() {
        Dialogue.sinkSupplier = { null }
        clear()
    }

    fun handleButtonLabelled(world: WorldPlayer, packet: com.opennxt.net.game.clientprot.IfButtonLabelled): Boolean {
        if (!Dialogue.enabled) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        if (Dialogue.sessionOf(content) == null) return false

        val result = Dialogue.onButton(content, packet.interfaceId, packet.component)
        return when (result) {
            is Dialogue.ClickResult.Consumed -> {
                logger.info {
                    "dialogue: ${world.name} labelled button on " +
                        "${packet.interfaceId}:${packet.component}" +
                        (if (result.chose != null) " chose option ${result.chose + 1}" else "") +
                        (if (result.ended) ", conversation ended" else ", next page")
                }
                true
            }
            else -> false
        }
    }

    fun handleButton(world: WorldPlayer, packet: IfButtonN): Boolean {
        if (!Dialogue.enabled) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        if (Dialogue.sessionOf(content) == null) return false

        val result = Dialogue.onButton(content, packet.interfaceId, packet.component)
        return when (result) {
            is Dialogue.ClickResult.Consumed -> {
                logger.info {
                    "dialogue: ${world.name} IF_BUTTON${packet.buttonOp} on " +
                        "${packet.interfaceId}:${packet.component}" +
                        (if (result.chose != null) " chose option ${result.chose + 1}" else "") +
                        (if (result.ended) ", conversation ended" else ", next page")
                }
                true
            }

            is Dialogue.ClickResult.NotArmed -> false
            Dialogue.ClickResult.NoSession -> false
        }
    }

    fun handleUndecodedComponentClick(world: WorldPlayer, interfaceId: Int, component: Int): Boolean {
        if (!Dialogue.enabled || !resume127Enabled) return false
        val content = world.contentPlayerAt()
        bind(content, world)
        if (Dialogue.sessionOf(content) == null) return false

        return when (val result = Dialogue.onButton(content, interfaceId, component)) {
            is Dialogue.ClickResult.Consumed -> {
                logger.info {
                    "dialogue: ${world.name} RESUME_PAUSEBUTTON on " +
                        "$interfaceId:$component" +
                        (if (result.chose != null) " chose option ${result.chose + 1}" else "") +
                        (if (result.ended) ", conversation ended" else ", next page")
                }
                true
            }

            is Dialogue.ClickResult.NotArmed -> false
            Dialogue.ClickResult.NoSession -> false
        }
    }

    val resume127Enabled: Boolean
        get() = System.getProperty("opennxt.experiment.dialogue.resume127") != "false"
}
