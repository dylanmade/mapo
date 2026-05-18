package com.mapo.service.overlay.keyboard

import android.content.Context
import com.mapo.service.keyboard.KeyboardController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Brick 4 (single-screen refactor) test for [KeyboardOverlayPresenter]'s state /
 * orchestration logic. The presenter is the single coordination point every
 * "show / hide / toggle the run-mode keyboard overlay" caller flows through —
 * QS tile, drawer item, future FGS-notification action — so its show/hide/toggle
 * semantics need to be predictable.
 *
 * The underlying [KeyboardOverlayManager] is mocked: its real attach/detach
 * machinery (`WindowManager.addView`, FGS lifecycle, display routing) is
 * device-side concern verified by hand. This test pins down the state contract
 * the presenter exposes to its callers.
 */
class KeyboardOverlayPresenterTest {

    private lateinit var context: Context
    private lateinit var manager: KeyboardOverlayManager
    private lateinit var controller: KeyboardController
    private lateinit var displayRouter: KeyboardDisplayRouter

    private lateinit var subject: KeyboardOverlayPresenter

    // Mirror of the manager's attached-id set so the mock can answer isAttached.
    private val attachedIds = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        controller = mockk(relaxed = true)
        displayRouter = mockk(relaxed = true)
        // Default routing: single-display (`Display.DEFAULT_DISPLAY` == 0). Matches the
        // Brick 5 baseline router behavior; tests that need to assert fan-out can
        // override `every { displayRouter.routeOverlay(any()) } returns listOf(0, 1)`.
        every { displayRouter.routeOverlay(any()) } returns listOf(0)

        // Track attach/detach state on the mock so isShowing() reflects what tests do.
        every { manager.isAttached(any()) } answers { call ->
            val id = call.invocation.args[0] as String
            id in attachedIds
        }
        every { manager.attach(any(), any(), any()) } answers { call ->
            attachedIds.add(call.invocation.args[0] as String)
        }
        every { manager.detach(any()) } answers { call ->
            attachedIds.remove(call.invocation.args[0] as String)
        }

        subject = KeyboardOverlayPresenter(context, manager, controller, displayRouter)
    }

    @Test
    fun isShowing_isFalse_initially() {
        assertFalse(subject.isShowing())
    }

    @Test
    fun show_callsManagerAttach_underCanonicalOverlayId() {
        subject.show()
        verify { manager.attach(KeyboardOverlayPresenter.OVERLAY_ID, any(), any()) }
        assertTrue(subject.isShowing())
    }

    @Test
    fun show_isIdempotent_whenAlreadyAttached() {
        subject.show()
        subject.show()
        // Manager should only see one attach call — second show() short-circuits.
        verify(exactly = 1) { manager.attach(KeyboardOverlayPresenter.OVERLAY_ID, any(), any()) }
    }

    @Test
    fun hide_callsManagerDetach() {
        subject.show()
        subject.hide()
        verify { manager.detach(KeyboardOverlayPresenter.OVERLAY_ID) }
        assertFalse(subject.isShowing())
    }

    @Test
    fun toggle_flipsBetweenShowAndHide() {
        subject.toggle()
        assertTrue(subject.isShowing())
        verifyOrder { manager.attach(KeyboardOverlayPresenter.OVERLAY_ID, any(), any()) }

        subject.toggle()
        assertFalse(subject.isShowing())
        verify { manager.detach(KeyboardOverlayPresenter.OVERLAY_ID) }
    }

    @Test
    fun overlayId_isStable() {
        // Other call sites — the QS tile, the in-app drawer toggle, the future
        // notification action — must not invent their own overlay ids. Pin the
        // canonical id so a rename surfaces in code review.
        assertEquals("keyboard_main", KeyboardOverlayPresenter.OVERLAY_ID)
    }
}
