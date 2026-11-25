package com.ekoehler.expressivecutout.core

/** A currently active notification displayed in the in-island inbox, without message body text. */
data class InboxNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val postTimeMs: Long,
    val progress: Float? = null,
    val progressIndeterminate: Boolean = false,
    val silent: Boolean = false,
)

