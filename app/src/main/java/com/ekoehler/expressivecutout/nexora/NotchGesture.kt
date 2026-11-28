package com.ekoehler.expressivecutout.nexora

/** One source of truth for the overlay and settings. No privileged action is implicit. */
internal enum class NotchAction(val label:String) {
    NONE("Off"), INBOX("Notification inbox"),
    SHADE("Android notifications"), QUICK_SETTINGS("Android quick settings"),
    NEXT_ACTIVITY("Next activity"), PREVIOUS_ACTIVITY("Previous activity"),
    PLAY_PAUSE("Play / pause music"), NEXT_TRACK("Next track"), PREVIOUS_TRACK("Previous track"),
    SETTINGS("NEXORA settings"), DISMISS("Close Island");
    companion object {
        fun read(value:String?,fallback:NotchAction):NotchAction =
            if(value=="HUB")QUICK_SETTINGS else entries.firstOrNull{it.name==value} ?: fallback
    }
}
internal enum class NotchGesture(val title:String,val key:String,val defaultAction:NotchAction) {
    TAP("Single tap","notch_tap",NotchAction.INBOX),
    DOUBLE("Double tap","notch_double",NotchAction.NONE),
    HOLD("Long press","notch_hold",NotchAction.QUICK_SETTINGS),
    LEFT("Swipe left","notch_left",NotchAction.NEXT_ACTIVITY),
    RIGHT("Swipe right","notch_right",NotchAction.PREVIOUS_ACTIVITY),
    DOWN("Swipe down","notch_down",NotchAction.SHADE),
    UP("Swipe up","notch_up",NotchAction.DISMISS)
}
