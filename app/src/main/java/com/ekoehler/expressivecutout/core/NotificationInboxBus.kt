package com.ekoehler.expressivecutout.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps a small, in-memory snapshot of the notifications already visible to the listener. */
object NotificationInboxBus {
    private val _items = MutableStateFlow<List<InboxNotification>>(emptyList())
    val items: StateFlow<List<InboxNotification>> = _items.asStateFlow()

    /** Publishes current entries without storing them on disk. */
    fun update(items: List<InboxNotification>) {
        _items.value = items
    }
}
