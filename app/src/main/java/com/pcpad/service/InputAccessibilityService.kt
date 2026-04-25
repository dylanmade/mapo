package com.pcpad.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class InputAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // TODO: Initialize input injection pipeline.
        // Use dispatchGesture() for touch/mouse simulation or
        // AccessibilityNodeInfo.performAction() for key events.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for input injection; no-op.
    }

    override fun onInterrupt() {
        // Called when the service must interrupt its feedback.
    }
}
