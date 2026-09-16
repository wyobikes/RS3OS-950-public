package com.opennxt.net.game.serverprot

object ZoneCoord {
    fun coord(worldX: Int, worldY: Int): Int = ((worldX and 7) shl 4) or (worldY and 7)

    fun shapeRotation(shape: Int, rotation: Int): Int {
        require(shape in 0..31) { "loc shape must be 0..31 (5 bits at (v shr 2) and 0x1f), was $shape" }
        require(rotation in 0..3) { "loc rotation must be 0..3 (2 bits at v and 3), was $rotation" }
        val packed = (shape shl 2) or rotation
        check(packed and 0x80 == 0) { "shapeRotation bit 7 must be clear (extra-block grammar unrecovered): $packed" }
        return packed
    }
}
