package com.mapo.service.input.capture

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct test of [MotionCaptureView]'s motion-event override. Lives in a
 * separate file from the manager so the manager's tests don't need to
 * synthesize MotionEvents to verify the callback wiring.
 *
 * Verifies the View returns `false` from the handler (so events continue
 * through the normal Android input pipeline; nothing is "stolen") and that
 * the forwarded event is the exact instance the platform handed us.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MotionCaptureViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun onGenericMotionEvent_forwardsEventAndReturnsFalse() {
        val view = MotionCaptureView(context)
        var received: MotionEvent? = null
        view.onMotion = { received = it }

        val ev = syntheticJoystickEvent(x = 0.5f, y = -0.3f)
        val handled = view.onGenericMotionEvent(ev)

        assertFalse("View must not consume motion events", handled)
        assertNotNull("Callback should have received the event", received)
        assertEquals(ev, received)
        ev.recycle()
    }

    @Test
    fun onGenericMotionEvent_withNullCallback_doesNotThrow() {
        val view = MotionCaptureView(context)
        // Default state — no callback wired (manager not yet finished setup).
        assertNull(view.onMotion)
        val ev = syntheticJoystickEvent(x = 0f, y = 0f)
        // No-op delivery; survives the missing callback.
        assertFalse(view.onGenericMotionEvent(ev))
        ev.recycle()
    }

    private fun syntheticJoystickEvent(x: Float, y: Float): MotionEvent {
        val downTime = SystemClock.uptimeMillis()
        val pointerProps = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, x)
            setAxisValue(MotionEvent.AXIS_Y, y)
        })
        return MotionEvent.obtain(
            /* downTime = */ downTime,
            /* eventTime = */ downTime,
            /* action = */ MotionEvent.ACTION_MOVE,
            /* pointerCount = */ 1,
            /* pointerProperties = */ pointerProps,
            /* pointerCoords = */ pointerCoords,
            /* metaState = */ 0,
            /* buttonState = */ 0,
            /* xPrecision = */ 1f,
            /* yPrecision = */ 1f,
            /* deviceId = */ 0,
            /* edgeFlags = */ 0,
            /* source = */ InputDevice.SOURCE_JOYSTICK,
            /* flags = */ 0,
        )
    }
}
