package com.opennxt.model.world

import com.opennxt.OpenNXT
import com.opennxt.api.stat.Stat
import com.opennxt.api.stat.StatContainer
import com.opennxt.content.ContentPlayer
import com.opennxt.content.impl.Banks
import com.opennxt.content.interfaces.InterfaceSlot
import com.opennxt.model.map.LocClipping
import com.opennxt.impl.stat.PlayerStatContainer
import com.opennxt.model.account.AccountStore
import com.opennxt.model.account.PlayerSave
import com.opennxt.model.entity.BasePlayer
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.entity.movement.CompassPoint
import com.opennxt.model.entity.player.InterfaceManager
import com.opennxt.model.entity.player.PlayerInventory
import com.opennxt.model.entity.player.Viewport
import com.opennxt.model.entity.updating.NpcInfoEncoder
import com.opennxt.model.entity.updating.PlayerInfoEncoder
import com.opennxt.model.combat.Lifepoints
import com.opennxt.model.lobby.TODORefactorThisClass
import com.opennxt.net.ConnectedClient
import com.opennxt.net.GenericResponse
import com.opennxt.net.Side
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.PacketRegistry
import com.opennxt.net.game.pipeline.*
import com.opennxt.net.game.serverprot.RebuildNormal
import com.opennxt.net.game.serverprot.RunClientScript
import com.opennxt.net.game.serverprot.ServerTickEnd
import com.opennxt.net.game.serverprot.variables.ClientSetvarcLarge
import com.opennxt.net.game.serverprot.variables.ClientSetvarcSmall
import com.opennxt.net.game.serverprot.variables.ResetClientVarcache
import com.opennxt.net.login.LoginPacket
import com.opennxt.net.login.LoginServerHandler
import com.opennxt.net.proxy.UnidentifiedPacket
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import mu.KotlinLogging
import kotlin.reflect.KClass
import com.opennxt.net.game.serverprot.variables.VarpLarge

class WorldPlayer(
    client: ConnectedClient,
    name: String,
    val entity: PlayerEntity,
    initialXp: Map<Stat, Double>? = null,
    loadedSave: PlayerSave? = null
) : BasePlayer(client, name), CombatDefender {
    val save: PlayerSave = loadedSave ?: AccountStore.instance.loadSave(name) ?: PlayerSave.fromNew(name)

    private val varpOverrides: LinkedHashMap<Int, Int> = LinkedHashMap(save.varps)

    private val cosmetics: LinkedHashMap<Int, Int> = LinkedHashMap(save.cosmetics)

    private var coinPouchAmount: Int = save.coinPouch

    init {
        com.opennxt.content.skills.SkillInteractions.restoreSlayerTask(save.username,
            com.opennxt.content.skills.SkillInteractions.fromSaved(save.slayerTask))
    }
    fun coinPouch(): Int = coinPouchAmount
    fun setCoinPouch(amount: Int) {
        require(amount >= 0) { "coin pouch must not be negative: $amount" }
        coinPouchAmount = amount
    }
    fun cosmeticFor(slot: Int): Int? = cosmetics[slot]
    fun cosmeticsSnapshot(): Map<Int, Int> = cosmetics.toMap()
    fun setCosmetic(slot: Int, itemId: Int?): Int? {
        val previous = if (itemId == null) cosmetics.remove(slot) else cosmetics.put(slot, itemId)
        entity.model.dirty = true
        return previous
    }
    fun clearCosmetics(): Int { val n = cosmetics.size; cosmetics.clear(); entity.model.dirty = true; return n }

    private val toolbelt: LinkedHashSet<Int> = LinkedHashSet(save.toolbelt)

    fun toolbeltIds(): Set<Int> = toolbelt.toSet()

    fun toolbeltHolds(itemId: Int): Boolean = itemId in toolbelt

    fun addToToolbelt(itemId: Int): Boolean = toolbelt.add(itemId)

    fun varpValue(id: Int): Int =
        varpOverrides[id]
            ?: com.opennxt.model.lobby.TODORefactorThisClass.fileVarpTable?.get(id)
            ?: com.opennxt.content.impl.LoginVarps.table.firstOrNull { it.first == id }?.second ?: 0

    fun varpOverride(id: Int): Int? = varpOverrides[id]

    private val lastTickByKey = HashMap<String, Long>()

    internal var tickOffsetForCheck: Long = 0

    fun allowOncePerTick(key: String): Boolean {
        val now = OpenNXT.world.currentTick + tickOffsetForCheck
        if (lastTickByKey[key] == now) return false
        lastTickByKey[key] = now
        return true
    }

    @Volatile
    var backpackResendOwed: Boolean = false

    fun flushOwedBackpack(): Boolean {
        if (!backpackResendOwed) return false
        backpackResendOwed = false
        PlayerInventory.sendBackpack(this)
        return true
    }

    private var lastPanelToggleTick: Long = -1

    fun allowPanelToggleThisTick(): Boolean {
        val now = OpenNXT.world.currentTick
        if (now == lastPanelToggleTick) return false
        lastPanelToggleTick = now
        return true
    }

    fun setVarpOverride(id: Int, value: Int, store: Boolean = true) {
        if (store) varpOverrides[id] = value
        if (!client.channel.isActive) return
        if (!TODORefactorThisClass.varpIsDefined(id)) {
            logger.warn("setVarpOverride: varp $id is not defined in the cache; stored but not sent")
            return
        }
        client.write(VarpLarge(id, value))
    }

    init {
        entity.controllingPlayer = this
        Banks.restoreBank(name, save.bankItems(), save.bankTabs)
        com.opennxt.content.impl.MetalBanks.restore(name, save.metalBankItems())

        save.run?.let { entity.runEnergy.restore(it.tenths, it.toggled) }
    }

    val contentPlayer: ContentPlayer by lazy {
        ContentPlayer(name, TileLocation(entity.location.x, entity.location.y, entity.location.plane))
    }

    fun contentPlayerAt(): ContentPlayer {
        val l = entity.location
        contentPlayer.location = TileLocation(l.x, l.y, l.plane)
        return contentPlayer
    }

    private var smithingRestoreRefused = false

    private var locClippingWarned = false

    private val handlers =
        Object2ObjectOpenHashMap<KClass<out GamePacket>, GamePacketHandler<in BasePlayer, out GamePacket>>()
    private val logger = KotlinLogging.logger { }

    init {
        val stored = save.smithing
        if (stored != null) {
            val ok = runCatching {
                com.opennxt.content.impl.Smithing.restoreProject(
                    contentPlayer,
                    stored.productId, stored.progress, stored.xpPaidTenths, stored.heat, stored.stage
                )
            }.onFailure {
                logger.warn(it) { "could not restore $name's smithing project (product ${stored.productId})" }
            }.getOrDefault(false)
            smithingRestoreRefused = !ok
        }
    }

    val viewport = Viewport(this)
    override val interfaces: InterfaceManager = InterfaceManager(this)
    override val stats: StatContainer = PlayerStatContainer(this, initialXp)

    override val maxLifepoints: Int
        get() = com.opennxt.model.combat.Lifepoints.forConstitutionLevel(stats.getLevel(Stat.CONSTITUTION))

    override var currentLifepoints: Int = 0
        private set

    init {
        currentLifepoints = maxLifepoints
    }

    override fun takeDamage(amount: Int): PlayerDeath? {
        if (amount <= 0) return null
        currentLifepoints = (currentLifepoints - amount).coerceAtLeast(0)
        sendLifepoints()
        runCatching { com.opennxt.content.ability.CombatVitals.onDamageTaken(this, amount, currentLifepoints <= 0) }
            .onFailure { logger.warn(it) { "vitals hook failed for $name; the damage stands" } }
        if (currentLifepoints > 0) return null
        runCatching { com.opennxt.content.impl.PrayerBook.onDeath(this) }
            .onFailure { logger.warn(it) { "prayers: death hook failed for $name; the death stands" } }

        val loc = entity.location
        val diedAt = TileLocation(loc.x, loc.y, loc.plane)
        val respawn = TileLocation(
            HeadlessPlayer.LUMBRIDGE_RESPAWN_X,
            HeadlessPlayer.LUMBRIDGE_RESPAWN_Y,
            HeadlessPlayer.LUMBRIDGE_RESPAWN_PLANE
        )
        entity.movement.teleport(respawn)
        currentLifepoints = maxLifepoints
        sendLifepoints()
        return PlayerDeath(
            player = name,
            diedAt = diedAt,
            respawnedAt = respawn,
            lifepointsRestored = currentLifepoints
        )
    }

    override fun level(stat: Stat): Int = stats.getLevel(stat)

    override fun combatLoadout(): CombatLoadout = PlayerCombatStats.loadoutOf(this)

    fun healToFull() {
        currentLifepoints = maxLifepoints
        sendLifepoints()
    }

    fun heal(amount: Int): Int {
        if (amount <= 0) return 0
        val before = currentLifepoints
        val max = maxLifepoints
        if (before >= max) return 0
        currentLifepoints = (before + amount).coerceAtMost(max)
        sendLifepoints()
        return currentLifepoints - before
    }

    fun sendLifepoints() {
        if (System.getProperty("opennxt.experiment.ui.lifepoints") == "false") return
        if (!client.channel.isActive) return
        val id = Lifepoints.CURRENT_LIFEPOINTS_VARP
        if (!TODORefactorThisClass.varpIsDefined(id)) {
            logger.warn("ui.lifepoints: varp $id is not defined in the cache; not sent")
            return
        }
        client.write(VarpLarge(id, currentLifepoints))
    }

    val worn: com.opennxt.model.items.ItemContainer
        get() = PlayerInventory.wornOf(this)

    private val modProfile: com.opennxt.model.permissions.ModProfiles.Profile? by lazy {
        com.opennxt.model.permissions.ModProfiles.of(name)
    }

    val rights: com.opennxt.model.permissions.Rights
        get() = modProfile?.rights ?: com.opennxt.model.permissions.Rights.of(save.rights)

    fun hasPower(node: String): Boolean {
        if (rights != com.opennxt.model.permissions.Rights.MOD) return false
        val granted = modProfile?.powers ?: com.opennxt.model.permissions.Powers.ALL
        return node in granted
    }

    val wornWeapon: EquippedWeapon?
        get() = worn[PlayerInventory.WEAPON_SLOT]?.let { PlayerCombatStats.lookupWeapon(it.id) }

    fun equipItem(itemId: Int): EquipResult {
        if (!PlayerInventory.equipEnabled) return EquipResult.Disabled
        val definition = com.opennxt.resources.sqlite.SqliteItemCodec.load(itemId)
            ?: return EquipResult.NoSuchItem(itemId)
        val slot = definition.equipSlotId
            ?: return EquipResult.NotEquipable(itemId, definition.name)
        if (slot < 0 || slot >= PlayerInventory.WORN_SIZE) {
            return EquipResult.SlotOutOfRange(itemId, definition.name, slot)
        }
        val container = worn
        val replaced = container[slot]
        container[slot] = com.opennxt.model.items.Item(itemId, 1)
        PlayerInventory.sendWorn(this)
        entity.model.dirty = true
        logger.info {
            "equip ${name}: item $itemId (${definition.name ?: "unnamed"}) -> worn slot $slot" +
                (if (replaced != null) ", replacing ${replaced.id}" else "")
        }
        return EquipResult.Equipped(itemId, definition.name, slot, replaced?.id)
    }

    fun unequipAll(): Int {
        val container = worn
        val had = container.usedSlots()
        container.clear()
        PlayerInventory.sendWorn(this)
        entity.model.dirty = true
        return had
    }

    val lifepointsUiStatus: String
        get() = if (System.getProperty("opennxt.experiment.ui.lifepoints") == "false")
            "disabled by -Dopennxt.experiment.ui.lifepoints=false (varp ${Lifepoints.CURRENT_LIFEPOINTS_VARP} " +
                "not sent), lifepoints=$currentLifepoints/$maxLifepoints"
        else
            "sent on varp ${Lifepoints.CURRENT_LIFEPOINTS_VARP} at login and on change, " +
                "lifepoints=$currentLifepoints/$maxLifepoints"

    fun toSave(): PlayerSave {
        val xp = LinkedHashMap<Stat, Double>()
        Stat.values().forEach { stat -> xp[stat] = stats.get(stat).experience }
        val location = entity.location
        val liveProject = com.opennxt.content.impl.Smithing.projectSnapshot(contentPlayer)
        if (liveProject != null) smithingRestoreRefused = false
        return save.copy(
            xp = xp,
            x = location.x, y = location.y, plane = location.plane,
            backpack = PlayerSave.containerContents(PlayerInventory.backpackOf(this)),
            worn = PlayerSave.containerContents(worn),
            rights = rights.id,
            bank = PlayerSave.bankContents(Banks.bankForAccount(name)),
            bankTabs = Banks.bankForAccount(name).savedTabs(),
            metalBank = PlayerSave.metalBankContents(com.opennxt.content.impl.MetalBanks.savedContents(name)),
            varcs = com.opennxt.net.game.handlers.VarcTransmitHandler.stateOf(this)
                .ifEmpty { save.varcs },
            varps = varpOverrides.toMap(),
            cosmetics = cosmetics.toMap(),
            toolbelt = toolbelt.sorted(),
            run = entity.runEnergy.let {
                if (it.isDefault()) null else PlayerSave.SavedRunEnergy(it.tenths, it.toggled)
            },
            smithing = liveProject?.let {
                PlayerSave.SavedProject(it.productId, it.progress, it.xpPaidTenths, it.heat, it.stage)
            } ?: if (smithingRestoreRefused) save.smithing else null,
            coinPouch = coinPouchAmount,
            slayerTask = com.opennxt.content.skills.SkillInteractions.toSaved(com.opennxt.content.skills.SkillInteractions.slayerTaskFor(name)),
        )
    }

    init {
        @Suppress("UNCHECKED_CAST")
        run {
            handlers[com.opennxt.net.game.serverprot.NoTimeout::class] =
                com.opennxt.net.game.handlers.NoTimeoutHandler
            handlers[com.opennxt.net.game.clientprot.VarcTransmit::class] =
                com.opennxt.net.game.handlers.VarcTransmitHandler
            handlers[com.opennxt.net.game.clientprot.ClientCheat::class] =
                com.opennxt.net.game.handlers.ClientCheatHandler
            handlers[com.opennxt.net.game.clientprot.IfButton1::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.MoveGameClick::class] =
                com.opennxt.net.game.handlers.MoveGameClickHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.MoveMinimapClick::class] =
                com.opennxt.net.game.handlers.MoveMinimapClickHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            handlers[com.opennxt.net.game.clientprot.WindowStatus::class] =
                com.opennxt.net.game.handlers.WindowStatusHandler

            handlers[com.opennxt.net.game.clientprot.IfButton2::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton3::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton4::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton5::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton6::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton7::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton8::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton9::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler
            handlers[com.opennxt.net.game.clientprot.IfButton10::class] =
                com.opennxt.net.game.handlers.IfButtonNHandler

            handlers[com.opennxt.net.game.clientprot.IfButtonLabelled::class] =
                com.opennxt.net.game.handlers.IfButtonLabelledHandler

            handlers[com.opennxt.net.game.clientprot.OpLoc1::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc2::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc3::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc4::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc5::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpLoc6::class] =
                com.opennxt.net.game.handlers.OpLocHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            handlers[com.opennxt.net.game.clientprot.OpNpc1::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc2::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc3::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc4::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc5::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpNpc6::class] =
                com.opennxt.net.game.handlers.OpNpcHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            handlers[com.opennxt.net.game.clientprot.OpPlayer1::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer2::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer3::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer4::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer5::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer6::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer7::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer8::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer9::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpPlayer10::class] =
                com.opennxt.net.game.handlers.OpPlayerHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            handlers[com.opennxt.net.game.clientprot.OpObj1::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj2::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj3::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj4::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj5::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.OpObj6::class] =
                com.opennxt.net.game.handlers.OpObjHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.EventAppletFocus::class] =
                com.opennxt.net.game.handlers.EventAppletFocusHandler
            handlers[com.opennxt.net.game.clientprot.EventCameraPosition::class] =
                com.opennxt.net.game.handlers.EventCameraPositionHandler

            handlers[com.opennxt.net.game.clientprot.EventKeyboard::class] =
                com.opennxt.net.game.handlers.EventKeyboardHandler
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>

            handlers[com.opennxt.net.game.clientprot.MessagePublic::class] =
                EquipChatCommand
                    as com.opennxt.net.game.pipeline.GamePacketHandler<in com.opennxt.model.entity.BasePlayer, out com.opennxt.net.game.GamePacket>
            handlers[com.opennxt.net.game.clientprot.ChatSetMode::class] =
                com.opennxt.net.game.handlers.ChatSetModeHandler
            handlers[com.opennxt.net.game.clientprot.MessagePrivate::class] =
                com.opennxt.net.game.handlers.MessagePrivateHandler

            handlers[com.opennxt.net.game.clientprot.EventMouseClick::class] =
                com.opennxt.net.game.handlers.EventMouseClickHandler

            handlers[com.opennxt.net.game.clientprot.WorldlistFetch::class] =
                com.opennxt.net.game.handlers.WorldlistFetchHandler

            handlers[com.opennxt.net.game.clientprot.UndecodedClientPacket::class] =
                com.opennxt.net.game.handlers.UndecodedClientPacketHandler

            handlers[com.opennxt.net.game.clientprot.generated.IfUpdateCount::class] =
                com.opennxt.net.game.handlers.IfUpdateCountHandler
            handlers[com.opennxt.net.game.clientprot.generated.ResumePausebutton::class] =
                com.opennxt.net.game.handlers.ResumePausebuttonHandler
            handlers[com.opennxt.net.game.clientprot.generated.IfButtond::class] =
                com.opennxt.net.game.handlers.IfButtondHandler
            handlers[com.opennxt.net.game.clientprot.generated.Oploct::class] = com.opennxt.net.game.handlers.OpLocTHandler
            handlers[com.opennxt.net.game.clientprot.generated.IfButtont::class] = com.opennxt.net.game.handlers.IfButtontHandler
            com.opennxt.net.game.GeneratedRegistrations.installClientHandlers(handlers)
        }
    }

    fun handleIncomingPackets() {
        val queue = client.incomingQueue
        var handled = 0
        while (handled < com.opennxt.net.InboundDrainLimit.MAX_PER_TICK) {
            val packet = client.pollIncoming() ?: return
            handled++

            val handler = handlers[packet::class] as? GamePacketHandler<in BasePlayer, GamePacket>
            if (handler != null) {
                handler.handle(this, packet)
            } else {
                InboundCensus.noHandler(packet)
            }
        }
        val remaining = queue.size
        if (remaining > 0) com.opennxt.net.InboundDrainLimit.reportBacklog(name, handled, remaining)
    }

    private val optionsMenuSlot: Int =
        System.getProperty("opennxt.experiment.ui.optionsSlot")?.toIntOrNull() ?: 805

    private val panelApplyAt: String =
        (System.getProperty("opennxt.experiment.panelApply.at") ?: "late").lowercase().also {
            if (it !in setOf("early", "late", "both"))
                logger.warn("opennxt.experiment.panelApply.at=$it is not early, late or both; using late")
        }.let { if (it in setOf("early", "late", "both")) it else "late" }

    private fun applyPanelVisibility(position: String) {
        if (System.getProperty("opennxt.experiment.panelApply") == "false") return
        val mounts = interfaces.openedCount()

        if (position == panelApplyAt || panelApplyAt == "both") {
            com.opennxt.content.impl.LoginVarps.send(this)
        }

        com.opennxt.content.impl.VarcRestore.send(this)

        sendLifepoints()

        runCatching { com.opennxt.content.impl.PrayerBook.onLogin(this) }
            .onFailure { logger.warn(it) { "prayers: login state failed for $name" } }

        if (System.getProperty("opennxt.experiment.ui.login9943") == "off") {
            logger.warn("panelApply[$position]: RunClientScript(9943) skipped (ui.login9943=off)")
            return
        }
        client.write(RunClientScript(script = 9943, args = arrayOf()))
        logger.info("panelApply[$position]: sent RunClientScript(9943) with $mounts interface(s) mounted")
    }

    fun added() {
        entity.model.refresh()

        client.channel.write(Unpooled.buffer(1).writeByte(GenericResponse.SUCCESSFUL.id))
        val response = LoginPacket.GameLoginResponse.forWorld(entity.index, name)
        logger.debug { "Sending game login response: $response" }
        val future = client.channel.writeAndFlush(response)

        future.awaitUninterruptibly(3000)
        if (!future.isSuccess) {
            logger.warn(future.cause()) { "Game login response for $name failed to send; not swapping pipeline" }
            return
        }
        if (!LoginServerHandler.swapToGamePipeline(client.channel, name)) return

        val packet = Unpooled.buffer(5140)
        val builder = GamePacketBuilder(packet)
        viewport.init(builder)
        val registration = PacketRegistry.getRegistration(Side.SERVER, RebuildNormal::class)!!
        (registration.codec as GamePacketCodec<GamePacket>).encode(viewport.createPacket(), builder)
        client.write(UnidentifiedPacket(OpcodeWithBuffer(registration.opcode, packet)))

        val player = this

        if (PlayerStatContainer.sendStatsEnabled) {
            statBurstCountdown = STAT_SEND_DELAY_TICKS
            logger.warn {
                "UPDATE_STAT burst scheduled in $statBurstCountdown tick(s)"
            }
        }
        if (com.opennxt.content.impl.Replay949.enabled && com.opennxt.content.impl.Replay949.only) {
            replay949Cursor = 0
            replay949Countdown = com.opennxt.content.impl.Replay949.delayTicks
            logger.warn { "replay949[only]: ${com.opennxt.content.impl.Replay949.size()} packet(s) start in ${replay949Countdown} tick(s); default login sequence skipped" }
            return
        }
        player.client.write(ResetClientVarcache)
        TODORefactorThisClass.sendDefaultVarps(client, stored = { id -> varpOverride(id) })
        com.opennxt.content.impl.OptionsMenu.applyLayoutDefaults(player)
        player.client.write(RunClientScript(script = 671, args = arrayOf(0)))
        player.interfaces.openTop(id = 1477)
        player.interfaces.open(id = 1482, parent = 1477, component = 31, walkable = true)
        if (System.getProperty("opennxt.experiment.ui.worldRepeat") == "true") {
            val worldView = (1482 shl 16) or 0
            player.client.write(RunClientScript(script = 4595, args = arrayOf(worldView, "")))
            logger.warn { "ui.worldRepeat: sent RunClientScript(4595, [1482:0, \"\"])" }
        }
        if (System.getProperty("opennxt.experiment.ui.worldHover") == "true") {
            val worldView = (1482 shl 16) or 0
            player.client.write(RunClientScript(script = 9671, args = arrayOf(-1, worldView, worldView)))
            logger.warn { "ui.worldHover: sent RunClientScript(9671, [-1, 1482:0, 1482:0])" }
        }
        player.interfaces.open(id = 1466, parent = 1477, component = 284, walkable = true)
        player.interfaces.events(id = 1466, component = 7, from = 0, to = 28, mask = 30)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(0, 1)))
        player.interfaces.open(id = 1473, parent = 1477, component = 98, walkable = true)
        if (System.getProperty("opennxt.experiment.unhideBackpack") != "false") {
            player.interfaces.hide(id = 1477, component = 101, hidden = false)
        }
        if (panelApplyAt == "early" || panelApplyAt == "both") applyPanelVisibility("early")
        val legacyBackpackArm = !com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()
        if (legacyBackpackArm) {
            player.interfaces.events(id = 1473, component = 7, from = 65535, to = 65535, mask = 2097152)
            player.interfaces.events(id = 1473, component = 7, from = 0, to = 27, mask = 15302030)
        }
        if (legacyBackpackArm && System.getProperty("opennxt.experiment.backpackSlotArm") != "false") {
            for (slotParent in intArrayOf(4, 5, 6)) {
                player.interfaces.events(id = 1473, component = slotParent,
                    from = 0, to = 27, mask = 15302030)
            }
        }
        if (legacyBackpackArm) {
            player.interfaces.events(id = 1473, component = 25, from = 0, to = 16, mask = 1422)
            player.interfaces.events(id = 1473, component = 1, from = 0, to = 5, mask = 2099198)
            player.interfaces.events(id = 1473, component = 28, from = 0, to = 5, mask = 2099198)
        }
        player.client.write(RunClientScript(script = 8862, args = arrayOf(2, 1)))
        player.interfaces.open(id = 1464, parent = 1477, component = 109, walkable = true)
        player.interfaces.events(id = 1464, component = 15, from = 0, to = 18, mask = 15302654)
        player.interfaces.events(id = 1464, component = 24, from = 0, to = 6, mask = 2046)
        player.interfaces.events(id = 1464, component = 19, from = 0, to = 6, mask = 2046)
        player.interfaces.events(id = 1464, component = 15, from = 0, to = 18, mask = 10749950)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(3, 1)))
        player.client.write(ClientSetvarcSmall(181, 0))
        player.interfaces.open(id = 1458, parent = 1477, component = 131, walkable = true)
        player.interfaces.events(id = 1458, component = 39, from = 0, to = 38, mask = 8388610)
        player.interfaces.open(id = 1461, parent = 1477, component = 186, walkable = true)
        player.interfaces.open(id = 1884, parent = 1477, component = 197, walkable = true)
        player.interfaces.open(id = 1885, parent = 1477, component = 208, walkable = true)
        player.interfaces.open(id = 1887, parent = 1477, component = 219, walkable = true)
        player.interfaces.open(id = 1886, parent = 1477, component = 230, walkable = true)
        player.interfaces.open(id = 1460, parent = 1477, component = 142, walkable = true)
        com.opennxt.content.impl.PrayerBook.arm(player)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(4, 1)))
        runCatching { com.opennxt.content.impl.ToolBeltPanel.armOpenButton(player) }
            .onFailure { logger.warn(it) { "could not arm the tool-belt open button for ${player.name}" } }
        if (com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()) runCatching {
            com.opennxt.content.impl.BackpackDrag.arm950(player)
        }.onFailure { logger.warn(it) { "could not arm the 950 backpack rows for ${player.name}" } }
        if (com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()) runCatching {
            com.opennxt.content.impl.MoneyPouch.sendLogin(player)
        }.onFailure { logger.warn(it) { "could not send the money pouch for ${player.name}" } }
        runCatching { com.opennxt.content.impl.WorldMapWindow.arm(player) }
            .onFailure { logger.warn(it) { "could not arm the world-map button for ${player.name}" } }
        player.interfaces.open(id = 1881, parent = 1477, component = 153, walkable = true)
        player.interfaces.open(id = 1888, parent = 1477, component = 164, walkable = true)
        player.interfaces.open(id = 1883, parent = 1477, component = 241, walkable = true)
        player.interfaces.open(id = 1449, parent = 1477, component = 252, walkable = true)
        player.interfaces.open(id = 1882, parent = 1477, component = 263, walkable = true)
        player.interfaces.open(id = 1452, parent = 1477, component = 175, walkable = true)

        if (System.getProperty("opennxt.experiment.ui.extraMounts") != "false") {
            player.interfaces.open(id = 994, parent = 1477, component = 43, walkable = true, native949 = true)
            player.interfaces.open(id = 1449, parent = 1477, component = 268, walkable = true, native949 = true)
            player.interfaces.open(id = 1882, parent = 1477, component = 279, walkable = true, native949 = true)
        } else {
            logger.info("ui.extraMounts=false: 994@43, 1449@268 and 1882@279 not opened")
        }
        val c950LoginBooks = com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()
        player.interfaces.events(id = 1461, component = 1, from = 0, to = 211, mask = 10320974, native949 = c950LoginBooks)
        player.interfaces.events(id = 1884, component = 1, from = 0, to = 211, mask = 10320974, native949 = c950LoginBooks)
        player.interfaces.events(id = 1885, component = 1, from = 0, to = 211, mask = 10320974, native949 = c950LoginBooks)
        player.interfaces.events(id = 1887, component = 1, from = 0, to = 211, mask = 10320974, native949 = c950LoginBooks)
        player.interfaces.events(id = 1886, component = 1, from = 0, to = 211, mask = 10320974, native949 = c950LoginBooks)
        player.interfaces.events(id = 1461, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1461, component = 7, from = 7, to = 10, mask = 10319874, native949 = c950LoginBooks)
        player.interfaces.events(id = 1460, component = 5, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1460, component = 5, from = 7, to = 10, mask = 10319874, native949 = c950LoginBooks)
        player.interfaces.events(id = 1452, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1883, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1883, component = 7, from = 7, to = 10, mask = 10319874, native949 = c950LoginBooks)
        player.interfaces.events(id = 1881, component = 5, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1888, component = 5, from = 7, to = 16, mask = 2)
        player.interfaces.events(id = 1449, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1882, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1884, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1885, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1887, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1886, component = 7, from = 7, to = 16, mask = 2, native949 = c950LoginBooks)
        player.interfaces.events(id = 1460, component = 1, from = 0, to = 211, mask = 10320902, native949 = c950LoginBooks)
        player.interfaces.events(id = 1881, component = 1, from = 0, to = 211, mask = 10320902)
        player.interfaces.events(id = 1888, component = 1, from = 0, to = 211, mask = 10320902)
        player.interfaces.events(id = 1452, component = 1, from = 0, to = 211, mask = 10320902, native949 = c950LoginBooks)
        player.interfaces.events(id = 1883, component = 1, from = 0, to = 211, mask = 10320902, native949 = c950LoginBooks)
        player.interfaces.events(id = 1449, component = 1, from = 0, to = 211, mask = 10320902, native949 = c950LoginBooks)
        player.interfaces.events(id = 1882, component = 1, from = 0, to = 211, mask = 10320902, native949 = c950LoginBooks)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(5, 1)))
        player.interfaces.open(id = 550, parent = 1477, component = 475, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(14, 1)))
        player.interfaces.events(id = 550, component = 7, from = 0, to = 500, mask = 2046)
        player.interfaces.events(id = 550, component = 60, from = 0, to = 500, mask = 6)
        player.interfaces.open(id = 1427, parent = 1477, component = 519, walkable = true)
        player.client.write(ClientSetvarcSmall(1027, 1))
        player.client.write(ClientSetvarcSmall(1034, 2))
        player.interfaces.events(id = 1427, component = 29, from = 0, to = 600, mask = 1040)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(15, 1)))
        player.interfaces.open(id = 1110, parent = 1477, component = 486, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(16, 1)))
        player.interfaces.events(id = 1110, component = 31, from = 0, to = 200, mask = 2)
        player.interfaces.events(id = 1110, component = 85, from = 0, to = 600, mask = 2)
        player.interfaces.events(id = 1110, component = 83, from = 0, to = 600, mask = 1040)
        player.interfaces.events(id = 1110, component = 38, from = 0, to = 600, mask = 1040)
        player.interfaces.open(id = 590, parent = 1477, component = 393, walkable = true)
        player.interfaces.events(id = 590, component = 1, from = 0, to = 58, mask = 8388622)
        player.interfaces.events(id = 590, component = 11, from = 0, to = 230, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 0, to = 0, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4096, to = 4096, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4352, to = 4352, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4608, to = 4608, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4864, to = 4864, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5120, to = 5120, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5376, to = 5376, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5632, to = 5632, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5888, to = 5888, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6144, to = 6144, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6400, to = 6400, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6656, to = 6656, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6912, to = 6912, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7168, to = 7168, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7424, to = 7424, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7680, to = 7680, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7936, to = 7936, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8192, to = 8192, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8448, to = 8448, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8704, to = 8704, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8960, to = 8960, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9216, to = 9216, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9472, to = 9472, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9728, to = 9728, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9984, to = 9984, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10240, to = 10240, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10496, to = 10496, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10752, to = 10752, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11008, to = 11008, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11264, to = 11264, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11520, to = 11520, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11776, to = 11776, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12032, to = 12032, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12288, to = 12288, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12544, to = 12544, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12800, to = 12800, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13056, to = 13056, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13312, to = 13312, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13568, to = 13568, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13824, to = 13824, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14080, to = 14080, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14336, to = 14336, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14592, to = 14592, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14848, to = 14848, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15104, to = 15104, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15360, to = 15360, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15616, to = 15616, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15872, to = 15872, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16128, to = 16128, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16384, to = 16384, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16640, to = 16640, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16896, to = 16896, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17152, to = 17152, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17408, to = 17408, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17664, to = 17664, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17920, to = 17920, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18176, to = 18176, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18432, to = 18432, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18688, to = 18688, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18944, to = 18944, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19200, to = 19200, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19456, to = 19456, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19712, to = 19712, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19968, to = 19968, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20224, to = 20224, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20480, to = 20480, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20736, to = 20736, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20992, to = 20992, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21248, to = 21248, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21504, to = 21504, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21760, to = 21760, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22016, to = 22016, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22272, to = 22272, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22528, to = 22528, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22784, to = 22784, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23040, to = 23040, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23296, to = 23296, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23552, to = 23552, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23808, to = 23808, mask = 8388614)
        player.interfaces.events(id = 590, component = 1, from = 0, to = 58, mask = 8388622)
        player.interfaces.events(id = 590, component = 11, from = 0, to = 230, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 0, to = 0, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4096, to = 4096, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4352, to = 4352, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4608, to = 4608, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 4864, to = 4864, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5120, to = 5120, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5376, to = 5376, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5632, to = 5632, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 5888, to = 5888, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6144, to = 6144, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6400, to = 6400, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6656, to = 6656, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 6912, to = 6912, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7168, to = 7168, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7424, to = 7424, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7680, to = 7680, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 7936, to = 7936, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8192, to = 8192, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8448, to = 8448, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8704, to = 8704, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 8960, to = 8960, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9216, to = 9216, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9472, to = 9472, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9728, to = 9728, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 9984, to = 9984, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10240, to = 10240, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10496, to = 10496, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 10752, to = 10752, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11008, to = 11008, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11264, to = 11264, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11520, to = 11520, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 11776, to = 11776, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12032, to = 12032, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12288, to = 12288, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12544, to = 12544, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 12800, to = 12800, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13056, to = 13056, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13312, to = 13312, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13568, to = 13568, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 13824, to = 13824, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14080, to = 14080, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14336, to = 14336, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14592, to = 14592, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 14848, to = 14848, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15104, to = 15104, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15360, to = 15360, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15616, to = 15616, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 15872, to = 15872, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16128, to = 16128, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16384, to = 16384, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16640, to = 16640, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 16896, to = 16896, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17152, to = 17152, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17408, to = 17408, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17664, to = 17664, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 17920, to = 17920, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18176, to = 18176, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18432, to = 18432, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18688, to = 18688, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 18944, to = 18944, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19200, to = 19200, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19456, to = 19456, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19712, to = 19712, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 19968, to = 19968, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20224, to = 20224, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20480, to = 20480, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20736, to = 20736, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 20992, to = 20992, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21248, to = 21248, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21504, to = 21504, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 21760, to = 21760, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22016, to = 22016, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22272, to = 22272, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22528, to = 22528, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 22784, to = 22784, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23040, to = 23040, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23296, to = 23296, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23552, to = 23552, mask = 8388614)
        player.interfaces.events(id = 590, component = 12, from = 23808, to = 23808, mask = 8388614)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(9, 1)))
        player.interfaces.open(id = 1416, parent = 1477, component = 295, walkable = true)
        player.interfaces.events(id = 1416, component = 3, from = 0, to = 3087, mask = 62)
        player.interfaces.events(id = 1416, component = 11, from = 0, to = 99, mask = 2359334)
        player.interfaces.events(id = 1416, component = 11, from = 100, to = 199, mask = 4)
        player.interfaces.events(id = 1416, component = 11, from = 200, to = 200, mask = 2097152)
        player.interfaces.text(id = 1416, component = 6, text = "Adventure")
        player.client.write(RunClientScript(script = 8862, args = arrayOf(10, 1)))
        player.client.write(ClientSetvarcSmall(3497, 0))
        player.interfaces.open(id = 1417, parent = 1477, component = 508, walkable = true)
        player.interfaces.events(id = 1417, component = 9, from = 0, to = 0, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 4096, to = 4096, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 4352, to = 4352, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 4608, to = 4608, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 4864, to = 4864, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 5120, to = 5120, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 5376, to = 5376, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 5632, to = 5632, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 5888, to = 5888, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 6144, to = 6144, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 6400, to = 6400, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 6656, to = 6656, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 6912, to = 6912, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 7168, to = 7168, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 7424, to = 7424, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 7680, to = 7680, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 7936, to = 7936, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 8192, to = 8192, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 8448, to = 8448, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 8704, to = 8704, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 8960, to = 8960, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 9216, to = 9216, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 9472, to = 9472, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 9728, to = 9728, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 9984, to = 9984, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 10240, to = 10240, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 10496, to = 10496, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 10752, to = 10752, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 11008, to = 11008, mask = 2621470)
        player.interfaces.events(id = 1417, component = 9, from = 11264, to = 11264, mask = 2621470)
        player.interfaces.text(id = 1417, component = 5, text = "Loading notes<br>Please wait...")
        player.interfaces.hide(id = 1417, component = 5, hidden = false)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(11, 1)))
        player.client.write(RunClientScript(script = 8862, args = arrayOf(12, 0)))
        player.interfaces.open(id = 1519, parent = 1477, component = 497, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(27, 1)))
        player.interfaces.open(id = 1588, parent = 1477, component = 327, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(28, 1)))
        player.interfaces.open(id = 1678, parent = 1477, component = 338, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(29, 1)))
        player.client.write(RunClientScript(script = 8862, args = arrayOf(30, 0)))
        player.interfaces.open(id = 190, parent = 1477, component = 360, walkable = true)
        player.interfaces.events(id = 190, component = 5, from = 0, to = 312, mask = 14)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(31, 1)))
        player.interfaces.open(id = 1854, parent = 1477, component = 371, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(32, 1)))
        player.interfaces.events(id = 1854, component = 6, from = 0, to = 4, mask = 66)
        player.interfaces.events(id = 1854, component = 5, from = 0, to = 1, mask = 2)
        player.interfaces.open(id = 1894, parent = 1477, component = 382, walkable = true)
        player.interfaces.events(id = 1894, component = 16, from = 0, to = 2, mask = 2)
        player.interfaces.events(id = 1894, component = 18, from = 0, to = 3, mask = 6)
        player.interfaces.events(id = 1894, component = 19, from = 0, to = 3, mask = 6)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(41, 1)))

        run {
            val t = InterfaceManager.hudMountTable()
            logger.info {
                "hudMount[${InterfaceManager.hudMountMode}] rows=${InterfaceManager.hudRowsEnabled}: ${t.size} component(s) re-seated -> " +
                    t.entries.joinToString { "${it.key}->${it.value}" }
            }
        }
        player.interfaces.open(id = 1431, parent = 1477, component = 59, walkable = true)
        if ("ribbon" in InterfaceManager.hudRowsEnabled && InterfaceManager.hudAnnounce) {
            player.client.write(RunClientScript(script = 8310, args = arrayOf(1002)))
            logger.info { "hudMount: announced the ribbon with RunClientScript(8310, [1002])" }
        }
        if ("popups" !in InterfaceManager.hudRowsEnabled) {
            player.interfaces.open(id = 568, parent = 1477, component = 691, walkable = true, native949 = true)
            player.interfaces.open(id = 598, parent = 1477, component = 638, walkable = true, native949 = true)
        } else logger.info { "hudMount popups: not opening 568 at 1477:691 or 598 at 1477:638" }
        player.interfaces.events(id = 1477, component = 60, from = 1, to = 1, mask = 2)
        player.interfaces.events(id = 1431, component = 0, from = 0, to = 46, mask = 6)
        player.interfaces.events(id = 568, component = 5, from = 0, to = 46, mask = 6)
        player.interfaces.open(id = 1465, parent = 1477, component = 90, walkable = true)
        player.interfaces.open(id = 1919, parent = 1477, component = 91, walkable = true)
        player.interfaces.events(id = 1477, component = if (com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()) 99 else 94, from = 1, to = 1, mask = 6)
        if ("popups" !in InterfaceManager.hudRowsEnabled) player.interfaces.open(id = 634, parent = 1477, component = 739, walkable = true)
        else logger.info { "hudMount popups: not opening 634 (Premier Pass) at 1477:739" }
        player.client.write(RunClientScript(script = 11145, args = arrayOf(1067, 600, 0, 0, 96797466)))
        player.client.write(RunClientScript(script = 8420, args = arrayOf(-1, 96797410, 96797411, -1, "", 21259, 1007)))
        player.interfaces.hide(id = 634, component = 259, hidden = true)
        player.interfaces.hide(id = 634, component = 0, hidden = false)
        player.interfaces.events(id = 634, component = 152, from = 65535, to = 65535, mask = 1022)
        player.interfaces.events(id = 634, component = 265, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 67, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 68, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 138, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 139, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 634, component = 2, from = 65535, to = 65535, mask = 2)
        if (System.getProperty("opennxt.experiment.panel653") == "true") {
            player.interfaces.open(id = 653, parent = 1477, component = 797, walkable = true)
            player.client.write(RunClientScript(script = 11145, args = arrayOf(1067, 600, 0, 0, 96797413)))
            player.client.write(RunClientScript(script = 8420, args = arrayOf(-1, 96797415, 96797416, -1, "", 21259, 1007)))
            player.interfaces.hide(id = 653, component = 71, hidden = true)
            player.interfaces.hide(id = 653, component = 0, hidden = false)
            player.interfaces.events(id = 653, component = 155, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 166, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 177, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 188, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 199, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 210, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 221, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 232, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 243, from = 0, to = 500, mask = 62)
            player.interfaces.events(id = 653, component = 254, from = 0, to = 500, mask = 62)
        } else {
            logger.info {
                "interface 653 (Travelling Artisan) not opened; -Dopennxt.experiment.panel653=true to enable"
            }
        }
        player.interfaces.open(id = 1430, parent = 1477, component = 65, walkable = true)
        if ("mainbar" in InterfaceManager.hudRowsEnabled && InterfaceManager.hudAnnounce) {
            player.client.write(RunClientScript(script = 8310, args = arrayOf(1003)))
            logger.info { "hudMount: announced the main action bar with RunClientScript(8310, [1003])" }
        }
        com.opennxt.content.impl.PanelToggles.sendLoginState(player)
        runCatching { com.opennxt.content.combat.CombatSpells.sendLoginState(player) }
            .onFailure { logger.warn(it) { "login: could not send combat spell state for ${player.name}" } }
        player.interfaces.events(id = 1477, component = if (com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()) 71 else 66, from = 1, to = 1, mask = 4)
        player.interfaces.events(id = 1430, component = 66, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 69, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 79, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 82, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 92, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 95, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 105, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 108, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 118, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 121, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 131, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 134, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 144, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 147, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 157, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 160, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 170, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 173, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 183, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 186, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 196, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 199, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 209, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 212, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 222, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 225, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 235, from = 65535, to = 65535, mask = 2098176)
        player.interfaces.events(id = 1430, component = 238, from = 65535, to = 65535, mask = 2098176)
        player.interfaces.events(id = 1430, component = 19, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1430, component = 13, from = 65535, to = 65535, mask = 8650758)
        player.interfaces.events(id = 1430, component = 26, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1430, component = 18, from = 65535, to = 65535, mask = 2046)
        player.interfaces.events(id = 1430, component = 59, from = 65535, to = 65535, mask = 8650754)
        player.interfaces.events(id = 1430, component = 261, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 260, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 259, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 258, from = 65535, to = 65535, mask = 448)
        player.interfaces.events(id = 1430, component = 38, from = 65535, to = 65535, mask = 0)
        player.interfaces.events(id = 1430, component = 38, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 1458, component = 39, from = 0, to = 38, mask = 8388610)
        player.interfaces.events(id = 1465, component = 15, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1460, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1881, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1888, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1452, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1461, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1884, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1885, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1887, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1886, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1883, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1449, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1882, component = 1, from = 0, to = 211, mask = 8616966)
        player.client.write(ClientSetvarcSmall(1436, 1))
        player.interfaces.open(id = 1433, parent = 1477, component = optionsMenuSlot, walkable = true)
        if (optionsMenuSlot == 805) {
            logger.info("options menu: interface 1433 mounted at 1477:805")
        } else {
            logger.warn("options menu: interface 1433 mounted at 1477:$optionsMenuSlot instead of 1477:805")
        }
        player.interfaces.events(id = 1433, component = 6, from = 0, to = 6, mask = 2)
        if (System.getProperty("opennxt.experiment.ui.chat1923") == "true") {
            logger.info("chat-panel experiment: opening 1923 (not 137) on the All Chat mount")
            player.interfaces.open(id = 1923, parent = 1477, component = 404, walkable = true)
        } else {
            player.interfaces.open(id = 137, parent = 1477, component = 404, walkable = true)
        }
        player.interfaces.events(id = 137, component = 86, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 137, component = 62, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 137, component = 65, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 137, component = 59, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1467, parent = 1477, component = 415, walkable = true)
        player.interfaces.events(id = 1467, component = 192, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1467, component = 180, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1467, component = 183, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1467, component = 185, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1472, parent = 1477, component = 425, walkable = true)
        player.interfaces.events(id = 1472, component = 193, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1472, component = 187, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1472, component = 190, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1472, component = 192, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1471, parent = 1477, component = 435, walkable = true)
        player.interfaces.events(id = 1471, component = 193, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1471, component = 181, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1471, component = 184, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1471, component = 186, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1470, parent = 1477, component = 445, walkable = true)
        player.interfaces.events(id = 1470, component = 193, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1470, component = 181, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1470, component = 184, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1470, component = 186, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 464, parent = 1477, component = 455, walkable = true)
        player.interfaces.events(id = 464, component = 193, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 464, component = 181, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 464, component = 184, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 464, component = 186, from = 0, to = 2, mask = 2)
        player.interfaces.open(id = 1529, parent = 1477, component = 465, walkable = true)
        player.interfaces.events(id = 1529, component = 192, from = 0, to = 99, mask = 1792)
        player.interfaces.events(id = 1529, component = 180, from = 0, to = 11, mask = 126)
        player.interfaces.events(id = 1529, component = 183, from = 0, to = 7, mask = 126)
        player.interfaces.events(id = 1529, component = 185, from = 0, to = 2, mask = 2)
        player.interfaces.events(id = 1477, component = if (com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()) 99 else 94, from = 1, to = 1, mask = 6)
        player.interfaces.open(InterfaceSlot.MINIMAP, 1484, walkable = true)
        player.interfaces.hide(id = 1477, component = 593, hidden = false)
        if (System.getProperty("opennxt.experiment.ui.gravestone") == "true") {
            player.interfaces.open(id = 1483, parent = 1477, component = 576, walkable = true)
        }
        player.interfaces.open(id = 745, parent = 1477, component = 589, walkable = true)
        player.interfaces.open(id = 284, parent = 1477, component = 572, walkable = true)
        player.interfaces.open(id = 1213, parent = 1477, component = 619, walkable = true)
        player.interfaces.open(id = 1448, parent = 1477, component = 715, walkable = true)
        System.getProperty("opennxt.experiment.ui.suboverlay")?.let { raw ->
            val map = mapOf("inv" to 1474, "worn" to 1462, "bar" to 1436, "prayer" to 1457)
            val layers = intArrayOf(3, 5, 7, 9, 11)
            raw.split(',').map { it.trim().lowercase() }.filter { it in map }.forEachIndexed { i, key ->
                if (i < layers.size) {
                    player.interfaces.open(id = map.getValue(key), parent = 1448, component = layers[i], walkable = true, native949 = true)
                    logger.warn { "ui.suboverlay: opened ${map.getValue(key)} (suboverlay $key) into 1448:${layers[i]}" }
                }
            }
        }
        player.interfaces.open(id = 291, parent = 1477, component = 568, walkable = true)
        player.client.write(ClientSetvarcSmall(2834, 1))
        val c950 = com.opennxt.model.entity.rendering.UpdateBlockType.experimentalBuild()
        player.interfaces.events(id = 1477, component = if (c950) 26 else 22, from = 65535, to = 65535, mask = 2097152)
        player.client.write(RunClientScript(script = 139, args = arrayOf(if (c950) 96796699 else 96796695)))
        player.interfaces.open(id = 1488, parent = 1477, component = 760, walkable = true)
        player.interfaces.open(id = 1680, parent = 1477, component = 39, walkable = true)
        player.client.write(RunClientScript(script = 14150, args = arrayOf(5)))
        player.interfaces.events(id = 1477, component = if (c950) 17 else 15, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 1477, component = if (c950) 17 else 15, from = 0, to = if (c950) 46 else 41, mask = 2)
        player.interfaces.events(id = 1477, component = 840, from = 0, to = 1000, mask = 2)
        player.interfaces.open(id = 1847, parent = 1477, component = 912, walkable = true)
        player.interfaces.events(id = 1477, component = 912, from = 0, to = 0, mask = 2)
        if ("popups" !in InterfaceManager.hudRowsEnabled) player.interfaces.open(id = 635, parent = 1477, component = 615, walkable = true)
        else logger.info { "hudMount popups: not opening 635 (Undead Army window) at 1477:615" }
        player.client.write(RunClientScript(script = 8862, args = arrayOf(1025, 0)))
        player.client.write(RunClientScript(script = 2651, args = arrayOf(1025, 0)))
        player.client.write(RunClientScript(script = 7486, args = arrayOf(27066179, 41615368)))
        player.client.write(RunClientScript(script = 10903))
        player.interfaces.open(id = 1639, parent = 1477, component = 627, walkable = true)
        player.client.write(RunClientScript(script = 8862, args = arrayOf(1031, 1)))
        player.client.write(RunClientScript(script = 2651, args = arrayOf(1031, 0)))
        player.client.write(ClientSetvarcSmall(1413, 1))
        player.client.write(RunClientScript(script = 8778))

        run {
            val panelsHigh = listOf(
                674 to 1639,
                592 to 1756,
                609 to 636,
                576 to 268,
                588 to 942,
                664 to 1234,
            )
            val panelsMid = panelsHigh + listOf(
                61 to 1431,
                67 to 1430,
                72 to 1670,
                77 to 1671,
                87 to 1673,
                125 to 662,
                268 to 1449,
                279 to 1882,
                322 to 231,
                611 to 291,
                693 to 517,
                703 to 1622,
                715 to 1448,
                737 to 374
            )
            val panelsLow = listOf(
                82 to 1672,
                698 to 1557
            )
            val raw = System.getProperty("opennxt.experiment.ui.extraPanels")?.trim()
            val pick: List<Pair<Int, Int>> = when {
                raw.isNullOrEmpty() -> emptyList()
                raw.equals("high", true) -> panelsHigh
                raw.equals("mid", true) -> panelsMid
                raw.equals("all", true) -> panelsMid + panelsLow
                else -> {
                    val want = raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                    (panelsMid + panelsLow).filter { it.first in want }
                }
            }
            if (pick.isNotEmpty()) {
                logger.warn {
                    "ui experiment: opening ${pick.size} extra panel(s) -> " +
                        pick.joinToString { "1477:${it.first}=${it.second}" }
                }
                pick.forEach { (component, iface) ->
                    if (player.interfaces.isOpened(iface)) {
                        logger.info { "ui experiment: extraPanels skips 1477:$component=$iface - already open" }
                        return@forEach
                    }
                    player.interfaces.open(id = iface, parent = 1477, component = component, walkable = true)
                }
            }
        }
        player.interfaces.text(id = 187, component = 7, text = "")
        player.interfaces.text(id = 1416, component = 6, text = "")
        player.client.write(ClientSetvarcSmall(6348, 0))
        player.client.write(ClientSetvarcSmall(1077, 0))
        player.client.write(ClientSetvarcSmall(2746, 0))
        com.opennxt.content.impl.VarcRestore.sendInt(
            player, com.opennxt.content.impl.VarcRestore.COMBAT_LEVEL_VARC,
            com.opennxt.content.impl.VarcRestore.combatLevel(player)
        )
        player.client.write(RunClientScript(script = 4704))
        player.client.write(RunClientScript(script = 4308, args = arrayOf(18, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30522, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30758, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30759, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30821, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30828, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(30964, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(31386, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(31562, 0)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(31918, 0)))
          com.opennxt.content.impl.VarcRestore.sendLevels(player)
          player.client.write(ClientSetvarcSmall(5153, 1))
        player.interfaces.hide(id = 1477, component = 555, hidden = false)
        player.interfaces.hide(id = 745, component = 5, hidden = true)
        player.client.write(RunClientScript(script = 5559, args = arrayOf(0L)))
        player.client.write(RunClientScript(script = 10623, args = arrayOf(39392, 0)))
        player.client.write(ClientSetvarcSmall(2059, 0))
        player.client.write(RunClientScript(script = 3957))

        if (System.getProperty("opennxt.experiment.ui.loginScripts") != "false") {
            player.client.write(RunClientScript(script = 18950, args = arrayOf(1)))
            player.client.write(RunClientScript(script = 18952, args = arrayOf(0)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(0, 0, 0, 0, 3, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(1, 0, 0, 0, 3, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(2, 0, 0, 0, 3, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(3, 255, 211, 0, 0, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(4, 255, 13, 22, 0, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(5, 26, 235, 255, 0, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(6, 238, 100, 0, 0, 7)))
            player.client.write(RunClientScript(script = 18951, args = arrayOf(7, 238, 100, 0, 0, 7)))
            player.client.write(RunClientScript(script = 20392))
            player.client.write(RunClientScript(script = 20093, args = arrayOf(40)))
            player.client.write(RunClientScript(script = 12297))
            player.client.write(RunClientScript(script = 3373, args = arrayOf(1014)))
            player.client.write(RunClientScript(script = 7466))
            player.client.write(RunClientScript(script = 9945))
            player.client.write(RunClientScript(script = 3543, args = arrayOf(1)))
            player.client.write(RunClientScript(script = 9542))
            player.client.write(RunClientScript(script = 7466))
            player.client.write(RunClientScript(script = 17838))
            player.client.write(RunClientScript(script = 18954, args = arrayOf(7, 0)))
            player.client.write(RunClientScript(script = 3373, args = arrayOf(1014)))
            player.client.write(RunClientScript(script = 8178))
            player.client.write(RunClientScript(script = 18468))
            player.client.write(RunClientScript(script = 16300, args = arrayOf(1)))
            player.client.write(RunClientScript(script = 20611))
            player.client.write(RunClientScript(script = 15997))
            logger.info("ui.loginScripts: sent 25 login clientscript calls")
        }
        player.client.write(ClientSetvarcLarge(779, 2699))
        player.interfaces.text(id = 187, component = 7, text = "Harmony")
        player.interfaces.text(id = 1416, component = 6, text = "Harmony")
        player.client.write(ClientSetvarcLarge(2771, 52727066))
        player.interfaces.events(id = 1477, component = 65, from = 1, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 99, from = 1, to = 1, mask = 6)
        player.interfaces.events(id = 1477, component = 71, from = 1, to = 1, mask = 4)
        player.interfaces.events(id = 1477, component = 99, from = 1, to = 1, mask = 6)
        player.interfaces.events(id = 1477, component = 26, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 17, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 1477, component = 17, from = 0, to = 46, mask = 2)
        player.interfaces.events(id = 1477, component = 896, from = 0, to = 1000, mask = 2)
        player.interfaces.events(id = 1477, component = 912, from = 0, to = 0, mask = 2)
        player.interfaces.events(id = 1477, component = 1, from = 0, to = 12, mask = 2)
        player.interfaces.events(id = 1477, component = 2, from = 0, to = 300, mask = 2)
        player.interfaces.events(id = 1477, component = 63, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 63, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 63, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 61, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 89, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 89, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 89, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 87, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 84, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 84, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 84, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 82, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 79, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 79, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 79, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 77, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 74, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 74, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 74, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 72, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 69, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 69, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 69, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 67, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 98, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 98, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 98, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 98, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 92, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 423, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 423, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 423, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 423, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 418, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 424, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 433, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 433, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 433, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 433, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 429, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 434, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 443, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 443, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 443, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 443, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 439, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 444, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 453, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 453, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 453, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 453, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 449, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 454, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 463, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 463, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 463, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 463, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 459, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 464, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 473, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 473, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 473, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 473, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 469, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 474, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 483, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 483, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 483, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 483, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 479, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 484, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 493, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 493, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 493, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 493, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 489, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 494, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 412, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 412, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 412, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 412, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 407, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 413, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 106, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 106, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 106, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 106, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 101, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 106, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 107, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 150, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 150, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 150, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 150, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 145, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 150, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 151, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 161, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 161, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 161, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 161, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 156, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 161, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 162, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 172, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 172, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 172, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 172, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 167, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 173, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 183, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 183, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 183, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 183, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 178, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 183, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 184, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 194, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 194, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 194, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 194, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 189, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 194, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 195, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 205, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 205, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 205, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 205, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 200, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 205, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 206, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 216, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 216, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 216, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 216, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 211, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 216, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 217, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 227, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 227, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 227, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 227, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 222, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 228, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 238, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 238, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 238, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 238, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 233, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 238, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 239, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 249, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 249, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 249, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 249, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 244, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 249, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 250, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 260, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 260, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 260, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 260, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 255, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 260, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 261, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 271, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 271, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 271, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 271, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 266, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 272, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 282, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 282, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 282, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 282, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 277, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 283, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 117, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 117, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 117, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 117, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 112, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 117, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 118, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 128, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 128, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 128, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 128, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 123, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 129, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 292, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 292, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 292, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 292, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 288, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 294, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 303, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 303, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 303, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 303, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 298, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 304, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 139, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 139, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 139, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 139, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 134, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 139, from = 16, to = 16, mask = 2)
        player.interfaces.events(id = 1477, component = 140, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 314, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 314, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 314, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 314, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 309, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 315, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 548, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 548, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 548, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 548, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 543, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 549, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 504, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 504, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 504, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 504, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 499, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 505, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 515, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 515, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 515, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 515, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 510, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 516, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 559, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 559, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 559, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 559, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 554, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 560, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 537, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 537, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 537, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 537, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 532, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 538, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 641, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 641, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 641, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 641, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 751, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 751, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 751, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 736, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 736, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 736, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 727, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 727, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 727, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 741, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 737, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 742, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 618, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 618, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 618, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 618, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 597, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 597, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 597, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 622, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 622, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 622, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 628, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 628, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 628, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 610, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 610, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 610, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 635, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 635, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 635, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 601, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 601, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 601, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 823, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 823, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 823, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 696, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 696, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 696, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 696, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 631, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 631, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 631, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 649, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 649, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 649, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 649, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 712, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 712, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 712, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 708, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 717, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 51, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 51, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 51, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 51, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 28, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 657, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 657, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 657, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 325, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 325, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 325, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 325, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 320, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 326, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 336, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 336, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 336, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 336, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 331, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 721, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 721, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 721, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 526, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 526, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 526, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 526, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 521, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 527, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 701, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 6, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 11, to = 11, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 13, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 701, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 665, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 665, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 665, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 669, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 669, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 669, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 346, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 346, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 346, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 346, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 341, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 351, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 673, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 673, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 673, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 606, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 606, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 606, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 706, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 706, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 706, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 706, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 681, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 681, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 681, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 677, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 677, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 677, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 357, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 357, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 357, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 357, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 352, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 362, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 645, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 645, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 645, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 368, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 368, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 368, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 368, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 363, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 373, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 593, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 593, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 593, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 379, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 379, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 379, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 379, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 374, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 384, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 614, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 614, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 614, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 614, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 390, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 390, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 390, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 390, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 385, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 395, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 401, from = 1, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 401, from = 11, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 401, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 401, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 396, from = 65535, to = 65535, mask = 2097152)
        player.interfaces.events(id = 1477, component = 406, from = 0, to = 1, mask = 2)
        player.interfaces.events(id = 1477, component = 731, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 731, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 731, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 589, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 589, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 589, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 585, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 585, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 585, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 577, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 577, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 577, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 653, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 653, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 653, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 573, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 573, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 573, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 685, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 685, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 685, from = 3, to = 4, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 1, to = 2, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 6, to = 7, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 11, to = 11, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 13, to = 13, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 0, to = 0, mask = 9175040)
        player.interfaces.events(id = 1477, component = 569, from = 3, to = 4, mask = 9175040)
        player.client.write(RunClientScript(script = 1264, args = arrayOf("PMod PvM Event", "Friday 18th June, 20:00 Game Time", "Nex: Angel of Death boss mass", "", "Nex lobby, God Wars Dungeon", "w88", "Pippyspot & Boss Guild", "Boss Guild", "", "", 1321)))
        player.client.write(RunClientScript(script = 3529))
        player.interfaces.events(id = 1430, component = 66, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 69, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 79, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 82, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 92, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 95, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 105, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 108, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 118, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 121, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 131, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 134, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 144, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 147, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 157, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 160, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 170, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 173, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 183, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 186, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 196, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 199, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 209, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 212, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 222, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 225, from = 65535, to = 65535, mask = 11239422)
        player.interfaces.events(id = 1430, component = 235, from = 65535, to = 65535, mask = 2098176)
        player.interfaces.events(id = 1430, component = 238, from = 65535, to = 65535, mask = 2098176)
        player.interfaces.events(id = 1430, component = 19, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1430, component = 13, from = 65535, to = 65535, mask = 8650758)
        player.interfaces.events(id = 1430, component = 26, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1430, component = 18, from = 65535, to = 65535, mask = 2046)
        player.interfaces.events(id = 1430, component = 59, from = 65535, to = 65535, mask = 8650754)
        player.interfaces.events(id = 1430, component = 261, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 260, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 259, from = 65535, to = 65535, mask = 1984)
        player.interfaces.events(id = 1430, component = 258, from = 65535, to = 65535, mask = 448)
        player.interfaces.events(id = 1430, component = 38, from = 65535, to = 65535, mask = 0)
        player.interfaces.events(id = 1430, component = 38, from = 65535, to = 65535, mask = 2)
        player.interfaces.events(id = 1458, component = 39, from = 0, to = 38, mask = 8388610)
        player.interfaces.events(id = 1465, component = 15, from = 65535, to = 65535, mask = 8388608)
        player.interfaces.events(id = 1460, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1881, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1888, component = 1, from = 0, to = 211, mask = 8592390)
        player.interfaces.events(id = 1452, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1461, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1884, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1885, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1887, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1886, component = 1, from = 0, to = 211, mask = 8617038)
        player.interfaces.events(id = 1883, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1449, component = 1, from = 0, to = 211, mask = 8616966)
        player.interfaces.events(id = 1882, component = 1, from = 0, to = 211, mask = 8616966)

        if (System.getProperty("opennxt.experiment.ui.armAll") == "true") {
            player.interfaces.armAllOpenComponents()
        }

        System.getProperty("opennxt.experiment.ui.armPanels")?.let { mode ->
            if (mode == "gap" || mode == "all") player.interfaces.armPanelInteractivity(mode)
            else logger.warn("ui.armPanels=$mode is not a mode; use 'gap' or 'all'. Nothing armed.")
        }

        System.getProperty("opennxt.experiment.ui.optionsVarbit")?.let { raw ->
            val v = raw.toIntOrNull()
            if (v == null || v !in 0..1) {
                logger.warn("ui.optionsVarbit=$raw ignored: must be 0 or 1")
            } else {
                val value = v shl 9
                player.client.write(VarpLarge(689, value))
                logger.info("ui.optionsVarbit: varp 689 = $value (varbit 1899 = $v)")
            }
        }

        System.getProperty("opennxt.experiment.ui.varps")?.let { raw ->
            var ok = 0
            val bad = ArrayList<String>()
            for (pair in raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val parts = pair.split(':')
                val id = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val value = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (parts.size != 2 || id == null || value == null) {
                    bad += pair
                    continue
                }
                player.client.write(VarpLarge(id, value))
                logger.info("ui.varps: varp $id = $value")
                ok++
            }
            if (bad.isNotEmpty())
                logger.warn("ui.varps: ignored ${bad.size} malformed entries $bad (expected id:value,id:value)")
            logger.info("ui.varps: sent $ok extra varp(s)")
        }

        if (System.getProperty("opennxt.experiment.ui.varpResend") != "false") {
            val n = TODORefactorThisClass.sendDefaultVarps(client, stored = { id -> varpOverride(id) })
            logger.info("ui.varpResend: re-sent $n varp(s) after the gameframe was built")
        }

        val varbit27169At = TODORefactorThisClass.varbit27169At()
        if (varbit27169At != "late" && System.getProperty("opennxt.experiment.ui.varbit27169") == "true") {
            logger.warn("ui.varbit27169[$varbit27169At]: default varps sent 3680 = " +
                "${TODORefactorThisClass.varp3680Default()} before openTop(1477)")
        }
        if (System.getProperty("opennxt.experiment.ui.varbit27169") == "true" && varbit27169At != "early") {
            val v = 1074501633 or (1 shl 21)
            player.client.write(VarpLarge(3680, v))
            logger.warn("ui.varbit27169: sent varp 3680 = $v (varbit 27169 = 1)")
        }
        if (System.getProperty("opennxt.experiment.hudMount.probe67") == "true") {
            probe67Countdown = PROBE67_DELAY_TICKS
        }
        if (System.getProperty("opennxt.experiment.hudMount.relayout") != "false") {
            relayoutCountdown = hudRepair(RELAYOUT_DELAY_TICKS)
        }
        if (System.getProperty("opennxt.experiment.hudMount.redim1003") == "true") {
            redim1003Countdown = hudRepair(RELAYOUT_DELAY_TICKS + RELAYOUT_AGAIN_TICKS + 2)
        }
        if (System.getProperty("opennxt.experiment.hudMount.rerun8110") == "true") {
            rerun8110Countdown = hudRepair(RELAYOUT_DELAY_TICKS + RELAYOUT_AGAIN_TICKS + 2)
        }
        val probeComp = System.getProperty("opennxt.experiment.hudMount.probe.comp")?.toIntOrNull()
        if (probeComp != null) {
            probeCompId = probeComp
            probeCompCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 4)
        }
        if (System.getProperty("opennxt.experiment.ui.armFrames") != "false") {
            armFramesCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 4)
        }
        System.getProperty("opennxt.experiment.ui.close8323")?.toIntOrNull()?.let {
            close8323Panel = it
            close8323Countdown = hudRepair(RELAYOUT_DELAY_TICKS + 6)
        }
        System.getProperty("opennxt.experiment.hudMount.rebuild")?.let { raw ->
            val ids = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (ids.isNotEmpty()) {
                rebuildPanels = ids
                rebuildCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 6)
            }
        }

        if (System.getProperty("opennxt.experiment.ui.chatSetup") == "true") {
            player.client.write(RunClientScript(script = 2704, args = arrayOf()))
            logger.warn { "ui.chatSetup: sent RunClientScript(2704)" }
        }
        System.getProperty("opennxt.experiment.ui.noClickThrough")?.let { raw ->
            raw.split(',').map { it.trim() }.filter { it.contains(':') }.forEach { pair ->
                val (i, c) = pair.split(':').map { it.trim().toInt() }
                player.client.write(RunClientScript(script = 7684, args = arrayOf((i shl 16) or c)))
                logger.warn { "ui.noClickThrough: sent RunClientScript(7684, [$i:$c])" }
            }
        }

        if (panelApplyAt == "late" || panelApplyAt == "both") applyPanelVisibility("late")

        if (System.getProperty("opennxt.experiment.unhideBackpack.after") == "true") {
            backpackUnhideCountdown = BACKPACK_UNHIDE_DELAY_TICKS
            if (BACKPACK_UNHIDE_DELAY_TICKS <= 0) player.interfaces.hide(id = 1477, component = 101, hidden = false)
            logger.warn("backpack unhide re-asserted after panelApply; minimap and action bars may not lay out")
        }

        PlayerInventory.sendBackpack(player)
        PlayerInventory.sendLoginContainers(player)

        if (System.getProperty("opennxt.experiment.ui.optionsMenu") == "true") {
            logger.info("options-menu experiment: sent RunClientScript(9922)")
            player.client.write(RunClientScript(script = 9922))
        }

        if (System.getProperty("opennxt.experiment.ui.optionsOpen") == "true") {
            logger.info("options-menu experiment 2: opening interface 274 on 1477:808")
            player.interfaces.open(id = 274, parent = 1477, component = 808, walkable = true)
            player.client.write(RunClientScript(script = 9922))
        }

        val slotBatchProp = System.getProperty("opennxt.experiment.ui.slotBatch")
        if (slotBatchProp != null) {
            val only = slotBatchProp.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
            val batch = listOf(
                28 to 1177,
                37 to 1422,
                56 to 279,
                596 to 279,
                690 to 1431,
                703 to 1622,
                880 to 1476
            )
            val chosen = if (only.isEmpty()) batch else batch.filter { it.first in only }
            logger.info("slot-batch experiment: opening ${chosen.size} of ${batch.size} panels" +
                (if (only.isEmpty()) "" else " (restricted to $only)"))
            for ((comp, iface) in chosen) {
                logger.info("slot-batch: 1477:$comp <- interface $iface")
                player.interfaces.open(id = iface, parent = 1477, component = comp,
                    walkable = true)
            }
        }

        if (System.getProperty("opennxt.experiment.ui.loginTail") != "false") {
            player.client.write(com.opennxt.net.game.serverprot.MessageGame(0, "Welcome to RuneScape."))
            player.client.write(com.opennxt.net.game.serverprot.ChatFilterSettingsPrivatechat(0))
            player.client.write(com.opennxt.net.game.serverprot.FriendlistLoaded)
            logger.info { "ui.loginTail: sent MESSAGE_GAME welcome + CHAT_FILTER_SETTINGS_PRIVATECHAT(0) + FRIENDLIST_LOADED" }
        }

        System.getProperty("opennxt.experiment.ui.showPanels")?.let { raw ->
            val ids = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (ids.isNotEmpty()) {
                showPanelsList = ids
                showPanelsCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 7)
            }
        }
        (System.getProperty("opennxt.experiment.ui.fillPanels") ?: "off").lowercase().let { mode ->
            if (mode == "books") {
                logger.warn { "ui.fillPanels=books is deprecated; treating as \"all\"" }
            }
            if (mode == "books" || mode == "all") {
                val fills = com.opennxt.content.impl.PanelCloseWiring.FILL_PANELS
                var opened = 0
                for ((dock, iface, label) in fills) {
                    if (iface == 1622 && System.getProperty("opennxt.experiment.ui.fillPanels.loot") != "true") {
                        logger.info { "ui.fillPanels: skipped 1477:$dock ($label), loot popup" }
                        continue
                    }
                    if (player.interfaces.hasSubAt(1477, dock)) {
                        logger.info { "ui.fillPanels: skipped 1477:$dock ($label), already occupied" }
                        continue
                    }
                    logger.info { "ui.fillPanels: 1477:$dock <- $iface  ($label)" }
                    player.interfaces.open(id = iface, parent = 1477, component = dock, walkable = true, native949 = true)
                    opened++
                }
                logger.warn { "ui.fillPanels[$mode]: opened $opened name-matched fill(s)" }
            } else if (mode.isNotEmpty() && mode != "off") {
                logger.warn { "opennxt.experiment.ui.fillPanels=$mode is not books|all|off; ignored" }
            }
        }

        if (System.getProperty("opennxt.experiment.ui.varp3680Late") == "true") {
            varp3680LateCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 5)
        }

        if (System.getProperty("opennxt.experiment.ui.rebuildInv") == "true") {
            rebuildInvCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 6)
        }

        if (System.getProperty("opennxt.experiment.ui.fullPack") != "false") {
            fullPackCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 4)
        }

        if (System.getProperty("opennxt.experiment.ui.invResend") == "true") {
            invResendCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 3)
        }

        if (System.getProperty("opennxt.experiment.ui.ribbon") == "true") {
            ribbonCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 6)
        }

        if (System.getProperty("opennxt.experiment.ui.ribbonBuild") != "false") {
            ribbonBuildCountdown = hudRepair(RELAYOUT_DELAY_TICKS + RELAYOUT_AGAIN_TICKS + 4)
        }

        System.getProperty("opennxt.experiment.ui.backpackProbe")?.trim()?.lowercase()?.let { mode ->
            if (mode in setOf("size", "unhide", "reopen")) {
                backpackProbeMode = mode
                backpackProbeCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 8)
            } else {
                logger.warn { "ui.backpackProbe=$mode is not size|unhide|reopen; ignored" }
            }
        }

        if (System.getProperty("opennxt.experiment.ui.barRelayoutAfterRing") == "true") {
            barRelayoutCountdown = hudRepair(RELAYOUT_DELAY_TICKS + 8)
        }

        if (System.getProperty("opennxt.experiment.ui.runEnergy") != "false") {
            runEnergyCountdown = RUN_ENERGY_DELAY_TICKS
        }

        if (com.opennxt.content.impl.Replay949.enabled && !com.opennxt.content.impl.Replay949.only) {
            replay949Cursor = 0
            replay949Countdown = com.opennxt.content.impl.Replay949.delayTicks
            logger.warn { "replay949: ${com.opennxt.content.impl.Replay949.size()} packet(s) start in ${replay949Countdown} tick(s)" }
        }

    }

    override fun tick() {
        runCatching { flushOwedBackpack() }.onFailure { logger.warn(it) { "could not send $name's deferred backpack" } }
        if (statBurstCountdown > 0) {
            statBurstCountdown--
            if (statBurstCountdown == 0) {
                logger.warn {
                    "sending deferred UPDATE_STAT burst (${WorldPlayer.STAT_SEND_DELAY_TICKS} tick(s) after REBUILD_NORMAL)"
                }
                stats.init()
            }
        }

        if (rerun8110Countdown > 0) {
            rerun8110Countdown--
            if (rerun8110Countdown == 0) {
                client.write(RunClientScript(script = 8110, args = arrayOf(1003)))
                logger.warn {
                    "hudMount.rerun8110: sent RunClientScript(8110, [1003]) after ${WorldPlayer.RERUN_8110_DELAY_TICKS} tick(s)"
                }
            }
        }

        if (probe67Countdown > 0) {
            probe67Countdown--
            if (probe67Countdown == 0) {
                interfaces.hide(id = 1477, component = 67, hidden = false)
                client.write(RunClientScript(script = 11145, args = arrayOf(352, 128, 0, 0, 96796739)))
                client.write(RunClientScript(script = 13268, args = arrayOf(464, 300, 0, 0, 96796739)))
                logger.warn {
                    "hudMount.probe67: forced 1477:67 visible, 352x128 at (464,300), " +
                        "${WorldPlayer.PROBE67_DELAY_TICKS} tick(s) after login"
                }
            }
        }

        if (relayoutCountdown > 0) {
            relayoutCountdown--
            if (relayoutCountdown == 0) {
                client.write(RunClientScript(script = 8884, args = arrayOf(RELAYOUT_MODE)))
                if (RELAYOUT_AGAIN_TICKS > 0 && relayoutAgainCountdown == 0) relayoutAgainCountdown = RELAYOUT_AGAIN_TICKS + 1
                logger.warn {
                    "hudMount.relayout: sent RunClientScript(8884, [$RELAYOUT_MODE])"
                }
            }
        }

        if (replay949Countdown > 0) {
            replay949Countdown--
            if (replay949Countdown == 0) {
                val left = com.opennxt.content.impl.Replay949.step(this, replay949Cursor)
                replay949Cursor = com.opennxt.content.impl.Replay949.size() - left
                if (left > 0) replay949Countdown = 1
                else {
                    logger.warn { "replay949: done, ${replay949Cursor} packet(s) replayed" }
                    if (com.opennxt.content.impl.Replay949.only) {
                        PlayerInventory.sendBackpack(this)
                        PlayerInventory.sendWorn(this)
                        logger.warn { "replay949[only]: sent the player's backpack and worn items" }
                    }
                }
            }
        }

        if (relayoutAgainCountdown > 0) {
            relayoutAgainCountdown--
            if (relayoutAgainCountdown == 0) {
                client.write(RunClientScript(script = 8884, args = arrayOf(RELAYOUT_MODE)))
                logger.warn { "hudMount.relayout.again: re-sent RunClientScript(8884, [$RELAYOUT_MODE]) after $RELAYOUT_AGAIN_TICKS tick(s)" }
            }
        }

        if (redim1003Countdown > 0) {
            redim1003Countdown--
            if (redim1003Countdown == 0) {
                client.write(RunClientScript(script = 8140, args = arrayOf(1003, 0, 0)))
                client.write(RunClientScript(script = 8110, args = arrayOf(1003)))
                logger.warn {
                    "hudMount.redim1003: sent RunClientScript(8140, [1003, 0, 0]) and RunClientScript(8110, [1003])"
                }
            }
        }

        if (fullPackCountdown > 0) {
            fullPackCountdown--
            if (fullPackCountdown == 0) {
                client.write(ClientSetvarcSmall(3475, 1))
                client.write(RunClientScript(script = 3379, args = arrayOf(2, 1, 1)))
                logger.warn {
                    "ui.fullPack: sent SETVARC(3475=1) + RunClientScript(3379, [2, 1, 1])"
                }
            }
        }

        if (rebuildInvCountdown > 0) {
            rebuildInvCountdown--
            if (rebuildInvCountdown == 0) {
                client.write(RunClientScript(script = 8678, args = arrayOf(96534528)))
                logger.warn {
                    "ui.rebuildInv: sent RunClientScript(8678, [1473:0])"
                }
            }
        }

        if (varp3680LateCountdown > 0) {
            varp3680LateCountdown--
            if (varp3680LateCountdown == 0) {
                client.write(VarpLarge(3680, TODORefactorThisClass.varp3680Default()))
                logger.warn {
                    "ui.varp3680Late: re-sent varp 3680"
                }
            }
        }

        if (invResendCountdown > 0) {
            invResendCountdown--
            if (invResendCountdown == 0) {
                com.opennxt.model.entity.player.PlayerInventory.sendBackpack(this)
                logger.warn { "ui.invResend: re-sent the backpack inventory" }
            }
        }

        if (showPanelsCountdown > 0) {
            showPanelsCountdown--
            if (showPanelsCountdown == 0) {
                showPanelsList.forEach { p ->
                    client.write(RunClientScript(script = 3379, args = arrayOf(p, 1, 1)))
                }
                logger.warn {
                    "ui.showPanels: sent RunClientScript(3379, [panel, 1, 1]) for $showPanelsList"
                }
            }
        }

        if (ribbonCountdown > 0) {
            ribbonCountdown--
            if (ribbonCountdown == 1) {
                client.write(RunClientScript(script = 3379, args = arrayOf(1002, 1, 1)))
                logger.warn { "ui.ribbon: sent RunClientScript(3379, [1002, 1, 1])" }
            }
            if (ribbonCountdown == 0) {
                client.write(RunClientScript(script = 8144, args = arrayOf()))
                logger.warn {
                    "ui.ribbon: sent RunClientScript(8144)"
                }
            }
        }

        if (ribbonBuildCountdown > 0) {
            ribbonBuildCountdown--
            if (ribbonBuildCountdown == 0) {
                client.write(RunClientScript(script = 13833, args = arrayOf((1431 shl 16) or 0, (1431 shl 16) or 12, 0)))
                if (System.getProperty("opennxt.experiment.ui.ribbonBuild.position") != "false") {
                    ribbonPositionCountdown = 3 + 1
                } else {
                    logger.warn { "ui.ribbonBuild.position=false: not re-sending RunClientScript(8144)" }
                }
                logger.warn {
                    "ui.ribbonBuild: sent RunClientScript(13833, [1431:0, 1431:12, 0])"
                }
            }
        }

        if (ribbonPositionCountdown > 0) {
            ribbonPositionCountdown--
            if (ribbonPositionCountdown == 0) {
                client.write(RunClientScript(script = 8144, args = arrayOf()))
                logger.warn { "ui.ribbonBuild: sent RunClientScript(8144)" }
            }
        }

        if (backpackProbeCountdown > 0) {
            backpackProbeCountdown--
            if (backpackProbeCountdown == 0) {
                when (backpackProbeMode) {
                    "size" -> {
                        client.write(RunClientScript(script = 11145, args = arrayOf(224, 255, 0, 0, (1473 shl 16) or 0)))
                        logger.warn { "ui.backpackProbe=size: sent RunClientScript(11145, [224, 255, 0, 0, 1473:0])" }
                    }
                    "unhide" -> {
                        interfaces.hide(id = 1477, component = 101, hidden = false)
                        interfaces.hide(id = 1477, component = 103, hidden = false)
                        logger.warn { "ui.backpackProbe=unhide: sent IF_SETHIDE(1477:101, false) + IF_SETHIDE(1477:103, false)" }
                    }
                    "reopen" -> {
                        interfaces.open(id = 1473, parent = 1477, component = 103, walkable = true, native949 = true)
                        logger.warn { "ui.backpackProbe=reopen: re-sent IF_OPENSUB(1473 -> 1477:103)" }
                    }
                }
            }
        }

        if (barRelayoutCountdown > 0) {
            barRelayoutCountdown--
            if (barRelayoutCountdown == 0) {
                client.write(RunClientScript(script = 3379, args = arrayOf(1003, 1, 1)))
                logger.warn { "ui.barRelayoutAfterRing: sent RunClientScript(3379, [1003, 1, 1])" }
            }
        }

        if (runEnergyCountdown > 0) {
            runEnergyCountdown--
            if (runEnergyCountdown == 0) {
                com.opennxt.content.impl.RunToggle.sendLogin(this)
                client.write(com.opennxt.net.game.serverprot.generated.UpdateRunweight(weight = 0))
                if (System.getProperty("opennxt.experiment.ui.loginSingles") != "false") {
                    client.write(com.opennxt.net.game.serverprot.ResetAnims)
                    client.write(com.opennxt.net.game.serverprot.generated.MinimapToggle(mode = 0))
                    client.write(com.opennxt.net.game.serverprot.CamReset)
                    logger.info { "ui.loginSingles: sent RESET_ANIMS, MINIMAP_TOGGLE(0), CAM_RESET" }
                }
                if (System.getProperty("opennxt.experiment.ui.xpBaseline") != "false") {
                    client.write(RunClientScript(script = 5653, args = arrayOf()))
                }
                logger.warn {
                    "ui.runEnergy: sent UPDATE_RUNENERGY and UPDATE_RUNWEIGHT(0)"
                }
            }
        }

        if (armFramesCountdown > 0) {
            armFramesCountdown--
            if (armFramesCountdown == 0) {
                com.opennxt.content.impl.PanelCloseWiring.armFrameOps(this)
            }
        }

        if (close8323Countdown > 0) {
            close8323Countdown--
            if (close8323Countdown == 0) {
                client.write(RunClientScript(script = 8323, args = arrayOf(close8323Panel, 0)))
                logger.warn {
                    "ui.close8323: sent RunClientScript(8323, [$close8323Panel, 0])"
                }
            }
        }

        if (rebuildCountdown > 0) {
            rebuildCountdown--
            if (rebuildCountdown == 0) {
                rebuildPanels.forEach { p ->
                    client.write(RunClientScript(script = 8409, args = arrayOf(p)))
                }
                relayoutCountdown = 2
                logger.warn {
                    "hudMount.rebuild: sent RunClientScript(8409) for panels $rebuildPanels; relayout in 2 ticks"
                }
            }
        }

        if (probeCompCountdown > 0) {
            probeCompCountdown--
            if (probeCompCountdown == 0) {
                interfaces.hide(id = 1477, component = probeCompId, hidden = false)
                client.write(RunClientScript(script = 11145, args = arrayOf(352, 128, 0, 0, (1477 shl 16) or probeCompId)))
                client.write(RunClientScript(script = 13268, args = arrayOf(464, 300, 0, 0, (1477 shl 16) or probeCompId)))
                logger.warn {
                    "hudMount.probe.comp: forced 1477:$probeCompId visible, 352x128 at (464,300)"
                }
            }
        }

        if (backpackUnhideCountdown > 0) {
            backpackUnhideCountdown--
            if (backpackUnhideCountdown == 0) {
                interfaces.hide(id = 1477, component = 101, hidden = false)
                logger.warn {
                    "backpack unhide re-asserted after ${WorldPlayer.BACKPACK_UNHIDE_DELAY_TICKS} tick(s)"
                }
            }
        }

        val ranThisTick = entity.movement.processPlayerTick()

        com.opennxt.content.impl.RunToggle.tick(this, ranThisTick)

        viewport.rebuildIfNeeded()

        val opcode = OpenNXT.protocol.serverProtNames.values["PLAYER_INFO"]
        if (opcode == null) {
            if (!playerInfoUnmappedWarned) {
                playerInfoUnmappedWarned = true
                logger.warn {
                    "Build ${OpenNXT.protocol.effectiveBuild} has no PLAYER_INFO opcode; players will not be updated. " +
                        "Add it to data/prot/<build>/serverProtNames.toml"
                }
            }
        } else {
            playerInfoTicks++

            if (System.getProperty("opennxt.experiment.appearanceResend") == "true" &&
                (playerInfoTicks == 10 || playerInfoTicks == 30)
            ) {
                viewport.cachedAppearanceHashes[entity.index] = null
            }

            val infoBuf = PlayerInfoEncoder.createBufferFor(this)

            val n = infoBuf.readableBytes()
            if (playerInfoTicks <= 4 || n > 8) {
                val mask =
                    if (n > 5) String.format("0x%02x", infoBuf.getByte(5).toInt() and 0xff) else "n/a"
                logger.info {
                    "PLAYER_INFO tick $playerInfoTicks: $n byte(s), mask byte=$mask"
                }
            }

            PlayerInfoEncoder.lastReport?.let { report ->
                if (!report.isEmpty()) {
                    logger.info {
                        "PLAYER_INFO ext-info -> $name: $report [packet ${n}B; cumulative " +
                            "${com.opennxt.model.entity.updating.ExtendedInfoTrace.counts()}]"
                    }
                }
            }

            client.write(UnidentifiedPacket(OpcodeWithBuffer(opcode, infoBuf)))
        }

        NpcInfoEncoder.writeTo(this)

        GroundItemTransmitter.sync(this)
        LocChanges.syncFor(this)
        Projectiles.flush(this)

        if (LocClipping.enabled) {
            try {
                val loc = entity.location
                val applied = LocClipping.applySceneAt(loc.x, loc.y, loc.plane, maxSquares = Int.MAX_VALUE)
                val bridgeApplied = if (loc.plane < 3) {
                    LocClipping.applySceneAt(loc.x, loc.y, loc.plane + 1, maxSquares = Int.MAX_VALUE)
                } else 0
                val totalApplied = applied + bridgeApplied
                if (totalApplied > 0) {
                    logger.info {
                        "loc clipping: applied $applied+$bridgeApplied map square(s) around (${loc.x},${loc.y}," +
                            "plane ${loc.plane}) for $name; ${LocClipping.loadedSquares()} loaded, " +
                            "${com.opennxt.model.map.CollisionMap.walledTiles()} walled tile(s)"
                    }
                }
            } catch (t: Throwable) {
                if (!locClippingWarned) {
                    locClippingWarned = true
                    logger.error(t) {
                        "loc clipping failed for $name; -Dopennxt.experiment.locClipping=false to disable"
                    }
                }
            }
        }

        viewport.resetForNextTransmit()

        client.write(ServerTickEnd)
    }

    private var backpackUnhideCountdown = 0

    private var rerun8110Countdown = 0

    private var probe67Countdown = 0

    private var relayoutCountdown = 0

    private var relayoutAgainCountdown = 0

    private var replay949Countdown = 0

    private var replay949Cursor = 0

    private var rebuildInvCountdown = 0

    private var fullPackCountdown = 0

    private var varp3680LateCountdown = 0

    private var invResendCountdown = 0

    private var showPanelsCountdown = 0

    private var showPanelsList: List<Int> = emptyList()

    private var ribbonCountdown = 0

    private var armFramesCountdown = 0

    private var close8323Countdown = 0

    private var close8323Panel = -1

    private var rebuildCountdown = 0

    private var rebuildPanels: List<Int> = emptyList()

    private var probeCompCountdown = 0

    private var probeCompId = -1

    private var redim1003Countdown = 0

    private var ribbonBuildCountdown = 0

    private var ribbonPositionCountdown = 0

    private var backpackProbeCountdown = 0

    private var backpackProbeMode = ""

    private var barRelayoutCountdown = 0

    private var runEnergyCountdown = 0

    private var statBurstCountdown = 0

    private var playerInfoTicks = 0

    companion object {
        @Volatile
        private var playerInfoUnmappedWarned = false

        val RELAYOUT_DELAY_TICKS: Int = System.getProperty("opennxt.experiment.hudMount.relayout.delayTicks")?.toIntOrNull() ?: 20

        val RELAYOUT_MODE: Int = System.getProperty("opennxt.experiment.hudMount.relayout.mode")?.toIntOrNull() ?: 15

        val HUD_REPAIR: Boolean = System.getProperty("opennxt.experiment.ui.hudRepair") == "true"

        fun hudRepair(ticks: Int): Int = if (HUD_REPAIR) ticks else 0

        val RUN_ENERGY_DELAY_TICKS: Int = System.getProperty("opennxt.experiment.ui.runEnergy.delayTicks")?.toIntOrNull() ?: 11

        val RELAYOUT_AGAIN_TICKS: Int = System.getProperty("opennxt.experiment.hudMount.relayout.again")?.toIntOrNull() ?: 10

        val PROBE67_DELAY_TICKS: Int = System.getProperty("opennxt.experiment.hudMount.probe67.delayTicks")?.toIntOrNull() ?: 8

        val RERUN_8110_DELAY_TICKS: Int = System.getProperty("opennxt.experiment.hudMount.rerun8110.delayTicks")?.toIntOrNull() ?: 3
                val BACKPACK_UNHIDE_DELAY_TICKS: Int =
            System.getProperty("opennxt.experiment.unhideBackpack.after.delayTicks")?.toIntOrNull() ?: 2

        val STAT_SEND_DELAY_TICKS: Int =
            System.getProperty("opennxt.experiment.sendStats.delayTicks")?.toIntOrNull() ?: 2
    }
}

sealed class EquipResult {
    object Disabled : EquipResult()

    data class NoSuchItem(val itemId: Int) : EquipResult()

    data class NotEquipable(val itemId: Int, val name: String?) : EquipResult()

    data class SlotOutOfRange(val itemId: Int, val name: String?, val slot: Int) : EquipResult()

    data class Equipped(val itemId: Int, val name: String?, val slot: Int, val replaced: Int?) : EquipResult()
}

object EquipChatCommand : com.opennxt.net.game.pipeline.GamePacketHandler<WorldPlayer, com.opennxt.net.game.clientprot.MessagePublic> {
    private val logger = KotlinLogging.logger { }

    val prefix: String
        get() = System.getProperty("opennxt.equip.command")?.trim()?.takeIf { it.isNotEmpty() } ?: "::equip"

    fun matches(text: String): Boolean {
        val t = text.trim()
        return t.equals(prefix, ignoreCase = true) || t.startsWith("$prefix ", ignoreCase = true)
    }

    fun run(player: WorldPlayer, text: String): String? {
        if (!matches(text)) return null
        val argument = text.trim().substring(prefix.length).trim()
        if (argument.isEmpty()) {
            val worn = player.worn
            return "worn (inv ${PlayerInventory.wornInv}, ${worn.size} slots): " +
                (0 until worn.size).mapNotNull { slot -> worn[slot]?.let { "$slot=${it.id}" } }
                    .ifEmpty { listOf("empty") }.joinToString(" ")
        }
        val power = com.opennxt.model.permissions.Powers.SPAWN_ITEM
        if (!player.hasPower(power)) {
            return "refused: requires the '$power' power (granted in data/config/mods.json)"
        }
        if (argument.equals("clear", ignoreCase = true)) {
            return "unequipped ${player.unequipAll()} item(s)"
        }
        val id = argument.toIntOrNull() ?: return "not a number: '$argument' (usage: $prefix <itemId>)"
        return when (val result = player.equipItem(id)) {
            is EquipResult.Disabled ->
                "refused: equipping is disabled (-Dopennxt.experiment.equip=true to enable)"
            is EquipResult.NoSuchItem -> "refused: no item with id ${result.itemId}"
            is EquipResult.NotEquipable ->
                "refused: item ${result.itemId} (${result.name ?: "unnamed"}) is not equipable"
            is EquipResult.SlotOutOfRange ->
                "refused: item ${result.itemId} has equip slot ${result.slot}, outside the " +
                    "${PlayerInventory.WORN_SIZE}-slot container"
            is EquipResult.Equipped ->
                "equipped ${result.itemId} (${result.name ?: "unnamed"}) in cache slot ${result.slot}" +
                    (if (result.replaced != null) ", replacing ${result.replaced}" else "")
        }
    }

    override fun handle(context: WorldPlayer, packet: com.opennxt.net.game.clientprot.MessagePublic) {
        val outcome = run(context, packet.text)
        if (outcome == null) {
            com.opennxt.net.game.handlers.MessagePublicHandler.handle(context, packet)
            return
        }
        if (outcome.startsWith("equipped") || outcome.startsWith("unequipped")) {
            logger.warn { "equip command from ${context.name} (rights ${context.rights}): '${packet.text.trim()}' -> $outcome" }
        } else {
            logger.info { "equip command from ${context.name}: '${packet.text.trim()}' -> $outcome" }
        }
    }
}
