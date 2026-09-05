package com.unciv.utils

/**
 * HashMap base for runtimes whose concrete map classes predate the Java 8 Map methods.
 *
 * Kotlin generates type-safe bridges to these methods for subclasses with concrete type arguments.
 * Keeping the implementations here prevents those bridges from calling methods absent from RoboVM.
 */
abstract class RoboVMCompatibleHashMap<K, V> : HashMap<K, V>() {
    @Suppress("UNCHECKED_CAST")
    override fun getOrDefault(key: K, defaultValue: V): V {
        val value: V? = super.get(key)
        return if (value != null || super.containsKey(key)) value as V else defaultValue
    }

    override fun remove(key: K, value: V): Boolean {
        if (!super.containsKey(key) || super.get(key) != value) return false
        super.remove(key)
        return true
    }
}

/** LinkedHashMap counterpart of [RoboVMCompatibleHashMap]. */
abstract class RoboVMCompatibleLinkedHashMap<K, V> : LinkedHashMap<K, V> {
    protected constructor() : super()
    protected constructor(initialCapacity: Int) : super(initialCapacity)

    @Suppress("UNCHECKED_CAST")
    override fun getOrDefault(key: K, defaultValue: V): V {
        val value: V? = super.get(key)
        return if (value != null || super.containsKey(key)) value as V else defaultValue
    }

    override fun remove(key: K, value: V): Boolean {
        if (!super.containsKey(key) || super.get(key) != value) return false
        super.remove(key)
        return true
    }
}
