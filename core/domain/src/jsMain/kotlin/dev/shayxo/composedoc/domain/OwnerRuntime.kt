package dev.shayxo.composedoc.domain

private val eventLoopOwner = Any()
internal actual fun ownerThreadToken(): Any = eventLoopOwner
internal actual fun monotonicMillis(): Long = (js("performance.now()") as Double).toLong()
