package org.stepic.droid.base

import java.util.*
import javax.inject.Inject

class ListenerContainerImpl<T>
@Inject constructor() : ListenerContainer<T> {

    private val listeners: MutableSet<T> = HashSet()

    override fun add(listener: T) {
        listeners.add(listener)
    }

    override fun remove(listener: T) {
        listeners.remove(listener)
    }

    override fun iterator() = listeners.iterator()
}
