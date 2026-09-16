package com.opennxt.model.entity

class EntityList<T : Entity>(val capacity: Int) : Iterable<T> {
    private val values = arrayOfNulls<Entity>(capacity)

    private var size: Int = 0

    init {
        if (capacity > Short.MAX_VALUE)
            throw IllegalArgumentException("Repository can't be bigger than the max short value")
    }

    fun add(entity: T): Boolean {
        if(isFull()) return false

        for(i in 0 until capacity) {
            if (values[i] != null) continue
            values[i] = entity
            entity.index = i + 1
            size++
            return true
        }
        return false
    }

    fun isFull(): Boolean = size == capacity

    fun size(): Int = size

    fun remove(entity: T) {
        remove(entity.index - 1)
    }

    fun remove(index: Int) {
        if (index < 0) return
        val current = values[index] ?: return

        if (current.index - 1 != index)
            throw IllegalStateException("Entity is in the wrong spot in EntityList. This should never happen.")

        values[index] = null
        current.index = -1
        size--
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T? {
        if (index < 1 || index > capacity)
            return null
        return values[index - 1] as? T
    }

    override fun iterator(): Iterator<T> = EntityListIterator(this)
}
