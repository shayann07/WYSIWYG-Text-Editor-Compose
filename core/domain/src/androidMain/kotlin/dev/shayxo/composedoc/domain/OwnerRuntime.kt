package dev.shayxo.composedoc.domain

internal actual fun ownerThreadToken(): Any = Thread.currentThread()

internal actual fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
