package io.legado.pack.api

import java.util.Collections

internal fun <T> immutableList(source: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

internal fun <T> immutableSet(source: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(source))

internal fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))