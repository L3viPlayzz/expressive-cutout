package com.ekoehler.expressivecutout.nexora

/** Compact, independent Quick Controls layout. No system switches are faked. */
internal enum class QuickControl(val label:String,val shortLabel:String) {
    NONE("Off",""), QUICK_SETTINGS("Android quick settings","Settings"),
    INBOX("Notification inbox","Inbox"), PLAY_PAUSE("Play / pause music","Play / pause"),
    NEXT_TRACK("Next track","Next"), PREVIOUS_TRACK("Previous track","Previous"),
    SHADE("Android notifications","Notifications"), FOCUS("NEXORA focus mode","Focus"),
    APP("NEXORA settings","NEXORA");
    companion object {
        val defaults=listOf(QUICK_SETTINGS,PLAY_PAUSE,INBOX)
        fun read(value:String?,slot:Int):QuickControl=entries.firstOrNull{it.name==value} ?: defaults[slot.coerceIn(0,2)]
    }
}
