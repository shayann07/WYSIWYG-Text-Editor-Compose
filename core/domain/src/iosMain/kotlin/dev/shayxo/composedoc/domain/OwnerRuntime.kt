package dev.shayxo.composedoc.domain

import kotlin.native.concurrent.ThreadLocal
import kotlin.time.TimeSource

@ThreadLocal
private val nativeOwnerToken = Any()
private val monotonicOrigin = TimeSource.Monotonic.markNow()

internal actual fun ownerThreadToken(): Any = nativeOwnerToken

internal actual fun monotonicMillis(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds
