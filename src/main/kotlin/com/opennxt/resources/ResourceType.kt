package com.opennxt.resources

import com.opennxt.resources.config.enums.EnumDefinition
import com.opennxt.resources.config.params.ParamDefinition
import com.opennxt.resources.config.structs.StructDefinition
import com.opennxt.resources.config.vars.impl.VarClanDefinition
import com.opennxt.resources.config.vars.impl.VarClanSettingDefinition
import com.opennxt.resources.config.vars.impl.VarClientDefinition
import com.opennxt.resources.config.vars.impl.VarNpcDefinition
import com.opennxt.resources.config.vars.impl.VarObjectDefinition
import com.opennxt.resources.config.vars.impl.VarPlayerDefinition
import com.opennxt.resources.config.vars.impl.VarRegionDefinition
import com.opennxt.resources.config.vars.impl.VarWorldDefinition
import com.opennxt.resources.sqlite.AnimGroupDefinition
import com.opennxt.resources.sqlite.ItemDefinition
import com.opennxt.resources.sqlite.LocDefinition
import com.opennxt.resources.sqlite.NpcDefinition
import com.opennxt.resources.sqlite.QuestDefinition
import com.opennxt.resources.sqlite.SequenceDefinition
import com.opennxt.resources.sqlite.SpotAnimDefinition
import com.opennxt.resources.sqlite.VarBitDefinition
import kotlin.reflect.KClass

enum class ResourceType(val identifier: String, val kclass: KClass<*>) {
    ENUM("enum", EnumDefinition::class),
    PARAM("param", ParamDefinition::class),
    STRUCT("struct", StructDefinition::class),

    VAR_PLAYER("varplayer", VarPlayerDefinition::class),
    VAR_NPC("varnpc", VarNpcDefinition::class),
    VAR_CLIENT("varclient", VarClientDefinition::class),
    VAR_WORLD("varworld", VarWorldDefinition::class),
    VAR_REGION("varregion", VarRegionDefinition::class),
    VAR_OBJECT("varobject", VarObjectDefinition::class),
    VAR_CLAN("varclan", VarClanDefinition::class),
    VAR_CLAN_SETTING("varclansetting", VarClanSettingDefinition::class),

    ITEM("item", ItemDefinition::class),
    NPC("npc", NpcDefinition::class),
    LOC("loc", LocDefinition::class),
    VARBIT("varbit", VarBitDefinition::class),

    SEQUENCE("seq", SequenceDefinition::class),
    SPOTANIM("spotanim", SpotAnimDefinition::class),
    ANIMGROUP("animgroup", AnimGroupDefinition::class),
    QUEST("quest", QuestDefinition::class),
    ;

    companion object {
        private val values = values()

        fun getArchive(id: Int, size: Int): Int = id.ushr(size)

        fun getFile(id: Int, size: Int): Int = (id and (1 shl size) - 1)

        fun forClass(kclass: KClass<*>): ResourceType? = values.firstOrNull { it.kclass == kclass }
    }
}
