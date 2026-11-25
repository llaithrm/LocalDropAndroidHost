package com.localdrop.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {

    private var cursorX = 0.5f
    private var cursorY = 0.5f

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
        cursorX = nx.coerceIn(0f, 1f)
        cursorY = ny.coerceIn(0f, 1f)
        val x = cursorX * screenW
        val y = cursorY * screenH
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 1f)
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

    // Smart TV D-Pad Navigation (Simulates clean swipes in 4 directions)
    fun dpad(direction: String, screenW: Int, screenH: Int): Boolean {
        val cx = 0.5f * screenW
        val cy = 0.5f * screenH
        val step = 0.15f * Math.min(screenW, screenH)

        val (x1, y1, x2, y2) = when (direction) {
            "up" -> listOf(cx, cy + step, cx, cy - step)
            "down" -> listOf(cx, cy - step, cx, cy + step)
            "left" -> listOf(cx + step, cy, cx - step, cy)
            "right" -> listOf(cx - step, cy, cx + step, cy)
            else -> return false
        }

        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun dpadCenter(screenW: Int, screenH: Int): Boolean {
        return tap(cursorX, cursorY, screenW, screenH)
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
