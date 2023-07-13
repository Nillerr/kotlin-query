package com.cardlay.nebula.shared.query

internal fun <T> List<T>.startsWith(prefix: List<T>): Boolean {
    if (size < prefix.size) {
        return false
    }

    for (idx in prefix.indices) {
        val pre = prefix[idx]
        val element = this[idx]

        if (pre != element) {
            return false
        }
    }

    return true
}
