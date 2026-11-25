package com.localdrop.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() { instance = this }

    fun tap(nx: Float, ny: Float, screenW: Int, screenH: Int) {
        val x = nx.coerceIn(0f, 1f) * screenW
        val y = ny.coerceIn(0f, 1f) * screenH
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 75)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, screenW: Int, screenH: Int, duration: Long) {
        val path = Path().apply {
            moveTo(nx1.coerceIn(0f,1f) * screenW, ny1.coerceIn(0f,1f) * screenH)
            lineTo(nx2.coerceIn(0f,1f) * screenW, ny2.coerceIn(0f,1f) * screenH)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceIn(80, 1500))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
    }
}
