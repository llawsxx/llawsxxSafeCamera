package com.llawsxx.safecamera.recording

import java.util.concurrent.atomic.AtomicBoolean

internal class FinalizationGate {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
}
