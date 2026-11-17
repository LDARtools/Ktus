@file:OptIn(ExperimentalAtomicApi::class)

package com.ldartools.ktus.test

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A copy-on-write list that is safe to append to from several threads (engine threads, the heartbeat, the test
 * thread). Kotlin/Native's ArrayList is not: concurrent appends can corrupt it and crash the test process.
 */
class SyncList<T> : AbstractList<T>() {
    private val ref = AtomicReference<List<T>>(emptyList())

    override val size: Int get() = ref.load().size
    override fun get(index: Int): T = ref.load()[index]

    fun add(element: T) = update { it + element }
    operator fun plusAssign(element: T) { add(element) }
    fun addAll(elements: Collection<T>) = update { it + elements }
    fun clear() = update { emptyList() }
    fun snapshot(): List<T> = ref.load()

    /** Removes and returns the first element, or null. */
    fun removeFirstOrNull(): T? {
        while (true) {
            val current = ref.load()
            if (current.isEmpty()) return null
            if (ref.compareAndSet(current, current.drop(1))) return current.first()
        }
    }

    private inline fun update(change: (List<T>) -> List<T>) {
        while (true) {
            val current = ref.load()
            if (ref.compareAndSet(current, change(current))) return
        }
    }
}

/** A copy-on-write map, safe across threads. */
class SyncMap<K, V> {
    private val ref = AtomicReference<Map<K, V>>(emptyMap())

    operator fun get(key: K): V? = ref.load()[key]
    operator fun set(key: K, value: V) = update { it + (key to value) }
    fun remove(key: K) = update { it - key }
    fun clear() = update { emptyMap() }
    fun getOrPut(key: K, create: () -> V): V {
        ref.load()[key]?.let { return it }
        val value = create()
        while (true) {
            val current = ref.load()
            current[key]?.let { return it }
            if (ref.compareAndSet(current, current + (key to value))) return value
        }
    }

    private inline fun update(change: (Map<K, V>) -> Map<K, V>) {
        while (true) {
            val current = ref.load()
            if (ref.compareAndSet(current, change(current))) return
        }
    }
}
