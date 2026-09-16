package com.opennxt.content.impl

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.Constants
import mu.KotlinLogging
import java.nio.file.Files

object DialogueSeed {
    private val logger = KotlinLogging.logger {}

    val enabled: Boolean get() = System.getProperty("opennxt.seed.dialogue") != "off"

    private val seedPath = Constants.DATA_PATH.resolve("seed").resolve("dialogue.json")

    const val PLAYER_PLACEHOLDER = "Player"

    private val playerToken = Regex("\\bPlayer\\b")

    class Conversation(
        val page: String,
        val revid: Int,
        val section: String,
        val npcName: String,
        val npcIds: List<Int>,
        val droppedConditional: Int,
        val droppedAction: Int,
        val truncatedOptions: Int,
        val pages: List<Dialogue.Page>
    ) {
        override fun toString() = "$page # $section (npc '$npcName' ${npcIds.size} ids, ${pages.size} pages)"
    }

    class Seed(
        val conversations: List<Conversation>,
        val byId: Map<Int, Conversation>,
        val byName: Map<String, Conversation>,
        val refused: Map<String, Int>,
        val retrieved: String
    ) {
        val pageCount: Int get() = conversations.sumOf { it.pages.size }
    }

    val seed: Seed by lazy { load() }

    fun size(): Int = if (enabled) seed.conversations.size else 0

    fun conversationFor(npcId: Int, npcName: String): Conversation? {
        if (!enabled) return null
        return seed.byId[npcId] ?: seed.byName[npcName.lowercase()]
    }

    fun pagesFor(npcId: Int, npcName: String, playerName: String): List<Dialogue.Page>? {
        val c = conversationFor(npcId, npcName) ?: return null
        return materialise(c.pages, playerName)
    }

    fun materialise(pages: List<Dialogue.Page>, playerName: String): List<Dialogue.Page> =
        pages.map { p ->
            when (p) {
                is Dialogue.Page.Say -> p.copy(
                    name = if (p.speaker == Dialogue.Speaker.PLAYER) playerName
                    else playerToken.replace(p.name, playerName),
                    text = playerToken.replace(p.text, playerName)
                )

                is Dialogue.Page.Choose -> p.copy(
                    options = p.options.map { playerToken.replace(it, playerName) },
                    title = playerToken.replace(p.title, playerName)
                )
            }
        }

    private fun load(): Seed {
        if (!Files.isRegularFile(seedPath)) {
            logger.warn { "dialogue seed: $seedPath is absent; the seeded dialogue layer is empty" }
            return Seed(emptyList(), emptyMap(), emptyMap(), emptyMap(), "")
        }
        val root = JsonParser().parse(Files.newBufferedReader(seedPath)).asJsonObject
        val out = ArrayList<Conversation>()
        val refused = LinkedHashMap<String, Int>()
        fun refuse(why: String) { refused[why] = (refused[why] ?: 0) + 1 }

        for (e in root.getAsJsonArray("conversations")) {
            val o = e as? JsonObject ?: continue
            val pages = ArrayList<Dialogue.Page>()
            var broken: String? = null
            for (pe in o.getAsJsonArray("pages")) {
                val p = pe as? JsonObject ?: continue
                when (p.get("t")?.asString) {
                    "say" -> {
                        val text = p.get("x")?.asString ?: ""
                        val name = p.get("n")?.asString ?: ""
                        if (text.isEmpty() || name.isEmpty()) { broken = "empty-say"; break }
                        pages += Dialogue.Page.Say(
                            speaker = if (p.get("s")?.asString == "player") Dialogue.Speaker.PLAYER
                            else Dialogue.Speaker.NPC,
                            name = name,
                            text = text,
                            endsHere = p.get("e")?.asBoolean == true
                        )
                    }

                    "choose" -> {
                        val opts = p.getAsJsonArray("o").map { it.asString }
                        val targets = p.getAsJsonArray("g").map { it.asInt }
                        if (opts.isEmpty() || opts.size != targets.size ||
                            opts.size > Dialogue.OPTION_ROWS.size || opts.any { it.isEmpty() }
                        ) {
                            broken = "bad-choose"; break
                        }
                        pages += Dialogue.Page.Choose(opts, targets)
                    }

                    else -> { broken = "unknown-page-kind"; break }
                }
            }
            if (broken != null) { refuse(broken); continue }
            val bad = pages.filterIsInstance<Dialogue.Page.Choose>()
                .any { c -> c.targets.any { it >= pages.size || it < -1 } }
            if (bad) { refuse("target-out-of-range"); continue }
            if (pages.size < 2 || pages.first() !is Dialogue.Page.Say) { refuse("shape"); continue }

            out += Conversation(
                page = o.get("page").asString,
                revid = o.get("revid").asInt,
                section = o.get("section")?.asString ?: "",
                npcName = o.get("npc_name").asString,
                npcIds = o.getAsJsonArray("npc_ids").map { it.asInt },
                droppedConditional = o.get("dropped_conditional")?.asInt ?: 0,
                droppedAction = o.get("dropped_action")?.asInt ?: 0,
                truncatedOptions = o.get("truncated_options")?.asInt ?: 0,
                pages = pages
            )
        }

        val byId = LinkedHashMap<Int, Conversation>()
        val byName = LinkedHashMap<String, Conversation>()
        for (c in out) {
            for (id in c.npcIds) byId.putIfAbsent(id, c)
            byName.putIfAbsent(c.npcName.lowercase(), c)
        }
        val seed = Seed(out, byId, byName, refused, root.get("retrieved")?.asString ?: "")
        logger.info {
            "dialogue seed: ${out.size} conversations, ${seed.pageCount} pages, " +
                "${byId.size} npc ids, ${byName.size} npc names" +
                (if (refused.isEmpty()) "" else "; refused $refused")
        }
        return seed
    }
}
