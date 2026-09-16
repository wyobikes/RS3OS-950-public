package com.opennxt.model.account

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opennxt.api.stat.Stat
import com.opennxt.model.bank.Bank
import com.opennxt.model.items.Item
import com.opennxt.model.items.ItemContainer
import mu.KotlinLogging

data class PlayerSave(
    val username: String,
    val xp: Map<Stat, Double>,
    val x: Int,
    val y: Int,
    val plane: Int,
    val backpack: List<SavedItem>,
    val bank: List<SavedItem>,
    val varps: Map<Int, Int>,
    val worn: List<SavedItem> = emptyList(),
    val rights: Int = 0,
    val varcs: Map<Int, Any> = emptyMap(),
    val cosmetics: Map<Int, Int> = emptyMap(),
    val toolbelt: List<Int> = emptyList(),
    val smithing: SavedProject? = null,
    val run: SavedRunEnergy? = null,
    val coinPouch: Int = 0,
    val bankTabs: List<Int> = emptyList(),
    val metalBank: List<SavedItem> = emptyList(),
    val slayerTask: SavedSlayerTask? = null,
) {
    data class SavedSlayerTask(val monster: String, val left: Int, val xpTenths: Int, val master: String) {
        init {
            require(monster.isNotBlank()) { "slayer task monster must not be blank" }
            require(left >= 1) { "slayer task left must be at least 1: $left (a finished task is not stored)" }
            require(xpTenths >= 0) { "slayer task xpTenths must not be negative: $xpTenths" }
            require(master.isNotBlank()) { "slayer task master must not be blank" }
        }
    }

    data class SavedItem(val slot: Int, val id: Int, val amount: Int) {
        init {
            require(slot >= 0) { "slot must not be negative: $slot" }
            require(id >= 0) { "item id must not be negative: $id" }
            require(amount >= 1) { "amount must be at least 1: $amount (an empty slot is omitted, not zero)" }
        }
    }

    data class SavedProject(
        val productId: Int,
        val progress: Int,
        val xpPaidTenths: Int,
        val heat: Int,
        val stage: Int,
    ) {
        init {
            require(productId >= 0) { "smithing product id must not be negative: $productId" }
            require(progress >= 0) { "smithing progress must not be negative: $progress" }
            require(xpPaidTenths >= 0) { "smithing xpPaidTenths must not be negative: $xpPaidTenths" }
            require(heat >= 0) { "smithing heat must not be negative: $heat" }
            require(stage >= 0) { "smithing heat stage must not be negative: $stage" }
        }
    }

    data class SavedRunEnergy(val tenths: Int, val toggled: Boolean) {
        init {
            require(tenths >= 0) { "run energy tenths must not be negative: $tenths" }
            require(tenths <= MAX_RUN_TENTHS) { "run energy tenths above the maximum: $tenths > $MAX_RUN_TENTHS" }
        }
    }

    init {
        require(username.isNotBlank()) { "username must not be blank" }
        for (stat in Stat.values()) {
            if (stat !in xp) throw IllegalArgumentException("save for '$username' is missing xp for $stat")
        }
        require(xp.size == Stat.values().size) { "xp map has ${xp.size} entries, expected ${Stat.values().size}" }
        xp.forEach { (stat, v) ->
            require(v.isFinite() && v >= 0.0) { "xp for $stat is not a finite non-negative double: $v" }
        }
        requireSlots(backpack, ItemContainer.INVENTORY_SIZE, "backpack")
        requireSlots(bank, Bank.CAPACITY, "bank")
        requireSlots(worn, WORN_CAPACITY, "worn")
        require(rights >= 0) { "rights must not be negative: $rights" }
        require(coinPouch >= 0) { "coin pouch must not be negative: $coinPouch" }
        require(bankTabs.size <= Bank.TAB_COUNT) { "${bankTabs.size} bank tabs; the client has ${Bank.TAB_COUNT}" }
        bankTabs.forEach { require(it >= 1) { "a stored bank tab has size $it; an empty tab is dropped, not stored" } }
        require(bankTabs.sumOf { it.toLong() } <= Bank.CAPACITY) {
            "bank tabs claim ${bankTabs.sumOf { it.toLong() }} slots of a ${Bank.CAPACITY}-slot bank"
        }
        requireSlots(metalBank, com.opennxt.model.bank.MetalBank.CAPACITY, "metal bank")
        require(metalBank.map { it.id }.toSet().size == metalBank.size) { "metal bank holds an item id in two slots" }
        toolbelt.forEach { id -> require(id >= 0) { "tool belt item id must not be negative: $id" } }
        require(toolbelt.size == toolbelt.toSet().size) {
            "tool belt holds a duplicate item id: $toolbelt"
        }
        varcs.forEach { (id, v) ->
            require(id >= 0) { "varc id must not be negative: $id" }
            require(v is Int || v is Long || v is String) {
                "varc $id holds ${v.javaClass.simpleName}; only Int, Long and String are storable"
            }
        }
    }

    private fun requireSlots(items: List<SavedItem>, capacity: Int, what: String) {
        val seen = HashSet<Int>()
        items.forEach {
            require(it.slot < capacity) { "$what slot ${it.slot} outside 0..${capacity - 1}" }
            require(seen.add(it.slot)) { "$what slot ${it.slot} appears twice" }
        }
    }

    fun restoreContainer(items: List<SavedItem>, capacity: Int, stackAll: Boolean = false): ItemContainer {
        val container = ItemContainer(capacity, stackAll)
        items.forEach { container[it.slot] = Item(it.id, it.amount) }
        return container
    }

    fun restoreBackpack(): ItemContainer = restoreContainer(backpack, ItemContainer.INVENTORY_SIZE)

    fun restoreWorn(): ItemContainer = restoreContainer(worn, WORN_CAPACITY)

    fun restoreBankContents(): ItemContainer = restoreContainer(bank, Bank.CAPACITY, stackAll = true)

    fun bankItems(): List<Bank.BankedItem> = bank.map { Bank.BankedItem(it.slot, it.id, it.amount) }

    fun metalBankItems(): List<Bank.BankedItem> = metalBank.map { Bank.BankedItem(it.slot, it.id, it.amount) }

    fun toJson(): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("username", username)
        val xpObj = JsonObject()
        Stat.values().forEach { stat -> xpObj.addProperty(stat.name, xp.getValue(stat)) }
        root.add("xp", xpObj)
        val pos = JsonObject()
        pos.addProperty("x", x)
        pos.addProperty("y", y)
        pos.addProperty("plane", plane)
        root.add("position", pos)
        root.add("backpack", itemsToJson(backpack))
        root.add("worn", itemsToJson(worn))
        root.addProperty("rights", rights)
        root.add("bank", itemsToJson(bank))
        val varpObj = JsonObject()
        varps.keys.sorted().forEach { id -> varpObj.addProperty(id.toString(), varps.getValue(id)) }
        root.add("varps", varpObj)
        val varcObj = JsonObject()
        varcs.keys.sorted().forEach { id ->
            when (val v = varcs.getValue(id)) {
                is Int -> varcObj.addProperty(id.toString(), v)
                is Long -> varcObj.addProperty(id.toString(), v)
                is String -> varcObj.addProperty(id.toString(), v)
                else -> error("unstorable varc $id: ${v.javaClass.simpleName}")
            }
        }
        root.add("varcs", varcObj)
        if (cosmetics.isNotEmpty()) {
            val cosObj = JsonObject()
            cosmetics.keys.sorted().forEach { slot -> cosObj.addProperty(slot.toString(), cosmetics.getValue(slot)) }
            root.add("cosmetics", cosObj)
        }
        if (toolbelt.isNotEmpty()) {
            val beltArr = JsonArray()
            toolbelt.sorted().forEach { beltArr.add(it) }
            root.add("toolbelt", beltArr)
        }
        smithing?.let { p ->
            val obj = JsonObject()
            obj.addProperty("product", p.productId)
            obj.addProperty("progress", p.progress)
            obj.addProperty("xpPaidTenths", p.xpPaidTenths)
            obj.addProperty("heat", p.heat)
            obj.addProperty("stage", p.stage)
            root.add("smithing", obj)
        }
        run?.let { r ->
            val obj = JsonObject()
            obj.addProperty("tenths", r.tenths)
            obj.addProperty("on", r.toggled)
            root.add("run", obj)
        }
        if (coinPouch > 0) root.addProperty("coinPouch", coinPouch)
        if (bankTabs.isNotEmpty()) {
            val tabArr = JsonArray()
            bankTabs.forEach { tabArr.add(it) }
            root.add("bankTabs", tabArr)
        }
        if (metalBank.isNotEmpty()) root.add("metalBank", itemsToJson(metalBank))
        slayerTask?.let { t ->
            val o = JsonObject()
            o.addProperty("monster", t.monster)
            o.addProperty("left", t.left)
            o.addProperty("xpTenths", t.xpTenths)
            o.addProperty("master", t.master)
            root.add("slayerTask", o)
        }
        return root.toString()
    }

    private fun itemsToJson(items: List<SavedItem>): JsonArray {
        val arr = JsonArray()
        items.sortedBy { it.slot }.forEach {
            val o = JsonObject()
            o.addProperty("slot", it.slot)
            o.addProperty("id", it.id)
            o.addProperty("amount", it.amount)
            arr.add(o)
        }
        return arr
    }

    companion object {
        private val logger = KotlinLogging.logger { }
        const val FORMAT = 1

        const val WORN_CAPACITY = 19

        const val MAX_RUN_TENTHS = 1000

        const val SPAWN_X = 3222
        const val SPAWN_Y = 3222
        const val SPAWN_PLANE = 0

        const val CONSTITUTION_START_XP = 1154.0

        fun fromNew(username: String): PlayerSave {
            val xp = LinkedHashMap<Stat, Double>()
            Stat.values().forEach { stat ->
                xp[stat] = if (stat == Stat.CONSTITUTION) CONSTITUTION_START_XP else 0.0
            }
            return PlayerSave(
                username = username,
                xp = xp,
                x = SPAWN_X, y = SPAWN_Y, plane = SPAWN_PLANE,
                backpack = emptyList(),
                bank = emptyList(),
                varps = emptyMap(),
            )
        }

        fun containerContents(container: ItemContainer): List<SavedItem> =
            container.toArray().withIndex()
                .filter { it.value != null }
                .map { (slot, item) -> SavedItem(slot, item!!.id, item.amount) }

        fun bankContents(bank: Bank): List<SavedItem> =
            bank.contents().map { SavedItem(it.slot, it.id, it.amount) }

        fun metalBankContents(items: List<Bank.BankedItem>): List<SavedItem> =
            items.map { SavedItem(it.slot, it.id, it.amount) }

        fun fromJson(json: String): PlayerSave {
            val root = try {
                JsonParser().parse(json).asJsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("save blob is not a JSON object: ${e.message}", e)
            }
            val format = root.get("format")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: throw IllegalArgumentException("save blob has no 'format' field")
            if (format != FORMAT) {
                throw IllegalArgumentException(
                    "save blob is format $format; this build reads only format $FORMAT"
                )
            }
            val username = root.get("username")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw IllegalArgumentException("save blob has no 'username'")
            val xpObj = root.getAsJsonObject("xp")
                ?: throw IllegalArgumentException("save blob has no 'xp' object")
            val xp = LinkedHashMap<Stat, Double>()
            val missing = ArrayList<String>()
            Stat.values().forEach { stat ->
                val v = xpObj.get(stat.name)
                if (v == null) { missing.add(stat.name); xp[stat] = 0.0 } else xp[stat] = v.asDouble
            }
            if (missing.isNotEmpty()) {
                logger.info {
                    "save for '$username' has no xp for ${missing.joinToString()}; starting at 0"
                }
            }
            val known = Stat.values().map { it.name }.toSet()
            xpObj.entrySet().forEach { (k, _) ->
                if (k !in known) throw IllegalArgumentException(
                    "xp object has unknown stat key '$k'"
                )
            }
            val pos = root.getAsJsonObject("position")
                ?: throw IllegalArgumentException("save blob has no 'position'")
            val backpack = itemsFromJson(root, "backpack")
            val worn = if (root.has("worn")) itemsFromJson(root, "worn") else emptyList()
            val rights = if (root.has("rights")) root.get("rights").asInt else 0
            val bank = itemsFromJson(root, "bank")
            val varpObj = root.getAsJsonObject("varps")
                ?: throw IllegalArgumentException("save blob has no 'varps' object")
            val varps = LinkedHashMap<Int, Int>()
            varpObj.entrySet().forEach { (k, v) ->
                val id = k.toIntOrNull() ?: throw IllegalArgumentException("non-numeric varp id '$k'")
                varps[id] = v.asInt
            }
            val varcs = LinkedHashMap<Int, Any>()
            root.getAsJsonObject("varcs")?.entrySet()?.forEach { (k, v) ->
                val id = k.toIntOrNull() ?: throw IllegalArgumentException("non-numeric varc id '$k'")
                val prim = v.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                    ?: throw IllegalArgumentException("varc $id is not a primitive: $v")
                varcs[id] = when {
                    prim.isString -> prim.asString
                    prim.isNumber -> prim.asLong.let { l -> if (l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) l.toInt() else l }
                    else -> throw IllegalArgumentException("varc $id is neither a number nor a string: $v")
                }
            }
            val cosmetics = LinkedHashMap<Int, Int>()
            root.getAsJsonObject("cosmetics")?.entrySet()?.forEach { (k, v) ->
                val slot = k.toIntOrNull() ?: throw IllegalArgumentException("non-numeric cosmetic slot '$k'")
                val id = v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    ?: throw IllegalArgumentException("cosmetic slot $slot is not an item id: $v")
                require(slot >= 0 && id >= 0) { "cosmetic slot $slot -> item $id: negative" }
                cosmetics[slot] = id
            }
            val toolbelt = ArrayList<Int>()
            root.get("toolbelt")?.let { el ->
                val arr = el.takeIf { it.isJsonArray }?.asJsonArray
                    ?: throw IllegalArgumentException("'toolbelt' is not an array: $el")
                arr.forEach { e ->
                    val prim = e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?: throw IllegalArgumentException("tool belt entry is not an item id: $e")
                    toolbelt += prim.asInt
                }
            }
            val smithing = root.get("smithing")?.let { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("'smithing' is not an object: $el")
                SavedProject(
                    productId = intField(o, "product"),
                    progress = intField(o, "progress"),
                    xpPaidTenths = intField(o, "xpPaidTenths"),
                    heat = intField(o, "heat"),
                    stage = intField(o, "stage"),
                )
            }
            val run = root.get("run")?.let { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("'run' is not an object: $el")
                val on = o.get("on")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    ?: throw IllegalArgumentException("'run.on' is not a boolean: ${o.get("on")}")
                SavedRunEnergy(tenths = intField(o, "tenths"), toggled = on)
            }
            val coinPouch = if (root.has("coinPouch")) intField(root, "coinPouch") else 0
            val bankTabs = ArrayList<Int>()
            root.get("bankTabs")?.let { el ->
                val arr = el.takeIf { it.isJsonArray }?.asJsonArray
                    ?: throw IllegalArgumentException("'bankTabs' is not an array: $el")
                arr.forEach { e ->
                    val prim = e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?: throw IllegalArgumentException("bank tab size is not a number: $e")
                    bankTabs += prim.asInt
                }
            }
            val metalBank = root.get("metalBank")?.let { el ->
                if (!el.isJsonArray) throw IllegalArgumentException("'metalBank' is not an array: $el")
                itemsFromJson(root, "metalBank")
            } ?: emptyList()
            val slayerTask = root.get("slayerTask")?.let { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("'slayerTask' is not an object: $el")
                fun str(name: String): String = o.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?: throw IllegalArgumentException("slayerTask.$name is not a string: ${o.get(name)}")
                SavedSlayerTask(str("monster"), intField(o, "left"), intField(o, "xpTenths"), str("master"))
            }
            return PlayerSave(
                username = username,
                xp = xp,
                x = intField(pos, "x"), y = intField(pos, "y"), plane = intField(pos, "plane"),
                backpack = backpack,
                bank = bank,
                varps = varps,
                worn = worn,
                rights = rights,
                varcs = varcs,
                cosmetics = cosmetics,
                toolbelt = toolbelt,
                smithing = smithing,
                run = run,
                coinPouch = coinPouch,
                bankTabs = bankTabs,
                metalBank = metalBank,
                slayerTask = slayerTask,
            )
        }

        private fun itemsFromJson(root: JsonObject, field: String): List<SavedItem> {
            val arr = root.getAsJsonArray(field)
                ?: throw IllegalArgumentException("save blob has no '$field' array")
            return arr.map { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("'$field' entry is not an object: $el")
                SavedItem(intField(o, "slot"), intField(o, "id"), intField(o, "amount"))
            }
        }

        private fun intField(o: JsonObject, name: String): Int {
            val el = o.get(name) ?: throw IllegalArgumentException("missing int field '$name' in $o")
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber)
                throw IllegalArgumentException("field '$name' is not a number: $el")
            return el.asInt
        }
    }
}
