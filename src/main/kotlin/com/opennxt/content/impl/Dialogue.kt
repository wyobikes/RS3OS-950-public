package com.opennxt.content.impl

import com.opennxt.content.ContentPlayer
import com.opennxt.content.ContentRegistry
import com.opennxt.content.NpcContext
import com.opennxt.content.interfaces.InterfaceSlot
import mu.KotlinLogging
import java.util.Collections
import java.util.WeakHashMap

object Dialogue {
    private val logger = KotlinLogging.logger { }

    val enabled: Boolean = System.getProperty("opennxt.experiment.dialogue") != "false"

    val sethideEnabled: Boolean = System.getProperty("opennxt.experiment.dialogue.sethide") != "false"

    val headExperiment: String =
        (System.getProperty("opennxt.experiment.dialogue.head") ?: "speaker").lowercase()

    private val headCycle = java.util.concurrent.atomic.AtomicInteger(0)

    private val HEAD_CANDIDATES = intArrayOf(1, 2, 3, 5)

    const val TALK_TO = "Talk to"

    const val GAMEFRAME = 1477

    const val DIALOGUE_PANEL = 1006

    const val DIALOGUE_STRUCT = 21303

    const val MOUNT_PARAM = 3505

    const val MOUNT_COMPONENT = 750

    val MOUNT_PARAM_COMPONENTS: Map<Int, Int> = linkedMapOf(
        6121 to 746,
        3503 to 747,
        3504 to 748,
        3505 to 750,
        3506 to 751,
    )

    fun mountComponent(): Int {
        val raw = System.getProperty("opennxt.experiment.dialogue.mountParam")?.trim()
            ?: return MOUNT_COMPONENT
        val n = raw.toIntOrNull() ?: return MOUNT_COMPONENT
        MOUNT_PARAM_COMPONENTS[n]?.let { return it }
        return if (n in 0..0xffff) n else MOUNT_COMPONENT
    }

    fun mountParamResolved(): Int =
        MOUNT_PARAM_COMPONENTS.entries.firstOrNull { it.value == mountComponent() }?.key ?: -1

    fun effectiveMount(): Pair<Int, Int> {
        val parent = if (InterfaceSlot.GAME_DIALOG.parent != -1) InterfaceSlot.GAME_DIALOG.parent else GAMEFRAME
        val override = System.getProperty("opennxt.experiment.dialogue.mountParam") != null
        val comp = if (!override && InterfaceSlot.GAME_DIALOG.component != -1)
            InterfaceSlot.GAME_DIALOG.component else mountComponent()
        return parent to comp
    }

    fun effectiveMountParam(): Int {
        val (_, comp) = effectiveMount()
        if (System.getProperty("opennxt.experiment.dialogue.mountParam") == null &&
            InterfaceSlot.GAME_DIALOG.component == comp && InterfaceSlot.GAME_DIALOG.mountParam != -1
        ) return InterfaceSlot.GAME_DIALOG.mountParam
        return MOUNT_PARAM_COMPONENTS.entries.firstOrNull { it.value == comp }?.key ?: -1
    }

    const val NPC_CHAT = 1184

    const val PLAYER_CHAT = 1191

    const val OPTIONS = 1188

    val MOUNT_FAMILY: IntArray = intArrayOf(1188, 1193, 1184, 1186, 835, 1189, 1191, 1187, 1192)

    val ALT_MOUNT_FAMILY: IntArray = intArrayOf(387, 327)

    val MESSAGE_BOXES: IntArray = intArrayOf(210, 211, 212, 213, 214)

    const val ROOT_COMPONENT = 0

    const val CHAT_HIDE_COMPONENT = 11

    const val CHAT_NAME_COMPONENT = 4

    const val CHAT_TEXT_COMPONENT = 10

    const val CHAT_HEAD_COMPONENT = 8

    const val CHAT_CONTINUE_BLOCKER = 15

    val CHAT_CONTINUE_COMPONENTS: IntArray = intArrayOf(15)

    fun continueComponents(): IntArray {
        val raw = System.getProperty("opennxt.experiment.dialogue.continueComponents")
            ?: return CHAT_CONTINUE_COMPONENTS
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..0xffff }.toIntArray()
    }

    fun armMask(): Int =
        System.getProperty("opennxt.experiment.dialogue.armMask")
            ?.trim()?.toIntOrNull()?.takeIf { it in 0..2047 } ?: CONTINUE_MASK

    val OPTION_ROWS: IntArray =
        System.getProperty("opennxt.experiment.dialogue.optionRows")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 5 }?.toIntArray()
            ?: intArrayOf(8, 13, 18, 23, 28)

    val OPTION_ROW_ALIASES: IntArray =
        System.getProperty("opennxt.experiment.dialogue.optionRowAliases")
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 5 }?.toIntArray()
            ?: intArrayOf(9, 14, 19, 24, 29)

    val OPTION_TEXTS: IntArray = intArrayOf(6, 33, 35, 37, 39)

    fun standardSequence(): Boolean =
        System.getProperty("opennxt.experiment.dialogue.sequence") != "off"

    const val PAGE_SCRIPT = 8178

    const val OPTIONS_SCRIPT = 5589

    const val DEFAULT_OPTIONS_TITLE = "Choose an option:"

    val DEFAULT_NPC_HEAD_ANIMS: IntArray = intArrayOf(9827, 9843, 9809, 9840, 9808, 9833)

    const val DEFAULT_NPC_HEAD_ANIM = 9827

    const val DEFAULT_PLAYER_HEAD_ANIM = 37905

    const val DEFAULT_EXPRESSION = 1

    const val OP1_MASK = 2

    const val CONTINUE_MASK = 1

    const val NO_EVENTS_MASK = 0

    fun blockerMask(): Int =
        System.getProperty("opennxt.experiment.dialogue.blockerMask")
            ?.trim()?.toIntOrNull()?.takeIf { it in 0..2047 } ?: 2

    private fun blockerMaskIsExplicit(): Boolean =
        System.getProperty("opennxt.experiment.dialogue.blockerMask")
            ?.trim()?.toIntOrNull()?.let { it in 0..2047 } == true

    private fun armMaskIsExplicit(): Boolean =
        System.getProperty("opennxt.experiment.dialogue.armMask")
            ?.trim()?.toIntOrNull()?.let { it in 0..2047 } == true

    fun maskFor(comp: Int): Int =
        if (comp == CHAT_CONTINUE_BLOCKER && blockerMaskIsExplicit() && !armMaskIsExplicit())
            blockerMask() else armMask()

    fun effectiveBlockerMask(): Int =
        if (CHAT_CONTINUE_BLOCKER in continueComponents()) maskFor(CHAT_CONTINUE_BLOCKER) else blockerMask()

    fun deliverableOps(mask: Int, str2NonEmpty: Boolean = false): List<Int> =
        (if (str2NonEmpty) 0..10 else 1..10).filter { (mask ushr it) and 1 == 1 }

    fun hasBakedMenuString(interfaceId: Int, component: Int): Boolean =
        runCatching {
            if (!com.opennxt.resources.sqlite.RsDatabase.available) return@runCatching false
            val hash = (interfaceId shl 16) or component
            val menu = com.opennxt.resources.sqlite.RsDatabase.queryAll(
                "SELECT value FROM interfaces_attr WHERE id = $hash AND field = 'menu'"
            ) { it.getString(1) }.firstOrNull() ?: return@runCatching false
            val i = menu.indexOf("\"str2\":\"")
            if (i < 0) false else menu.substring(i + 8).takeWhile { it != '"' }.isNotEmpty()
        }.getOrDefault(false)

    fun openWalkable(): Boolean =
        System.getProperty("opennxt.experiment.dialogue.walkable")?.toBoolean()
            ?: false

    const val SLOT_SELF = 65535

    const val PROVENANCE =
        "Speech from the dialogue seed (-Dopennxt.seed.dialogue=off disables it), else a built-in fallback."

    const val PROVENANCE_SHORT =
        "[speech: seed or fallback]"

    const val DIALOGUE_TEXT_IS_NOT_IN_THE_CACHE =
        "The cache holds no dialogue text; it comes from seed data or built-in content."

    interface Sink {
        fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean)
        fun setText(interfaceId: Int, component: Int, text: String)
        fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int)
        fun setHide(interfaceId: Int, component: Int, hidden: Boolean)

        fun setModel(interfaceId: Int, component: Int, kind: Int, value: Int)

        fun setAnim(interfaceId: Int, component: Int, anim: Int)

        fun setNpcHead(interfaceId: Int, component: Int, npcId: Int) =
            setModel(interfaceId, component, 2, npcId)

        fun setPlayerHead(interfaceId: Int, component: Int) =
            setModel(interfaceId, component, 3, -1)

        fun closeSub(parent: Int, component: Int)

        fun runClientScript(scriptId: Int, vararg args: Any)
    }

    class RecordingSink : Sink {
        val sent = ArrayList<String>()
        override fun openSub(interfaceId: Int, parent: Int, component: Int, walkable: Boolean) {
            sent += "IF_OPENSUB $interfaceId -> $parent:$component walkable=$walkable"
        }

        override fun setText(interfaceId: Int, component: Int, text: String) {
            sent += "IF_SETTEXT $interfaceId:$component = '$text'"
        }

        override fun setEvents(interfaceId: Int, component: Int, fromSlot: Int, toSlot: Int, mask: Int) {
            sent += "IF_SETEVENTS $interfaceId:$component $fromSlot..$toSlot mask=$mask"
        }

        override fun setHide(interfaceId: Int, component: Int, hidden: Boolean) {
            sent += "IF_SETHIDE $interfaceId:$component hidden=$hidden"
        }

        override fun setModel(interfaceId: Int, component: Int, kind: Int, value: Int) {
            sent += "IF_MODEL_K$kind $interfaceId:$component value=$value"
        }

        override fun setAnim(interfaceId: Int, component: Int, anim: Int) {
            sent += "IF_SETANIM $interfaceId:$component anim=$anim"
        }

        override fun setNpcHead(interfaceId: Int, component: Int, npcId: Int) {
            sent += "IF_SETNPCHEAD $interfaceId:$component npc=$npcId"
        }

        override fun setPlayerHead(interfaceId: Int, component: Int) {
            sent += "IF_SETPLAYERHEAD $interfaceId:$component"
        }

        override fun closeSub(parent: Int, component: Int) {
            sent += "IF_CLOSESUB $parent:$component"
        }

        override fun runClientScript(scriptId: Int, vararg args: Any) {
            sent += "RUNCLIENTSCRIPT $scriptId" + if (args.isEmpty()) "" else
                " [" + args.joinToString { if (it is String) "\"$it\"" else it.toString() } + "]"
        }

        fun clear() = sent.clear()
    }

    var sinkSupplier: (ContentPlayer) -> Sink? = { null }

    enum class Speaker { NPC, PLAYER }

    sealed class Page {
        data class Say(
            val speaker: Speaker,
            val name: String,
            val text: String,
            val headAnim: Int = -1,
            val expression: Int = -1,
            val endsHere: Boolean = false
        ) : Page() {
            val interfaceId: Int get() = if (speaker == Speaker.NPC) NPC_CHAT else PLAYER_CHAT
        }

        data class Choose(
            val options: List<String>,
            val targets: List<Int>,
            val title: String = DEFAULT_OPTIONS_TITLE
        ) : Page() {
            init {
                require(options.size == targets.size) { "options and targets must be parallel" }
                require(options.isNotEmpty() && options.size <= OPTION_ROWS.size) {
                    "1188 has ${OPTION_ROWS.size} option rows; asked for ${options.size}"
                }
            }
        }
    }

    class Session(
        val npcId: Int,
        val npcName: String,
        val pages: List<Page>,
        val headKind: Int = 0
    ) {
        var index: Int = 0
            internal set

        internal val armed = ArrayList<Pair<Int, Int>>()

        var clicks: Int = 0
            internal set

        val page: Page? get() = pages.getOrNull(index)
        override fun toString() = "Session(npc $npcId '$npcName', page $index/${pages.size}, clicks $clicks)"
    }

    private val sessions: MutableMap<ContentPlayer, Session> =
        Collections.synchronizedMap(WeakHashMap())

    fun sessionOf(player: ContentPlayer): Session? = sessions[player]

    fun openSessions(): Int = synchronized(sessions) { sessions.size }

    internal fun clearSessions() = synchronized(sessions) { sessions.clear() }

    sealed class ClickResult {
        data class Consumed(val ended: Boolean, val chose: Int?) : ClickResult()

        object NoSession : ClickResult()

        data class NotArmed(val interfaceId: Int, val component: Int) : ClickResult()
    }

    fun script(npcId: Int, npcName: String, playerName: String): List<Page> {
        if (npcId == 758) {
            return listOf(
                Page.Say(Speaker.NPC, npcName, "What are you doing on my land? You're not the one who keeps leaving my gates open and letting my sheep out are you?"),
                Page.Choose(
                    options = listOf("I'm looking for a quest.", "I'm looking for something to kill.", "I'm lost."),
                    targets = listOf(2, 6, 8)
                ),
                Page.Say(Speaker.PLAYER, playerName, "I'm looking for a quest."),
                Page.Say(Speaker.NPC, npcName, "You're after a quest, you say? Actually I could do with a bit of help."),
                Page.Say(Speaker.NPC, npcName, "My sheep are getting mighty woolly. I'd be much obliged if you could shear them. And while you're at it, spin the wool into balls for me too."),
                Page.Say(Speaker.NPC, npcName, "Yes, 20 balls of wool should do me. I'm sure I could sort out some sort of payment."),
                Page.Say(Speaker.PLAYER, playerName, "I'm looking for something to kill."),
                Page.Say(Speaker.NPC, npcName, "What, on my land? Leave my livestock alone you scoundrel!"),
                Page.Say(Speaker.PLAYER, playerName, "I'm lost."),
                Page.Say(Speaker.NPC, npcName, "This is Lumbridge. The castle is just to the south.")
            )
        }
        
        DialogueSeed.pagesFor(npcId, npcName, playerName)?.let { return it }

        return listOf(
            Page.Say(Speaker.NPC, npcName, "Hello there."),
            Page.Choose(options = listOf("Who are you?", "Never mind."), targets = listOf(2, -1)),
            Page.Say(Speaker.NPC, npcName, "I am $npcName. This server knows my name, but doesn't have my dialogue mapped yet."),
            Page.Say(Speaker.PLAYER, playerName, "Fair enough. Good day.")
        )
    }

    fun start(player: ContentPlayer, npcId: Int, npcName: String): Session {
        close(player)
        val session = Session(npcId, npcName, script(npcId, npcName, player.name), nextHeadKind())
        sessions[player] = session
        render(player, session)
        logger.info {
            "dialogue: ${player.name} opened a conversation with '$npcName' (npc $npcId), " +
                "${session.pages.size} pages. $PROVENANCE_SHORT"
        }
        return session
    }

    fun headAnimOf(page: Page.Say): Int =
        if (page.headAnim >= 0) page.headAnim
        else if (page.speaker == Speaker.NPC) DEFAULT_NPC_HEAD_ANIM else DEFAULT_PLAYER_HEAD_ANIM

    fun expressionOf(page: Page.Say): Int =
        if (page.expression >= 0) page.expression else DEFAULT_EXPRESSION

    fun lineTextOf(page: Page.Say): String = "<p=${expressionOf(page)}>${page.text}"

    private fun render(player: ContentPlayer, session: Session) {
        val sink = sinkSupplier(player)
        disarm(sink, session)

        val (mountParent, mountComp) = effectiveMount()
        val standardSeq = standardSequence()

        when (val page = session.page) {
            null -> return
            is Page.Say -> {
                val iface = page.interfaceId
                if (!standardSeq) sink?.openSub(iface, mountParent, mountComp, walkable = openWalkable())

                hide(sink, iface, if (standardSeq) CHAT_HIDE_COMPONENT else ROOT_COMPONENT, false)
                if (standardSeq) sink?.setAnim(iface, CHAT_HEAD_COMPONENT, headAnimOf(page))
                sink?.setText(iface, CHAT_NAME_COMPONENT, page.name)
                sink?.setText(iface, CHAT_TEXT_COMPONENT, if (standardSeq) lineTextOf(page) else page.text)

                head(sink, session, page, iface)

                val comps = continueComponents()
                for (comp in comps) {
                    if (!standardSeq) sink?.setEvents(iface, comp, SLOT_SELF, SLOT_SELF, maskFor(comp))
                    session.armed += iface to comp
                }
                if (CHAT_CONTINUE_BLOCKER !in comps) {
                    if (!standardSeq) sink?.setEvents(iface, CHAT_CONTINUE_BLOCKER, SLOT_SELF, SLOT_SELF, blockerMask())
                    session.armed += iface to CHAT_CONTINUE_BLOCKER
                }

                if (standardSeq) {
                    sink?.openSub(iface, mountParent, mountComp, walkable = openWalkable())
                    sink?.runClientScript(PAGE_SCRIPT)
                }
            }

            is Page.Choose -> {
                sink?.openSub(OPTIONS, mountParent, mountComp, walkable = openWalkable())

                if (standardSeq) {
                    sink?.runClientScript(PAGE_SCRIPT)
                    val args = ArrayList<Any>(2 + OPTION_ROWS.size)
                    args += page.title
                    args += page.options.size
                    for (i in OPTION_ROWS.indices) args += page.options.getOrElse(i) { "" }
                    sink?.runClientScript(OPTIONS_SCRIPT, *args.toTypedArray())
                    for (i in page.options.indices) {
                        session.armed += OPTIONS to OPTION_ROWS[i]
                        session.armed += OPTIONS to OPTION_ROW_ALIASES[i]
                    }
                } else {
                    hide(sink, OPTIONS, ROOT_COMPONENT, false)
                    for (i in OPTION_ROWS.indices) {
                        val used = i < page.options.size
                        val mask = if (used) armMask() else NO_EVENTS_MASK
                        sink?.setText(OPTIONS, OPTION_TEXTS[i], if (used) page.options[i] else "")
                        sink?.setEvents(OPTIONS, OPTION_ROWS[i], SLOT_SELF, SLOT_SELF, mask)
                        sink?.setEvents(OPTIONS, OPTION_ROW_ALIASES[i], SLOT_SELF, SLOT_SELF, mask)
                        if (used) {
                            session.armed += OPTIONS to OPTION_ROWS[i]
                            session.armed += OPTIONS to OPTION_ROW_ALIASES[i]
                        }
                    }
                }
            }
        }
    }

    private fun head(sink: Sink?, session: Session, page: Page.Say, iface: Int) {
        val kind = if (headExperiment == "speaker") {
            if (page.speaker == Speaker.NPC) 2 else 3
        } else session.headKind
        if (kind == 0) return

        val value = when (kind) {
            1 -> NpcHeadModels.firstHeadModel(session.npcId) ?: run {
                logger.info {
                    "dialogue: npc ${session.npcId} ('${session.npcName}') has no head model; no chathead drawn"
                }
                return
            }
            2 -> session.npcId
            else -> -1
        }

        when (kind) {
            2 -> sink?.setNpcHead(iface, CHAT_HEAD_COMPONENT, value)
            3 -> sink?.setPlayerHead(iface, CHAT_HEAD_COMPONENT)
            else -> sink?.setModel(iface, CHAT_HEAD_COMPONENT, kind, value)
        }

        logger.info {
            val what = when (kind) {
                1 -> "head model $value" +
                    (NpcHeadModels.partCount(session.npcId).let {
                        if (it > 1) " (first of $it parts)" else ""
                    })
                2 -> "npc id $value (head model " +
                    "${NpcHeadModels.firstHeadModel(session.npcId) ?: -1})"
                else -> "local player head"
            }
            "dialogue: chathead for '${session.npcName}' via IF_MODEL_K$kind on $iface:$CHAT_HEAD_COMPONENT, $what"
        }
    }

    private fun nextHeadKind(): Int = when (headExperiment) {
        "off" -> 0
        "k1" -> 1
        "k2" -> 2
        "k3" -> 3
        "k5" -> 5
        "cycle" -> HEAD_CANDIDATES[
            Math.floorMod(headCycle.getAndIncrement(), HEAD_CANDIDATES.size)
        ]
        else -> 0
    }

    internal fun resetHeadCycle() = headCycle.set(0)

    private fun disarm(sink: Sink?, session: Session) {
        val send = !standardSequence()
        for ((iface, component) in session.armed) {
            if (send) sink?.setEvents(iface, component, SLOT_SELF, SLOT_SELF, NO_EVENTS_MASK)
        }
        session.armed.clear()
    }

    private fun hide(sink: Sink?, interfaceId: Int, component: Int, hidden: Boolean) {
        if (!sethideEnabled) return
        sink?.setHide(interfaceId, component, hidden)
    }

    fun onButton(player: ContentPlayer, interfaceId: Int, component: Int): ClickResult {
        val session = sessions[player] ?: return ClickResult.NoSession
        if ((interfaceId to component) !in session.armed) {
            logger.warn {
                "dialogue: ${player.name} clicked unarmed $interfaceId:$component on page ${session.page}; ignored"
            }
            return ClickResult.NotArmed(interfaceId, component)
        }
        session.clicks++

        return when (val page = session.page) {
            is Page.Say -> {
                advance(player, session,
                    if (page.endsHere) session.pages.size else session.index + 1)
                ClickResult.Consumed(ended = sessions[player] == null, chose = null)
            }

            is Page.Choose -> {
                val choice = OPTION_ROWS.indexOf(component)
                    .let { if (it >= 0) it else OPTION_ROW_ALIASES.indexOf(component) }
                val target = page.targets.getOrElse(choice) { -1 }
                logger.info {
                    "dialogue: ${player.name} chose option ${choice + 1} " +
                        "('${page.options.getOrNull(choice)}') -> " +
                        (if (target < 0) "end" else "page $target") + ". $PROVENANCE_SHORT"
                }
                advance(player, session, if (target < 0) session.pages.size else target)
                ClickResult.Consumed(ended = sessions[player] == null, chose = choice)
            }

            null -> {
                close(player)
                ClickResult.Consumed(ended = true, chose = null)
            }
        }
    }

    private fun advance(player: ContentPlayer, session: Session, next: Int) {
        session.index = next
        if (session.page == null) {
            close(player)
            return
        }
        render(player, session)
    }

    fun close(player: ContentPlayer): Boolean {
        val session = sessions.remove(player) ?: return false
        val sink = sinkSupplier(player)
        disarm(sink, session)
        if (!standardSequence()) {
            for (iface in intArrayOf(NPC_CHAT, PLAYER_CHAT)) {
                sink?.setText(iface, CHAT_NAME_COMPONENT, "")
                sink?.setText(iface, CHAT_TEXT_COMPONENT, "")
            }
            for (slot in OPTION_TEXTS) sink?.setText(OPTIONS, slot, "")
            for (iface in intArrayOf(NPC_CHAT, PLAYER_CHAT, OPTIONS)) {
                hide(sink, iface, ROOT_COMPONENT, true)
            }
        }
        val (mountParent, mountComp) = effectiveMount()

        sink?.closeSub(mountParent, mountComp)
        logger.info { "dialogue: ${player.name} closed $session. $PROVENANCE_SHORT" }
        return true
    }

    fun install(): Int {
        if (!enabled) {
            logger.warn { "dialogue: disabled (-Dopennxt.experiment.dialogue=false)" }
            return 0
        }
        val bound = ContentRegistry.onNpcAction(TALK_TO) { ctx: NpcContext ->
            val name = ctx.definition.name ?: "Someone"
            start(ctx.player, ctx.npcId, name)
            OPENED
        }
        logger.info { experimentLine() }
        logger.info {
            "dialogue: bound '$TALK_TO' on $bound npc ids; interfaces $NPC_CHAT/$PLAYER_CHAT/$OPTIONS, " +
                "IF_SETHIDE " + (if (sethideEnabled) "on" else "off")
        }
        logger.info { "dialogue: seed loaded - ${DialogueSeed.size()} conversations" }
        return bound
    }

    fun experimentLine(): String {
        val (mountParent, mount) = effectiveMount()
        val param = effectiveMountParam()
        val comps = continueComponents()
        val blocker = effectiveBlockerMask()
        val armMasks = comps.map { maskFor(it) }.distinct()
        val arm = if (comps.isEmpty()) armMask() else armMasks.first()
        val armExtra = if (armMasks.size > 1) " (mixed: ${armMasks.sorted()})" else ""
        val blockerStr2 = hasBakedMenuString(NPC_CHAT, CHAT_CONTINUE_BLOCKER)
        val armStr2 = comps.any { hasBakedMenuString(NPC_CHAT, it) }
        val blockerOps = deliverableOps(blocker, blockerStr2)
        val armOps = deliverableOps(arm, armStr2)
        val standardSeq = standardSequence()
        val eventsNote = if (standardSeq) " [standard sequence]"
        else " [legacy sequence]"
        return "dialogue: mount=$mountParent:$mount " +
            (if (param >= 0) "(struct $DIALOGUE_STRUCT param $param)" else "(raw component)") +
            eventsNote +
            "; IF_OPENSUB type=${if (openWalkable()) 1 else 0}" +
            "; blocker $NPC_CHAT:$CHAT_CONTINUE_BLOCKER mask=$blocker str2=" +
            (if (blockerStr2) "set" else "empty") +
            " ops=" +
            (if (blockerOps.isEmpty()) "none" else blockerOps.toString()) +
            "; armed=${comps.joinToString(",")} mask=$arm$armExtra str2=" +
            (if (armStr2) "set" else "empty") + " ops=" +
            (if (armOps.isEmpty()) "none" else armOps.toString())
    }

    const val OPENED = "dialogue-opened"
}
