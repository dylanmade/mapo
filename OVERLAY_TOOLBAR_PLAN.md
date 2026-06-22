# Overlay Toolbar + Implementation-B Navigation Plan

## Context

After the bottom-toolbar redesign, Mapo's "home" is the floating pill (master switch ·
profile split-button · options) rendered **inside `MainActivity`** — a translucent,
fullscreen, `singleTask` activity that floats over the foregrounded game. The activity
also hosts a `NavHost` whose deeper routes (Edit Controls, Edit Overlay, Change Profile,
Auto-switch, Shizuku Setup, Theme Studio, …) are full-screen destinations in the same
task. Tapping the backdrop calls `moveTaskToBack`.

This is still an **activity-shaped home**: while it's up, the Mapo window owns focus, so
it's a modal "I opened Mapo" state, not an always-available control surface layered on the
game. The single-screen target (`project_target_single_screen_pivot`) wants the inverse —
the chrome should be a **persistent passthrough overlay** the user can reach mid-game
without backgrounding it, navigable by **physical gamepad** (no touchscreen assumption),
while the game keeps receiving input.

This plan converts the toolbar chrome into a system overlay and adds gamepad navigation of
that non-focusable overlay via the accessibility service ("implementation B"). It builds
directly on the keyboard-overlay refactor (`SINGLE_SCREEN_REFACTOR_PLAN.md`) and reuses the
per-element window machinery from the overlay rebuild (`OVERLAY_REBUILD_PLAN.md`).

Prereq/relation:
- Reuses `OverlayElementWindowManager`'s window recipe (`TYPE_APPLICATION_OVERLAY` +
  `FLAG_NOT_FOCUSABLE` + `FLAG_HARDWARE_ACCELERATED`, gesture-routed) and
  `OverlayLifecycleOwner`.
- Reuses the FGS (`KeyboardOverlayService`) that already holds priority while overlay
  windows are mounted.
- Mirrors the existing `OverlayFocusKind.PROMPT` contract (service owns focus/selection
  state, UI owns content) — but **inverts the focus decision** (see Decision below).

---

## Decision: non-focusable toolbar overlay + accessibility-driven selection (Implementation B)

Mount the toolbar chrome as a `TYPE_APPLICATION_OVERLAY` window that is **not focusable**.
Heavy screens stay in an activity (one task). The non-focusable overlay can't receive
gamepad key events through the window system, so a selection cursor is driven by the
**accessibility service**, which already intercepts every physical key via
`FLAG_REQUEST_FILTER_KEY_EVENTS`.

### Why not Implementation A (focusable overlay + Compose focus traversal)

`OverlayManager.showCreatePrompt(focusable = true)` already proves a focusable overlay can
be gamepad-navigated: `InputAccessibilityService` translates A→ENTER / B→BACK and lets DPAD
fall through to Compose's focus traversal (the `OverlayFocusKind.PROMPT` branch). That's
perfect for a **transient modal** prompt.

It is **fatal for a persistent toolbar**: a focusable `TYPE_APPLICATION_OVERLAY` window
takes key-event focus away from the game for the *entire* time it's mounted. The whole
reason every Mapo overlay window carries `FLAG_NOT_FOCUSABLE` (and why the flag was removed
from the activity in `feedback_no_flag_not_focusable_on_activity`) is so unmapped gamepad
input flows to the game. A persistent home chrome cannot own focus.

### Why Implementation B works

The toolbar window stays `FLAG_NOT_FOCUSABLE` (passthrough). Gamepad input reaches the game
normally. Only when the user **explicitly enters toolbar-nav mode** (a dedicated trigger)
does the service start consuming DPAD/A/B to drive a selection index; everything else still
passes. On exit, input returns to the game. This is the same "service owns the focus state,
UI owns the content" split as `OverlayFocusKind.PROMPT`, generalized from "is a prompt up"
to "which toolbar element is selected."

### Rejected alternatives

- **Focusable toolbar overlay (Impl. A)** — steals game input persistently (above).
- **Keep the toolbar in an activity** — the status quo; modal, backgrounds the game, no
  always-available mid-game chrome. This is what we're replacing.
- **`Presentation` / second activity for deep screens on a second display** — steals focus;
  also Thor-specific. Deep screens stay in the existing single task.
- **Compose-side DPAD handling in the overlay** — impossible: a non-focusable window never
  receives the key events; they go to the game. The service is the only component that sees
  them. This is the architectural crux that *forces* Implementation B.

---

## Architecture: the service ↔ UI contract

The hard constraint: **the service sees the keys, the UI knows the layout.** So we split
responsibilities exactly along that line.

```
 physical DPAD/A/B ──► InputAccessibilityService.onKeyEvent
                          │  (only while toolbarNavActive)
                          ▼
                       InputDispatcher  ◄── shared state bus (already exists)
                          │  toolbarNavActive: StateFlow<Boolean>
                          │  toolbarSelection: StateFlow<Int>
                          │  toolbarTargets:   StateFlow<List<ToolbarTargetId>>   (UI → bus)
                          │  activateToolbar(index): SharedFlow signal            (svc → UI)
                          ▼
                       Toolbar overlay Compose
                          • publishes its ordered focusable targets to the bus
                          • renders the highlight ring on toolbarSelection
                          • runs the target's onClick when activateToolbar fires
```

- **Service = cursor + activation signal.** DPAD moves `toolbarSelection` within
  `[0, toolbarTargets.size)`; A emits `activateToolbar(selection)`; B clears
  `toolbarNavActive`. These keys are consumed (not forwarded to the game) only while nav is
  active. The service knows *nothing* about what a target does or where it is on screen.
- **UI = topology + actions + highlight.** The toolbar composable owns the ordered list of
  focusable targets (switch, split-leading, split-trailing, options, and — when a menu is
  open — that menu's items). It republishes the list whenever the topology changes (menu
  open/close), renders the highlight on the selected index, and executes the action when the
  activation signal arrives. Republishing resets the selection to 0; a small nav stack in
  the UI handles descend-into-menu / ascend-out.

This mirrors `setOverlayFocus(PROMPT)` exactly: the service flips a coarse mode flag and the
UI reacts. We're adding an index + a target list + an activation channel to the same bus
(`InputDispatcher`), nothing structurally new.

---

## Locked-in contracts (Brick 0)

### Window
- Toolbar overlay: single `TYPE_APPLICATION_OVERLAY` window, bottom-center gravity, sized
  WRAP_CONTENT around the pill. Flags identical to `OverlayElementWindowManager`'s set:
  `FLAG_LAYOUT_IN_SCREEN | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS
  | FLAG_HARDWARE_ACCELERATED`. Out-of-bounds taps pass through; in-bounds taps reach
  Compose; key events route to the game. (`project_overlay_windows_need_hw_accel`.)
- Menus (profile / options) open as their own overlay popups, reusing the `UpwardMenu`
  pattern already built for `MainBottomToolbar` (custom `Popup` + position provider; the
  stock `DropdownMenu` auto-flip is broken under `FLAG_LAYOUT_NO_LIMITS`).

### State bus (`InputDispatcher`)
- `toolbarNavActive: StateFlow<Boolean>` — gamepad is currently driving the toolbar.
- `toolbarSelection: StateFlow<Int>` — selected target index (service-owned).
- `toolbarTargets: StateFlow<List<String>>` — ordered target ids (UI-owned, published up).
- `activateToolbar: SharedFlow<Int>` — one-shot "A pressed on index i" (service → UI).
- These coexist with `overlayFocus`/`captureMode`; nav mode and `PROMPT` are mutually
  exclusive (a prompt is modal).

### Deep screens
- The existing `NavHost` deep routes stay in **one task** (the current `MainActivity`,
  acting as a config host). The host is launchable directly into a route via an intent
  extra; the toolbar fires that intent for any element needing a full screen. The toolbar
  **home route is removed** from the NavHost (the overlay replaces it).

### Coexistence / kill-switch
- Build behind the existing overlay machinery; the in-activity toolbar is **not deleted**
  until the final brick (per `OVERLAY_REBUILD_PLAN` coexistence discipline). Every brick
  boundary leaves remap + the activity working end-to-end.

### Forward-compat
- Honors `project_action_layer_overlay_inheritance` FC seams: the toolbar's profile/set/
  layer scope is read through the same resolver the keyboard overlay uses, not a private
  copy.

---

## Resolved product decisions (2026-06-17)

1. **Launcher-icon behavior → shows the overlay toolbar.** Tapping the Mapo icon reveals the
   passthrough toolbar over the current foreground app (and routes to permission setup if
   overlay/accessibility perms are missing). It no longer opens a modal activity home. This
   is the same reveal path as the QS tile (decision 3). *Implemented at Brick 6 (the cutover),
   not earlier — it's irreversible and must wait until the overlay is gamepad-navigable.*
2. **Toolbar-nav entry trigger → long-press Select + A (chord), later configurable.** Default
   is the chord *hold Select, then A*. This is authored as a normal binding so it later flows
   through Mapo's standard activator configuration (regular / long-press / etc., per
   `reference_steam_input_activators`) and becomes user-rebindable with no special-case code.
   For the MVP the chord is the seeded default; the config UI is a follow-up, but the binding
   shape is chosen now so we don't hardcode a one-off.
3. **Toolbar visibility → shown on demand.** Hidden by default; revealed via the QS tile, the
   nav trigger, **or the launcher icon** (decision 1); auto-hides on exit. No persistent pill
   over the game.

---

## Bricks

Sequencing rule (inherited): **every brick boundary leaves remap working end-to-end and the
activity functional.**

### Brick 0 — Decisions (this file) — ⏳ awaiting sign-off
Architecture + contracts above. Resolve the three open decisions.

### Brick 1 — Toolbar overlay POC (touch only) — ✅ LANDED (awaiting device verify)
`ToolbarOverlayManager` (`service/overlay/element/`, sibling of `OverlayElementWindowManager`)
renders `MainBottomToolbar` in a single non-focusable overlay window with the shared flag
matrix + FGS; state collected directly from the singletons (`ProfileRepository`,
`SteamCredentialStore`, `KeyboardController`, `OverlayPresenter`, `OverlayLiveEditController`).
Master switch + menus work by touch; `WRAP_CONTENT` width so side taps fall through. Deep-screen
items launch `MainActivity` (precise routing is Brick 2); `onEditOverlay` → live editor works.
Mounted via a temporary "Toolbar overlay (dev)" entry in the in-activity toolbar's More menu
(`MainViewModel.toggleToolbarOverlayDev()`; optional `MainBottomToolbar` param, null on the
overlay's own copy). Coexists with the in-activity toolbar; nothing deleted. **Verify on
device:** touch works, out-of-bounds passthrough works, game keeps gamepad input while up.

### Brick 2 — Config-host deep-route launching (additive) — ✅ LANDED (awaiting device verify)
`MainActivity` takes `EXTRA_ROUTE` (a `MapoRoute` string), consumed in `onCreate` +
`onNewIntent` (singleTask) into `pendingRoute`/`routeNonce` snapshot state; `MainScreen` gained
`deepLinkRoute`/`deepLinkNonce` params and a `LaunchedEffect(deepLinkNonce)` that navigates
there. Keyed on the **nonce, not the route**, so re-tapping the same destination after backing
out re-navigates (a plain String wouldn't re-fire). `startDestination` stays MAIN, so Back from
a deep screen returns to the Mapo home. `ToolbarOverlayManager.launchRoute(route)` puts the
extra; every deep-screen callback fires its exact route. **Purely additive** — in-activity home,
MAIN route, and launcher behavior untouched. Launcher repoint + MAIN-home removal remain the
**Brick 6 cutover** (must wait until the overlay is gamepad-navigable). **Verify on device:**
from the dev overlay, each menu item opens the right screen; Back returns to home; re-tapping a
destination after backing out re-opens it.

### Brick 3 — Implementation-B selection engine (the heart) — ✅ LANDED (awaiting device verify)
`InputDispatcher` gained the four toolbar members (`toolbarNavActive`, `toolbarSelection`,
`toolbarTargets`, `activateToolbar`) + ops (`setToolbarTargets`/`enter`/`exit`/`move`/`activate`;
republish resets selection to 0, empty list force-exits nav). `InputAccessibilityService.onKeyEvent`
gained a `toolbarNavActive` branch **before** the remap-enabled gate (so the toolbar stays
navigable with remap off): DPAD L/R move (consumed), DPAD U/D consumed (reserved for Brick 4),
A activates (consumed), B exits (consumed); all else passes. `ToolbarOverlayManager.ToolbarContent`
publishes targets + collects nav state + bumps an activate tick; `MainBottomToolbar` gained
`navEnabled/navActive/navSelection/navActivateTick`, renders a constant-footprint selection ring
(`navRingModifier`, no reflow on enter, in-activity copy untouched) and self-activates.

**As-built deviations** (vs. the plan-as-written, per living-plan workflow):
- **3 top-level targets, not 4:** `switch` / `split-button` / `options`. The split button is
  one ring; A on it opens the profile menu (which already contains "Profile options" =
  the leading button's direct action), so collapsing leading+trailing into one target loses
  nothing and gives a clean single highlight. Brick 4 adds gamepad descent into the menus.
- **Enter trigger detected inline** (Select-held + A) as the Brick-3 MVP, *not* yet routed
  through the activator/chord machinery. Deferred to Brick 5 with the configurability work.
- **Known MVP artifacts** (clean up in Brick 4/5): (a) entering nav lets the game see a stray
  Select tap (we can't buffer the Select edge without the activator flow); (b) A on
  split/options opens a `focusable` popup over the game — transient focus-steal, and not yet
  gamepad-navigable (service consumes DPAD during nav). B exits nav but doesn't dismiss an open
  menu. Both resolved when Brick 4 makes menu nav service-driven.

**Verify on device:** with the dev overlay up, hold **Select + A** → selection ring appears;
**DPAD L/R** walks switch→split→options; **A on the switch** toggles Mapo on/off (toolbar stays
up); **A on split/options** opens that menu; **B** exits (ring gone, game DPAD restored). Confirm
the game keeps gamepad input when *not* in nav mode, and DPAD doesn't leak to the game *during* nav.

### Brick 4 — Menu nav stack — ✅ LANDED (awaiting device verify)
Both menus are modeled as ordered `ToolbarMenuEntry` lists in `MainBottomToolbar` (the nav-stack
owner). Opening a menu republishes `toolbarTargets` to its item ids (selection resets to 0);
the per-item highlight (`secondaryContainer` background) follows `navSelection`; activating a leaf
runs its action, closes the menu, and exits nav. New plumbing: `InputDispatcher.backToolbar` +
`signalToolbarBack()`; the service routes **B → back signal** (no longer a direct exit) so the UI
decides **ascend-out-of-menu vs. exit-at-top**; DPAD axes unified (Left/Up = prev, Right/Down =
next) so the same cursor drives the horizontal toolbar and the vertical menu. `MainBottomToolbar`
gained `navBackTick` / `onPublishTargets` / `onExitNav`; live target publishing moved from the host
into the toolbar (host keeps only teardown cleanup). This **resolves the Brick-3 menu-descent
artifact**; the focusable popup still transiently takes focus while open (cosmetic; service-driven
nav works through it) and the stray Select tap on entry remains for Brick 5. **Verify on device:**
A on split/options opens the menu with item 0 highlighted; DPAD moves the highlight; A runs the
item (and exits nav); B closes the menu back to the top-level rings; B again exits nav.

### Brick 5 — Reveal triggers, tile & polish
Finalize the shown-on-demand reveal (decision 3): QS tile + nav-trigger + launcher icon all
show the toolbar and it auto-hides on exit. Wire permission/health surfacing (overlay +
accessibility) consistent with the Shizuku pattern. Highlight enter/exit + move animation per
`feedback_handrolled_components_animations` (reproduce conventional focus-traversal motion,
not just a static ring). Sentence-case all strings; M3 surface roles per
`feedback_m3_compose_standards`.

- **Brick 5a — gamepad summon + auto-hide — ✅ LANDED (awaiting device verify).** Select+A now
  *summons* a hidden toolbar and enters nav, and a gamepad-summoned toolbar auto-hides when the
  user exits nav. `InputDispatcher.requestEnterToolbarNavWhenReady()` + a `pendingEnterNav` latch
  honored in `setToolbarTargets` bridge the async mount (targets publish a frame after
  `addView`). `ToolbarOverlayManager` split into `show()` (persistent, dev/QS) vs `showForNav()`
  (returns false if overlay perm missing → trigger's A falls through to the game) with a
  `summonedByNav` flag; `onExitNav` hides only a summoned session. Service injects the manager and
  reveals-then-enters on Select+A. A/B (and the enter chord) guarded on `repeatCount == 0` so a
  held button doesn't spam activate/back; DPAD repeat left on for menu scroll. **Verify on device:**
  with the toolbar hidden, Select+A makes it appear in nav (ring on switch); B at top makes it
  vanish; a dev/QS-shown toolbar stays after B (only its nav exits).
- **Brick 5a-fix — PIVOT to transient-focusable nav — ✅ LANDED (awaiting device verify).** On-device,
  the stick AND D-pad turned out to be **MotionEvents** (HAT axis) the AccessibilityService can't see
  (only face buttons are key events) — so the onKeyEvent cursor of Bricks 3–4 fundamentally can't drive
  movement. User chose transient-focusable over the Shizuku-motion path. Now: while `navActive` the
  toolbar window is made **focusable** (`setWindowFocusable`), so the platform's focus traversal drives
  stick/D-pad for free; `overlayFocus = TOOLBAR` makes `onKeyEvent` inject **A→ENTER / B→BACK** (reusing
  the PROMPT path) so Compose's native key-activation fires. Movement/highlight = Compose focus + M3
  focus indication. The Bricks 3–4 service cursor + `InputDispatcher` target/selection/activate/back
  machinery are now **vestigial** (cleanup later). Highlight prominence + motion = polish (Brick 5c).
- **Brick 5b — QS tile reveal — ✅ LANDED (awaiting device verify).** `ToolbarTileService`
  (`@AndroidEntryPoint` `TileService`) toggles the toolbar via `ToolbarOverlayManager.toggle()`
  (tile-shown = persistent, `summonedByNav` false); reflects `isShowing()` as active/inactive; if
  overlay perm is missing it `startActivityAndCollapse`-launches the app so its permission flow can
  prompt. Manifest service + `ic_toolbar_tile` vector + `toolbar_tile_label`.
- **Brick 5c (highlight polish) — ✅ LANDED (awaiting device verify).** Replaced M3's subtle default
  focus indicator with `Modifier.toolbarFocusRing` — a constant-footprint (3 dp gap reserved, no
  reflow), animated primary ring driven by `focusGroup()` + `onFocusChanged { hasFocus }`. Split
  button's leading made non-focusable (`focusProperties { canFocus = false }`) so gamepad focus is a
  clean 3-stop walk (switch → menu → options); focused menu rows get a fading `secondaryContainer`
  background. Per `feedback_animate_interactions_by_default` / `feedback_handrolled_components_animations`.
- **Brick 5c (remaining) — trigger via activator flow + permission/health surfacing** (pending).
  Also clears the residual MVP artifacts (Select-tap on entry; menu-popup transient focus-steal).

### Brick 6 — Retire the in-activity home
Remove the home route, backdrop tap-to-background, and dead drawer/menu code; the launcher
icon now shows the overlay (decision 1). Pre-release, so destructive removal is fine
(`project_mapo_pre_release`); flag back-compat reinstatement as release approaches.

---

## Risks / watch-items
- **Highlight legibility over arbitrary game art.** The selection ring must read on any
  backdrop — use an M3 focus-indication treatment with a contrasting halo, not a thin tint.
- **Consumed-key correctness.** While nav is active, only DPAD/A/B are consumed; a stuck
  `toolbarNavActive` would swallow the game's DPAD. Guarantee exit on activity launch, on
  toolbar hide, and on any modal `PROMPT`.
- **Multi-display (Thor).** The toolbar window must mount on the display the user is looking
  at; reuse the display-routing logic already in the accessibility service. Single-screen is
  the primary target, so Thor parity is a follow-up, not a Brick-1 gate.
- **Latency.** The DPAD→selection→highlight path is event-driven (no polling); keep it off
  any frame loop (`feedback_latency_phrasing`).
```
