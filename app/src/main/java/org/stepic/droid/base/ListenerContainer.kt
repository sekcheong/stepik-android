package org.stepic.droid.base

interface ListenerContainer<T>{

    fun add(listener: T)

    fun remove(listener: T)

    fun iterator(): MutableIterator<T>
}
