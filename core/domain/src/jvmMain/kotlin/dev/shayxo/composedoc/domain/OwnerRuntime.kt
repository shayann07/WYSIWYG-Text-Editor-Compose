package dev.shayxo.composedoc.domain

private const val NANOS_PER_MILLISECOND = 1_000_000

internal actual fun ownerThreadToken(): Any = Thread.currentThread()
internal actual fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND
