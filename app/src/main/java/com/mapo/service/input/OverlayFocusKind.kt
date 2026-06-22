package com.mapo.service.input

/**
 * Which (if any) Mapo-owned overlay window currently holds the user's interaction
 * focus, from the accessibility-service / input-dispatcher's point of view. Replaces
 * the previous `overlayFocused: Boolean` so the routing logic in
 * `InputAccessibilityService.onKeyEvent` can branch on intent, not just on
 * "something is focused."
 *
 * **Values:**
 *  - [NONE] — no Mapo overlay is focused. Gamepad / key events flow to whichever
 *    app window holds keyboard focus underneath (typically the foreground game).
 *  - [PROMPT] — a focusable Mapo overlay prompt (e.g. the auto-switch
 *    "Create profile for X?" prompt from `OverlayManager`) is up. The accessibility
 *    service translates gamepad A → ENTER and B → BACK so the user can navigate the
 *    prompt with their controller.
 *  - [KEYBOARD] — the run-mode virtual-keyboard overlay is up. **Reserved.** The
 *    overlay window already has `FLAG_NOT_FOCUSABLE`, so key events route past it
 *    naturally — no service-side intervention needed today. Declared as a value
 *    rather than a future-only TODO so the dispatcher / service can adopt
 *    keyboard-specific routing later without changing the enum shape.
 *  - [TOOLBAR] — the home toolbar overlay is in gamepad-navigation mode
 *    (OVERLAY_TOOLBAR_PLAN.md). Handled identically to [PROMPT]: the toolbar window is
 *    made focusable while navigating, so the platform's focus traversal drives the
 *    stick / D-pad (which are MotionEvents the service can't see) and the service only
 *    translates gamepad A → ENTER and B → BACK so Compose's native key activation fires.
 */
enum class OverlayFocusKind {
    NONE,
    PROMPT,
    KEYBOARD,
    TOOLBAR,
}
