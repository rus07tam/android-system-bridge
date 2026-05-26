package org.ruject.gateway.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class GatewayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We can capture events here if needed, but primary interaction is via direct tree dumps.
    }

    override fun onInterrupt() {
        // Interrupt logic
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        var instance: GatewayAccessibilityService? = null
            private set
    }
}
