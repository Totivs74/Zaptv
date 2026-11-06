package com.zaptv.app

import android.view.KeyEvent

enum class Action(val label: String, val defaultKey: Int) {
    GUIDE("TV guide", KeyEvent.KEYCODE_PROG_RED),
    FAVOURITE("Add / remove favourite", KeyEvent.KEYCODE_PROG_YELLOW),
    VOICE("Voice command", KeyMap.LONG_OK),
    PREV_CHANNEL("Previous channel", KeyEvent.KEYCODE_0),
    GRID("Channel grid", KeyEvent.KEYCODE_PROG_GREEN),
    SETTINGS("Settings", KeyEvent.KEYCODE_PROG_BLUE),
    OPTIONS("Audio / subtitles / picture", KeyMap.NONE)
}

object KeyMap {
    const val NONE = 0
    const val LONG_OK = -2

    /** Keys the app never takes over: volume, power, home, back and navigation. */
    val protected = setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
        KeyEvent.KEYCODE_9
    )

    fun name(code: Int): String = when (code) {
        NONE -> "None"
        LONG_OK -> "Hold OK"
        KeyEvent.KEYCODE_PROG_RED -> "Red"
        KeyEvent.KEYCODE_PROG_GREEN -> "Green"
        KeyEvent.KEYCODE_PROG_YELLOW -> "Yellow"
        KeyEvent.KEYCODE_PROG_BLUE -> "Blue"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            .lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    fun actionFor(prefs: Prefs, code: Int): Action? =
        Action.values().firstOrNull { prefs.keyFor(it) == code && code != NONE }
}
