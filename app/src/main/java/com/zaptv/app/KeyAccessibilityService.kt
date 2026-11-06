package com.zaptv.app

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, off unless the user enables it in Android settings.
 * Only reacts to the one key chosen in ZapTV settings; every other key passes straight through.
 */
class KeyAccessibilityService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = (applicationContext as App).prefs.globalLaunchKey
        if (key == KeyMap.NONE || event.keyCode != key) return false
        if (event.action == KeyEvent.ACTION_UP) App.launchMain(this)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
