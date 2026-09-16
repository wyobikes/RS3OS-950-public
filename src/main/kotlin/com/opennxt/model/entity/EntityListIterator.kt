package com.opennxt.model.entity

class EntityListIterator<T : Entity>(val list: EntityList<T>) : Iterator<T> {
    private var index = 0

    override fun hasNext(): Boolean {
        var i = index + 1

        while (i <= list.capacity) {
            if (list[i] != null)
                return true
            i++
        }
        return false
    }

    override fun next(): T {
        while (index <= list.capacity) {
            val current = list[++index]
            if (current != null) return current
        }

        throw NoSuchElementException("Reached end of list")
    }
}
