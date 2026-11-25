package com.localdrop.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun tap(nx: Float, ny: Float, screenW: Int, screenH: Int): Boolean {
        val x = nx.coerceIn(0f, 1f) * screenW
        val y = ny.coerceIn(0f, 1f) * screenH
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 1f) // Ensure path has non-zero length so Android dispatchGesture won't cancel it
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, screenW: Int, screenH: Int, duration: Long): Boolean {
        val path = Path().apply {
            moveTo(nx1.coerceIn(0f, 1f) * screenW, ny1.coerceIn(0f, 1f) * screenH)
            lineTo(nx2.coerceIn(0f, 1f) * screenW, ny2.coerceIn(0f, 1f) * screenH)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceIn(50, 1500))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performGlobal(action: String): Boolean {
        return when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "power" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
            else -> false
        }
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
        val isAvailable: Boolean get() = instance != null
    }
}
