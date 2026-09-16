package com.opennxt.content.interfaces

import com.opennxt.OpenNXT
import com.opennxt.resources.config.enums.EnumDefinition
import com.opennxt.resources.config.structs.StructDefinition

enum class InterfaceSlot(val id: Int) {
    SKILLS(0),
    BACKPACK(2),
    WORN_EQUIPMENT(3),
    PRAYER_ABILITIES(4),
    MAGIC_BOOK(5),
    MELEE_ABILITIES(6),
    RANGED_ABILITIES(7),
    DEFENSIVE_ABILITIES(8),
    EMOTES(9),
    MUSIC_PLAYER(10),
    NOTES(11),
    FAMILIAR(12),
    FRIENDS_LIST(14),
    FRIENDS_CHAT_LIST(15),
    CLAN_CHAT_LIST(16),
    MINIGAMES(17),
    ALL_CHAT(18),
    PRIVATE_CHAT(19),
    FRIENDS_CHAT(20),
    CLAN_CHAT(21),
    GUEST_CLAN_CHAT(22),
    TRADE_AND_ASSISTANCE(23),
    TWITCH_CHAT(24),
    GROUP_CHAT(25),
    TWITCH_STREAM(26),
    GROUP_CHAT_LIST(27),
    METRICS(28),
    DROPS(29),
    GRAPHS(30),
    QUEST_LIST(31),
    ACHIEVEMENT_TRACKER(32),
    MAGIC_ABILITIES(33),
    COMBAT_SPELLS(34),
    TELEPORT_SPELLS(35),
    SKILLING_SPELLS(36),
    ATTACK_ABILITIES(37),
    STRENGTH_ABILITIES(38),
    DEFENCE_ABILITIES(39),
    CONSTITUTION_ABILITIES(40),
    ACHIEVEMENT_PATHS(41),
    GAME_VIEW(1000),
    BUTTONS(1002),
    ACTION_BAR(1003),
    MINIMAP(1004),
    MINIGAME_HUD(1005),
    GAME_DIALOG(1006),
    CENTRAL_INTERFACE(1007),
    BUFF_BAR(1009),
    GRAVE_TIMER(1010),
    TASK_COMPLETE(1012),
    GRAVE_INTERFACE(1013),
    AREA_STATUS(1014),
    XP_TRACKER(1015),
    SUBSCRIBE(1016),
    BANK(1017),
    CRAFTING_PROGRESS(1018),
    SPLIT_PRIVATE_CHAT(1019),
    BOSS_TIMER(1021),
    GROUP_INVITATIONS(1023),
    PLAYER_INSPECT(1024),
    CLOCK(1025),
    XP_POPUPS(1026),
    JMOD_TOOLBOX(1027),
    LOOT(1028),
    DEBUG_TEXT(1029),
    CHALLENGE_GEM(1030),
    SLAYER_COUNTER(1031),
    SECONDARY_ACTION_BAR(1032),
    TERTIARY_ACTION_BAR(1033),
    QUATERNARY_ACTION_BAR(1034),
    QUINARY_ACTION_BAR(1035),
    BXP_COUNTDOWN(1036),
    EVENTS(1037),
    DEBUFF_BAR(1038),
    CENTRAL_OVERLAY_INTERFACE(1040),
    DUNGEONEERING_MAP(1041),
    COMBAT_INFORMATION(1042),
    MOBILE_ACTION_BAR(1043),
    MOBILE_REVO_BAR(1044),
    EXTRA_ACTION_BUTTON(1045),
    DAY_PLANNER(1046),
    CENTRAL_INTERFACE_LARGE(1047),
    INVENTORY_DRAG_OPTIONS(1048),
    EDIT_MODE(2000),
    PANEL_2001(2001),
    PANEL_2002(2002),
    PANEL_2003(2003),
    PANEL_2004(2004),
    PANEL_2005(2005),
    COMBAT_TARGET(2008),
    ;

    var parent = -1

    var component = -1

    lateinit var struct: StructDefinition

    var mountParam = -1

    companion object {
        const val DOCKED_MARKER = 3509

        const val MOUNT_DOCKED = 3505

        const val MOUNT_HUD = 3503

        const val PANEL_ENUM = 7716

        val VALUES = values()

        fun mountParamFor(struct: StructDefinition): Int =
            if (struct.values.containsKey(DOCKED_MARKER)) MOUNT_DOCKED else MOUNT_HUD

        data class Reload(val mapped: Int, val unmapped: List<InterfaceSlot>)

        fun reload(): Reload {
            val enum = OpenNXT.resources.get<EnumDefinition>(PANEL_ENUM) ?: throw NullPointerException("InterfaceSlot enum not found")

            var mapped = 0
            val unmapped = ArrayList<InterfaceSlot>()
            VALUES.forEach { slot ->
                val structId = enum.values[slot.id] as? Int
                if (structId == null) {
                    slot.mountParam = -1
                    slot.parent = -1
                    slot.component = -1
                    unmapped += slot
                    return@forEach
                }
                val struct = OpenNXT.resources.get<StructDefinition>(structId) ?: throw NullPointerException("InterfaceSlot illegal struct: $structId in slot $slot")
                val param = mountParamFor(struct)
                val hash = struct.getInt(param, -1)

                slot.struct = struct
                slot.mountParam = param
                slot.parent = if (hash == -1) -1 else hash shr 16
                slot.component = if (hash == -1) -1 else hash and 0xffff
                mapped++
            }
            return Reload(mapped, unmapped)
        }
    }
}
