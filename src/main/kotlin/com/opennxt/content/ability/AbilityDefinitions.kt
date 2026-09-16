package com.opennxt.content.ability

import com.google.gson.JsonParser
import com.opennxt.Constants
import com.opennxt.model.combat.CombatFormulas
import com.opennxt.resources.sqlite.RsDatabase
import mu.KotlinLogging
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AbilityDefinitions {
    private val logger = KotlinLogging.logger { }

    const val GCD_STRUCT = 14881
    const val PARAM_COOLDOWN = 2796
    const val DEFAULT_GCD_TICKS = 3
    const val MAX_HIT_DELAY_TICKS = 100
    const val MAX_HITS_PER_ABILITY = 64
    const val MAX_PERCENT = 10_000.0

    const val AUTO_ADRENALINE_GAIN_TENTHS = 90

    val TARGETED_VALUES: Set<String> = setOf("Single", "Multi", "Area")
    val APPLIED_EFFECT_TAGS: Set<String> = setOf("bleed", "dot")
    val KNOWN_EFFECT_TAGS: Set<String> = setOf("bleed", "dot", "stun", "bind", "heal", "aoe", "buff", "debuff", "dash", "adrenaline", "other")
    val KNOWN_REQUIRES: Set<String> = setOf("2h", "dual", "shield", "mainhand", "target", "no_target")

    enum class Style(val book: String, val barType: Int) {
        MELEE("melee", 1), RANGED("ranged", 3), MAGIC("magic", 4), NECROMANCY("necromancy", 29),
        DEFENCE("defence", 5), CONSTITUTION("constitution", 6);

        val isCombatBook: Boolean get() = this == MELEE || this == RANGED || this == MAGIC || this == NECROMANCY

        companion object {
            fun ofBook(book: String): Style? = values().firstOrNull { it.book == book }
        }
    }

    enum class Category(val seed: String) {
        AUTO("auto"), BASIC("basic"), THRESHOLD("threshold"), ULTIMATE("ultimate"),
        UTILITY("utility"), SPECIAL("special"), SPELL("spell");

        companion object {
            fun ofSeed(value: String): Category? = values().firstOrNull { it.seed == value }
        }
    }

    enum class HitKind { HIT, BLEED, DOT }

    data class HitSpec(val delayTicks: Int, val minPct: Double, val maxPct: Double, val kind: HitKind = HitKind.HIT)

    enum class HitSource { EFFECTS, FALLBACK, NONE }

    enum class EffectsMode { DISK, ABSENT, LINES }

    sealed class Effect {
        abstract val raw: String
        data class Stun(override val raw: String, val ticks: Int) : Effect()
        data class Bind(override val raw: String, val ticks: Int) : Effect()
        data class Debuff(override val raw: String, val name: String, val ticks: Int) : Effect()
        data class Heal(override val raw: String, val percent: Double) : Effect()
        data class Aoe(override val raw: String, val radius: Int) : Effect()
        data class Buff(override val raw: String, val name: String, val ticks: Int) : Effect()
        data class Dash(override val raw: String, val range: Int) : Effect()
        data class Adrenaline(override val raw: String) : Effect()
        data class Necrosis(override val raw: String, val stacks: Int) : Effect()
        data class ResidualSoul(override val raw: String, val souls: Int) : Effect()
        data class StormShard(override val raw: String, val minPct: Double, val maxPct: Double, val max: Int) : Effect()
        data class CostPerNecrosis(override val raw: String, val percentPerStack: Int, val consumes: Int) : Effect()
        data class HitsPerSoul(override val raw: String, val max: Int) : Effect()
        data class ConsumeShards(override val raw: String) : Effect()
        data class Bounces(override val raw: String, val count: Int, val radius: Int) : Effect()
        data class Execute(override val raw: String, val multiplier: Double, val belowFraction: Double) : Effect()
        data class Splash(override val raw: String, val minPct: Double, val maxPct: Double, val max: Int) : Effect()
        data class RicochetMissing(override val raw: String) : Effect()
        data class Decay(override val raw: String, val percent: Int) : Effect()
        data class PeriodicHeal(override val raw: String, val percent: Double, val everyTicks: Int) : Effect()
        data class Described(override val raw: String, val by: String) : Effect()
        data class Unmodelled(override val raw: String) : Effect()
    }

    private val NECROSIS_RE = Regex("""^necrosis_\+(\d+)$""")
    private val SOUL_RE = Regex("""^residual_soul_\+(\d+)$""")
    private val SHARD_RE = Regex("""^storm_shard_stack_(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)_max_(\d+)$""")
    private val COST_NECROSIS_RE = Regex("""^cost_-(\d+)_per_necrosis_consumes_(\d+)$""")
    private val HITS_SOULS_RE = Regex("""^hits_equal_residual_souls_max_(\d+)$""")
    private val BOUNCES_RE = Regex("""^bounces_(\d+)_within_(\d+)$""")
    private val EXECUTE_RE = Regex("""^x(\d+(?:\.\d+)?)_below_(\d+)pct_lifepoints$""")
    private val CONE_RE = Regex("""^cone_(\d+)$""")
    private val SPLASH_RE = Regex("""^splash_(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)_up_to_(\d+)$""")
    private val DECAY_RE = Regex("""^decay_(\d+)pct_of_initial_per_hit$""")
    private val BLOAT_RE = Regex("""^bloat_(\d+)pct_of_initial_per_hit$""")
    private val PERIODIC_HEAL_RE = Regex("""^heal_(\d+(?:\.\d+)?)pct_lifepoints_(?:per_tick|every_(\d+))$""")

    val DESCRIBED_BY_BUFF: Set<String> = setOf(
        "melee_damage_x1.75", "damage_taken_x1.25", "ranged_damage_x1.5", "magic_damage_x1.5_inside_area",
        "damage_taken_-10", "stun_immune", "damage_taken_-50_reflect_100", "damage_immune", "damage_taken_-25",
        "adrenaline_gain_x2", "clears_stuns_binds_dots", "damage_from_target_-50"
    )

    fun parseEffect(tag: String): Effect {
        val t = tag.trim()
        val kind = t.substringBefore(':').trim().lowercase()
        val rest = t.substringAfter(':', "").trim()
        fun int(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it >= 0 }
        return when (kind) {
            "stun" -> int(rest)?.let { Effect.Stun(t, it) } ?: Effect.Unmodelled(t)
            "bind" -> int(rest)?.let { Effect.Bind(t, it) } ?: Effect.Unmodelled(t)
            "heal" -> number(rest)?.takeIf { it >= 0 }?.let { Effect.Heal(t, it) } ?: Effect.Unmodelled(t)
            "aoe" -> int(rest)?.let { Effect.Aoe(t, it) } ?: Effect.Unmodelled(t)
            "dash" -> int(rest)?.let { Effect.Dash(t, it) } ?: Effect.Unmodelled(t)
            "buff", "debuff" -> {
                val name = rest.substringBefore(':').trim().lowercase()
                val ticks = int(rest.substringAfter(':', ""))
                if (name.isEmpty() || ticks == null) Effect.Unmodelled(t)
                else if (kind == "buff") Effect.Buff(t, name, ticks) else Effect.Debuff(t, name, ticks)
            }
            "adrenaline" -> Effect.Adrenaline(t)
            "other" -> parseOther(t, rest.lowercase())
            else -> Effect.Unmodelled(t)
        }
    }

    private fun parseOther(raw: String, d: String): Effect {
        NECROSIS_RE.matchEntire(d)?.let { return Effect.Necrosis(raw, it.groupValues[1].toInt()) }
        SOUL_RE.matchEntire(d)?.let { return Effect.ResidualSoul(raw, it.groupValues[1].toInt()) }
        SHARD_RE.matchEntire(d)?.let { return Effect.StormShard(raw, it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toInt()) }
        COST_NECROSIS_RE.matchEntire(d)?.let { return Effect.CostPerNecrosis(raw, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        HITS_SOULS_RE.matchEntire(d)?.let { return Effect.HitsPerSoul(raw, it.groupValues[1].toInt()) }
        if (d == "consumes_storm_shards_deals_stored") return Effect.ConsumeShards(raw)
        BOUNCES_RE.matchEntire(d)?.let { return Effect.Bounces(raw, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        EXECUTE_RE.matchEntire(d)?.let { return Effect.Execute(raw, it.groupValues[1].toDouble(), it.groupValues[2].toInt() / 100.0) }
        CONE_RE.matchEntire(d)?.let { return Effect.Aoe(raw, it.groupValues[1].toInt()) }
        SPLASH_RE.matchEntire(d)?.let { return Effect.Splash(raw, it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toInt()) }
        if (d == "extra_hits_only_for_missing_secondary_targets") return Effect.RicochetMissing(raw)
        DECAY_RE.matchEntire(d)?.let { return Effect.Decay(raw, it.groupValues[1].toInt()) }
        BLOAT_RE.matchEntire(d)?.let { return Effect.Described(raw, "the row's bleed band already is ${it.groupValues[1]}% of the initial hit's band") }
        PERIODIC_HEAL_RE.matchEntire(d)?.let { return Effect.PeriodicHeal(raw, it.groupValues[1].toDouble(), it.groupValues[2].toIntOrNull() ?: 1) }
        if (d in DESCRIBED_BY_BUFF) return Effect.Described(raw, "the named buff/debuff on this row (StatusEffects.BUFF_TABLE)")
        return Effect.Unmodelled(raw)
    }

    val AUTO_BAND = HitSpec(0, CombatFormulas.PLAYER_AUTO_ATTACK_FLOOR_PERCENT.toDouble(), 100.0)

    val FALLBACK_BANDS: Map<Category, HitSpec> = mapOf(
        Category.AUTO to AUTO_BAND,
        Category.BASIC to HitSpec(0, 90.0, 110.0),
        Category.THRESHOLD to HitSpec(0, 130.0, 150.0),
        Category.ULTIMATE to HitSpec(0, 250.0, 300.0),
    )

    data class EffectsRow(
        val structId: Int,
        val name: String,
        val adrenalineCostPct: Int?,
        val adrenalineGainPct: Int?,
        val cooldownTicks: Int?,
        val hitsRaw: String,
        val hits: List<HitSpec>?,
        val damageOverTime: List<HitSpec>?,
        val channelTicks: Int?,
        val effects: List<String>,
        val typed: List<Effect>,
        val unapplied: List<String>,
        val requires: List<String>,
        val source: String,
        val tier: String
    )

    data class Definition(
        val structId: Int,
        val name: String,
        val style: Style,
        val bookEnum: Int,
        val slot: Int,
        val category: Category?,
        val level: Int?,
        val adrenalineTenths: Int?,
        val cooldownTicks: Int,
        val animation: Int?,
        val animEnum: Int?,
        val animDefault: Int?,
        val animByWeaponType: Map<Int, Int>,
        val refTarget: String?,
        val effects: EffectsRow?,
        val hits: List<HitSpec>,
        val hitSource: HitSource,
        val notModelledWhy: String?
    ) {
        val adrenalineCost: Int get() = adrenalineTenths?.takeIf { it < 0 }?.let { -it } ?: 0
        val adrenalineGain: Int get() = adrenalineTenths?.takeIf { it > 0 } ?: 0
        val modelled: Boolean get() = hits.isNotEmpty()
        val requires: List<String> get() = effects?.requires.orEmpty()
        val unappliedEffects: List<String> get() = effects?.unapplied.orEmpty()

        val typedEffects: List<Effect> get() = effects?.typed.orEmpty()
        val channelTicks: Int get() = (effects?.channelTicks ?: 0).coerceAtLeast(0)
        val selfEffects: List<Effect> get() = typedEffects.filter {
            it is Effect.Buff || it is Effect.Dash || it is Effect.Necrosis || it is Effect.ResidualSoul || it is Effect.StormShard ||
                it is Effect.CostPerNecrosis || it is Effect.HitsPerSoul || it is Effect.ConsumeShards || it is Effect.PeriodicHeal
        }
        val targetEffects: List<Effect> get() = typedEffects.filter {
            it is Effect.Stun || it is Effect.Bind || it is Effect.Debuff || it is Effect.Heal || it is Effect.Aoe ||
                it is Effect.Bounces || it is Effect.Splash || it is Effect.RicochetMissing || it is Effect.Execute || it is Effect.Decay
        }
        val selfOnly: Boolean get() = hits.isEmpty() && effects != null && selfEffects.isNotEmpty()

        fun animationFor(weaponTypeStruct: Int?): Int? =
            animation ?: weaponTypeStruct?.let { animByWeaponType[it] } ?: animDefault
    }

    data class Loaded(
        val byStruct: Map<Int, Definition>,
        val gcdTicks: Int,
        val gcdSource: String,
        val mode: EffectsMode,
        val effectsPath: Path,
        val effectsActive: Boolean,
        val effectsRows: Int,
        val unknownEffectTags: Set<String>,
        val unknownRequires: Set<String>,
        val problems: List<String>
    ) {
        fun count(source: HitSource, style: Style? = null): Int =
            byStruct.values.count { it.hitSource == source && (style == null || it.style == style) }
    }

    @Volatile private var mode: EffectsMode = EffectsMode.DISK
    @Volatile private var checkLines: List<String> = emptyList()
    @Volatile private var loaded: Loaded? = null

    fun current(): Loaded = loaded ?: synchronized(this) { loaded ?: load().also { loaded = it } }
    fun reload(): Loaded = synchronized(this) { load().also { loaded = it } }
    fun forStruct(structId: Int): Definition? = current().byStruct[structId]
    val gcdTicks: Int get() = current().gcdTicks

    private fun seedDir(): Path =
        System.getProperty("opennxt.seed.dir")?.let { Paths.get(it) } ?: Constants.DATA_PATH.resolve("seed")

    fun seedPath(): Path = seedDir().resolve("abilities_950.tsv")
    fun effectsPath(): Path = seedDir().resolve("ability_effects_950.tsv")
    fun infoPath(): Path = Constants.DATA_PATH.resolve("seed").resolve("ability_info_950.jsonl")

    private fun load(): Loaded {
        val problems = ArrayList<String>()
        val seed = readTsv(seedPath(), problems)
        val effectsFile = effectsPath()
        val m = mode
        val effectsLines: List<String>? = when (m) {
            EffectsMode.LINES -> checkLines
            EffectsMode.ABSENT -> null
            EffectsMode.DISK -> if (Files.isRegularFile(effectsFile)) Files.readAllLines(effectsFile, StandardCharsets.UTF_8) else null
        }
        val unknownTags = LinkedHashSet<String>()
        val unknownRequires = LinkedHashSet<String>()
        val effects = if (effectsLines == null) emptyMap() else parseEffects(effectsLines, problems, unknownTags, unknownRequires)
        val active = effectsLines != null
        val targets = readTargets(problems)
        val animEnums = loadAnimEnums(seed.mapNotNull { it["anim_enum"]?.toIntOrNull() }.toSet(), problems)
        val (gcd, gcdSource) = loadGcd()

        val out = LinkedHashMap<Int, Definition>()
        for (r in seed) {
            val sid = r["struct_id"]?.toIntOrNull() ?: continue
            val style = Style.ofBook(r["book"].orEmpty()) ?: continue
            if (out.containsKey(sid)) continue
            val category = Category.ofSeed(r["category"].orEmpty())
            val e = effects[sid]
            val adrenaline = r["adrenaline_tenths"]?.toIntOrNull()
                ?: e?.adrenalineCostPct?.takeIf { it > 0 }?.let { -it * 10 }
                ?: e?.adrenalineGainPct?.takeIf { it > 0 }?.let { it * 10 }
                ?: if (category == Category.AUTO) AUTO_ADRENALINE_GAIN_TENTHS else null
            val cooldown = r["cooldown_ticks"]?.toIntOrNull() ?: e?.cooldownTicks ?: 0
            val animEnum = r["anim_enum"]?.toIntOrNull()
            val target = targets[sid]

            var why: String? = null
            fun none(reason: String): Pair<List<HitSpec>, HitSource> { why = reason; return emptyList<HitSpec>() to HitSource.NONE }
            val (hits, source) = when {
                e != null -> {
                    val scheduled = e.hits
                    val overTime = e.damageOverTime
                    when {
                        scheduled == null -> none("unparseable hits column: '${e.hitsRaw}'")
                        overTime == null -> none("unparseable bleed/dot tag: ${e.effects}")
                        category == Category.AUTO -> listOf(AUTO_BAND) to HitSource.EFFECTS
                        scheduled.isEmpty() && overTime.isEmpty() ->
                            none("no damage (buff or utility): ${e.effects}; " +
                                "self effects ${e.typed.filter { it !is Effect.Unmodelled && it !is Effect.Adrenaline && it !is Effect.Described }.map { it.raw }}; " +
                                "not applied: ${e.unapplied}")
                        else -> (scheduled + overTime).sortedBy { it.delayTicks } to HitSource.EFFECTS
                    }
                }
                active -> none("no row in ability_effects_950.tsv")
                !style.isCombatBook -> none("${style.book} book is self-targeted and there is no effects file")
                category == null || category !in FALLBACK_BANDS -> none("category '${r["category"]}' has no fallback hit and there is no effects file")
                category == Category.AUTO || target in TARGETED_VALUES -> listOf(FALLBACK_BANDS.getValue(category)) to HitSource.FALLBACK
                else -> none("target is '${target ?: "absent"}', not an enemy, and there is no effects file")
            }
            out[sid] = Definition(
                structId = sid,
                name = r["name"] ?: "?",
                style = style,
                bookEnum = r["book_enum"]?.toIntOrNull() ?: -1,
                slot = r["slot"]?.toIntOrNull() ?: -1,
                category = category,
                level = r["level"]?.toIntOrNull(),
                adrenalineTenths = adrenaline,
                cooldownTicks = cooldown.coerceAtLeast(0),
                animation = r["animation"]?.toIntOrNull(),
                animEnum = animEnum,
                animDefault = r["anim_default"]?.toIntOrNull(),
                animByWeaponType = animEnum?.let { animEnums[it] }.orEmpty(),
                refTarget = target,
                effects = e,
                hits = hits,
                hitSource = source,
                notModelledWhy = why
            )
        }
        val result = Loaded(out, gcd, gcdSource, m, effectsFile, active, effects.size, unknownTags, unknownRequires, problems)
        logger.info {
            "abilities: ${out.size} definitions from ${seedPath()}; effects ${when (m) { EffectsMode.LINES -> "from supplied lines"; EffectsMode.ABSENT -> "absent"; EffectsMode.DISK -> if (active) "from $effectsFile" else "missing at $effectsFile" }} (${effects.size} rows); " +
                "modelled from effects ${result.count(HitSource.EFFECTS)}, fallback ${result.count(HitSource.FALLBACK)}, not modelled ${result.count(HitSource.NONE)}; GCD $gcd ticks ($gcdSource)"
        }
        if (unknownTags.isNotEmpty()) logger.warn { "abilities: ignoring unrecognised effect tags: $unknownTags" }
        if (unknownRequires.isNotEmpty()) logger.warn { "abilities: ignoring unrecognised requirement tags (treated as met): $unknownRequires" }
        for (p in problems) logger.warn { "abilities: $p" }
        return result
    }

    private fun readTsv(path: Path, problems: MutableList<String>): List<Map<String, String>> {
        if (!Files.isRegularFile(path)) {
            problems += "$path is MISSING - no ability can be defined"
            return emptyList()
        }
        val body = Files.readAllLines(path, StandardCharsets.UTF_8).filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = body.firstOrNull()?.split('\t') ?: return emptyList()
        return body.drop(1).map { line ->
            val f = line.split('\t')
            header.indices.associate { i -> header[i] to (f.getOrNull(i) ?: "") }
        }
    }

    private fun number(raw: String): Double? = raw.trim().removeSuffix("%").trim().toDoubleOrNull()

    private fun band(raw: String): Pair<Double, Double>? {
        val s = raw.trim()
        val dash = s.indexOf('-', 1)
        val lo = number(if (dash < 0) s else s.substring(0, dash)) ?: return null
        val hi = if (dash < 0) lo else number(s.substring(dash + 1)) ?: return null
        if (lo < 0 || hi < lo || hi > MAX_PERCENT) return null
        return lo to hi
    }

    fun parseHits(raw: String): List<HitSpec>? {
        val s = raw.trim()
        if (s.isEmpty() || s == "-") return emptyList()
        val parts = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_HITS_PER_ABILITY) return null
        val out = ArrayList<HitSpec>()
        for (p in parts) {
            val colon = p.indexOf(':')
            if (colon <= 0) return null
            val delay = p.substring(0, colon).trim().toIntOrNull() ?: return null
            if (delay !in 0..MAX_HIT_DELAY_TICKS) return null
            val (lo, hi) = band(p.substring(colon + 1)) ?: return null
            out += HitSpec(delay, lo, hi)
        }
        return out.sortedBy { it.delayTicks }
    }

    private val OVER_TIME_RE = Regex("""^(bleed|dot):(\d+)x(\d+):(.+)$""")

    fun parseOverTime(tag: String): List<HitSpec>? {
        val m = OVER_TIME_RE.matchEntire(tag.trim()) ?: return null
        val n = m.groupValues[2].toIntOrNull() ?: return null
        val t = m.groupValues[3].toIntOrNull() ?: return null
        val (lo, hi) = band(m.groupValues[4]) ?: return null
        if (n !in 1..MAX_HITS_PER_ABILITY || t < 1 || (n - 1) * t > MAX_HIT_DELAY_TICKS) return null
        val kind = if (m.groupValues[1] == "bleed") HitKind.BLEED else HitKind.DOT
        return (0 until n).map { HitSpec(it * t, lo, hi, kind) }
    }

    private fun list(raw: String): List<String> =
        if (raw.isEmpty() || raw == "-") emptyList() else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "-" }

    fun parseEffects(
        lines: List<String>,
        problems: MutableList<String>,
        unknownTags: MutableSet<String>,
        unknownRequires: MutableSet<String>
    ): Map<Int, EffectsRow> {
        val body = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        val header = body.firstOrNull()?.split('\t')?.map { it.trim() } ?: return emptyMap()
        val idx = header.withIndex().associate { it.value to it.index }
        if (idx["struct_id"] == null || idx["hits"] == null) {
            problems += "effects: header has no struct_id/hits column - file ignored"
            return emptyMap()
        }
        fun col(f: List<String>, name: String): String = idx[name]?.let { f.getOrNull(it)?.trim() }.orEmpty()
        val out = LinkedHashMap<Int, EffectsRow>()
        for (line in body.drop(1)) {
            val f = line.split('\t')
            val sid = col(f, "struct_id").toIntOrNull()
            if (sid == null) { problems += "effects: no struct id in '${line.take(60)}'"; continue }
            if (out.containsKey(sid)) { problems += "effects: struct $sid listed twice - the first row is kept"; continue }
            val hitsRaw = col(f, "hits")
            val hits = parseHits(hitsRaw)
            if (hits == null) problems += "effects: struct $sid hits '$hitsRaw' is malformed - that ability is not modelled"
            val effects = list(col(f, "effects"))
            val overTime = ArrayList<HitSpec>()
            var overTimeBad = false
            val unapplied = ArrayList<String>()
            val typed = ArrayList<Effect>()
            for (tag in effects) {
                val t = tag.substringBefore(':').trim().lowercase()
                if (t !in KNOWN_EFFECT_TAGS) unknownTags += t
                if (t in APPLIED_EFFECT_TAGS) {
                    val expanded = parseOverTime(tag)
                    if (expanded == null) { overTimeBad = true; problems += "effects: struct $sid tag '$tag' is malformed - that ability is not modelled" }
                    else overTime += expanded
                } else {
                    val e = parseEffect(tag)
                    typed += e
                    if (e is Effect.Unmodelled || e is Effect.Adrenaline) unapplied += tag
                }
            }
            val requires = list(col(f, "requires")).map { it.lowercase() }
            for (q in requires) if (q !in KNOWN_REQUIRES) unknownRequires += q
            if (hits != null && hits.size + overTime.size > MAX_HITS_PER_ABILITY) {
                overTimeBad = true
                problems += "effects: struct $sid schedules ${hits.size + overTime.size} > $MAX_HITS_PER_ABILITY damage instances - not modelled"
            }
            out[sid] = EffectsRow(
                structId = sid,
                name = col(f, "name"),
                adrenalineCostPct = number(col(f, "adrenaline_cost_pct"))?.let { Math.abs(Math.round(it).toInt()) },
                adrenalineGainPct = number(col(f, "adrenaline_gain_pct"))?.let { Math.abs(Math.round(it).toInt()) },
                cooldownTicks = col(f, "cooldown_ticks").toIntOrNull(),
                hitsRaw = hitsRaw,
                hits = hits,
                damageOverTime = if (overTimeBad) null else overTime,
                channelTicks = col(f, "channel_ticks").toIntOrNull(),
                effects = effects,
                typed = typed,
                unapplied = unapplied,
                requires = requires,
                source = col(f, "source"),
                tier = col(f, "tier")
            )
        }
        return out
    }

    private val TARGET_RE = Regex("""\|\s*target\s*=\s*([^\n|}]*)""")

    private fun readTargets(problems: MutableList<String>): Map<Int, String> {
        val path = infoPath()
        if (!Files.isRegularFile(path)) {
            problems += "$path is missing; no fallback hits available"
            return emptyMap()
        }
        val out = HashMap<Int, String>()
        for (line in Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue
            val o = runCatching { JsonParser().parse(line).asJsonObject }.getOrNull() ?: continue
            val w = o.get("text")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val t = TARGET_RE.find(w)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val structs = o.get("structs")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (s in structs) runCatching { out[s.asInt] = t }
        }
        return out
    }

    private fun loadAnimEnums(ids: Set<Int>, problems: MutableList<String>): Map<Int, Map<Int, Int>> {
        if (ids.isEmpty() || !RsDatabase.available) return emptyMap()
        val out = HashMap<Int, Map<Int, Int>>()
        runCatching {
            val rows = RsDatabase.queryAll(
                "SELECT id, value FROM enums_attr WHERE field = 'intArrayValue1' AND id IN (${ids.joinToString(",")})"
            ) { it.getInt("id") to it.getString("value") }
            for ((id, raw) in rows) {
                val m = HashMap<Int, Int>()
                runCatching { for (pair in JsonParser().parse(raw).asJsonArray) { val a = pair.asJsonArray; m[a[0].asInt] = a[1].asInt } }
                out[id] = m
            }
        }.onFailure { problems += "animation enums not read: ${it.message}" }
        return out
    }

    private fun loadGcd(): Pair<Int, String> {
        if (!RsDatabase.available) return DEFAULT_GCD_TICKS to "default, rs3.sqlite absent"
        val v = runCatching {
            RsDatabase.queryAll("SELECT intvalue FROM struct_param WHERE struct_id = $GCD_STRUCT AND prop = $PARAM_COOLDOWN") { it.getInt(1) }.firstOrNull()
        }.getOrNull()
        return if (v != null && v > 0) v to "struct $GCD_STRUCT param $PARAM_COOLDOWN" else DEFAULT_GCD_TICKS to "default, struct $GCD_STRUCT param $PARAM_COOLDOWN absent"
    }
}
