package com.ekoehler.expressivecutout.overlay

/** A camera-safe, screen-bounded height for the expanded notification inbox. */
internal fun inboxHeightDp(baseHeightDp: Int, topMarginDp: Int, entries: Int, screenHeightDp: Int): Int {
    val cameraBandDp = maxOf(topMarginDp, 56)
    val headerAndFooterDp = 68
    val visibleEntries = entries.coerceIn(1, 5)
    val wanted = cameraBandDp + headerAndFooterDp + visibleEntries * 58
    return maxOf(baseHeightDp, minOf(wanted, screenHeightDp * 70 / 100))
}
