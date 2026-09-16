package com.opennxt.model.entity.player

import com.opennxt.OpenNXT
import com.opennxt.model.entity.PlayerEntity
import com.opennxt.model.entity.movement.MovementSpeed
import com.opennxt.model.world.MapSize
import com.opennxt.model.world.TileLocation
import com.opennxt.model.world.WorldPlayer
import com.opennxt.net.buf.GamePacketBuilder
import com.opennxt.net.game.GamePacket
import com.opennxt.net.game.serverprot.RebuildNormal

class Viewport(val player: WorldPlayer) {
    val localPlayers = arrayOfNulls<PlayerEntity>(2048)
    val localPlayerIndices = IntArray(2048)
    var localPlayerIndicesCount = 0
    val outPlayerIndices = IntArray(2048)
    var outPlayerIndicesCount = 0
    val regionHashes = IntArray(2048)
    val slotFlags = ByteArray(2048)
    val movementTypes = ByteArray(2048)
    var localAddedPlayers = 0
    val cachedAppearanceHashes = arrayOfNulls<ByteArray>(2048)
    val cachedHeadIconHashes = arrayOfNulls<ByteArray>(2048)

    var sceneRadius = 7
    var baseTile = player.entity.location
    var playerViewingDistance = 14
    var mapSize = MapSize.SIZE_256

    fun init(buf: GamePacketBuilder) {
        val entity = player.entity

        baseTile = entity.location
        if (entity.index < 1 || entity.index >= 2048)
            throw IllegalStateException("Player index must be between 1 and 2047 for ${player.name}: $player.index")

        buf.switchToBitAccess()
        buf.putBits(30, entity.location.tileHash)

        localPlayers[entity.index] = entity
        localPlayerIndicesCount = 0
        outPlayerIndicesCount = 0
        localPlayerIndices[localPlayerIndicesCount++] = entity.index
        for (index in 1 until 2048) {
            if (index == entity.index)
                continue
            val other = OpenNXT.world.getPlayer(index)
            val speed = other?.movement?.currentSpeed ?: MovementSpeed.STATIONARY
            val hash = (other?.location?.regionHash ?: 0) or (speed.id shl 18)
            buf.putBits(20, hash)
            regionHashes[index] = hash
            outPlayerIndices[outPlayerIndicesCount++] = index
            if (speed != MovementSpeed.STATIONARY)
                continue
            slotFlags[index] = (slotFlags[index].toInt() or 0x1).toByte()
        }
        buf.switchToByteAccess()
        moveToRegion(entity.location, mapSize, false)
    }

    private val rebuildThreshold = 96

    fun rebuildIfNeeded() {
        val loc = player.entity.location
        val dx = Math.abs(loc.x - baseTile.x)
        val dy = Math.abs(loc.y - baseTile.y)
        if (dx < rebuildThreshold && dy < rebuildThreshold) return
        moveToRegion(loc, mapSize, sendUpdate = true)
    }

    var sceneEpoch: Int = 0
        private set

    fun moveToRegion(tile: TileLocation, size: MapSize, sendUpdate: Boolean = true) {
        this.mapSize = size
        this.baseTile = tile
        sceneEpoch++

        if (sendUpdate) {
            player.client.write(createPacket())
        }
    }

    fun createPacket(): GamePacket {
        return RebuildNormal(
            unused1 = if (defaultHashes) DEFAULT_UNUSED1 else 0,
            chunkX = baseTile.x / 8,
            unused2 = 0,
            chunkY = baseTile.y / 8,
            npcBits = sceneRadius,
            mapSize = mapSize.id,
            areaType = AREA_TYPE,
            hash1 = if (defaultHashes) DEFAULT_HASH1 else -1,
            hash2 = if (defaultHashes) DEFAULT_HASH2 else -1,
        )
    }

    val zoneOrigin: Int get() = zoneOrigin(mapSize)

    val chunkX: Int get() = baseTile.x / 8

    val chunkY: Int get() = baseTile.y / 8

    fun zoneX(worldX: Int): Int = zoneIndex(worldX, chunkX, mapSize)

    fun zoneY(worldY: Int): Int = zoneIndex(worldY, chunkY, mapSize)

    fun containsTile(worldX: Int, worldY: Int): Boolean =
        contains(worldX, worldY, chunkX, chunkY, mapSize)

    companion object {
        const val AREA_TYPE = 474

        const val DEFAULT_UNUSED1 = 0x7f

        const val DEFAULT_HASH1 = 0x01a00940

        const val DEFAULT_HASH2 = 0x048e23b8

        val defaultHashes: Boolean = System.getProperty("opennxt.compat.rebuildHashes") != "off"

        fun zoneIndex(worldTile: Int, chunk: Int, mapSize: MapSize): Int =
            (worldTile shr 3) - chunk + zoneOrigin(mapSize)

        fun zoneOrigin(mapSize: MapSize): Int = mapSize.size shr 4

        fun contains(worldX: Int, worldY: Int, chunkX: Int, chunkY: Int, mapSize: MapSize): Boolean {
            val span = zoneOrigin(mapSize) * 2
            return zoneIndex(worldX, chunkX, mapSize) in 0 until span &&
                zoneIndex(worldY, chunkY, mapSize) in 0 until span
        }
    }

    fun resetForNextTransmit() {
        localPlayerIndicesCount = 0
        outPlayerIndicesCount = 0
        localAddedPlayers = 0
        for (idx in 1 until 2048) {
            slotFlags[idx] = (slotFlags[idx].toInt() shr 1).toByte()
            val player = localPlayers[idx]
            if (player == null)
                outPlayerIndices[outPlayerIndicesCount++] = idx
            else
                localPlayerIndices[localPlayerIndicesCount++] = idx
        }
    }
}
