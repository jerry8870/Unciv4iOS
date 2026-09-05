package com.unciv.ui.components.extensions

import org.threeten.bp.Duration
import org.threeten.bp.Instant


fun Duration.isLargerThan(other: Duration): Boolean {
    return compareTo(other) > 0
}
fun Instant.isLargerThan(other: Instant): Boolean {
    return compareTo(other) > 0
}
