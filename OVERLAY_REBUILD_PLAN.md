# Overlay Rebuild Plan — conventional multi-window button overlay + editor

## Context

The single-screen refactor (`SINGLE_SCREEN_REFACTOR_PLAN.md`, Bricks 0–5, complete
2026-05-18) moved the run-mode keyboard into **one** full-screen
`TYPE_APPLICATION_OVERLAY` window. To make empty space fall through to the game, that
single window relies on an `@hide` touchable-region reflection hack
(`OverlayTouchableInsets` + `TOUCHABLE_INSETS_REGION`) that never fully worked for
empty-zone passthrough and was always flagged as an artifact of the old dual-screen,
tabbed "virtual keyboard" metaphor.

We are now transitioning the overlay feature to a **conventional mobile game-overlay
paradigm**: discrete, free-positioned buttons floating over a foregrounded game, each
button its **own** `WindowManager` window. Empty space is passthrough for free (no
window exists there) — no reflection, public APIs only. The tab/grid "keyboard"
metaphor is retired in favor of free placement. A new **"Edit Overlay"** drawer entry
opens an editor for adding/placing/configuring buttons.

**Intended outcome (MVP):** show a set of free-positioned buttons over any game; each
button injects an input on tap; empty space passes through; gamepad still drives the
game; and the user can add/move/size/bind buttons through an editor.

### Guardrails
- **Do not delete the old paradigm yet.** The legacy keyboard overlay
  (`KeyboardOverlayManager` / `KeyboardHost` / `KeyLayout` tabs / grid edit mode) stays
  intact and functional. We *route to a new overlay* and *grab from the old as needed*.
- Runs **concurrently** with the physical-button remap (Steam Input) work.
  **Eventually** overlay buttons hook into the same input pipelines (`Binding` /
  `Activator`); MVP reuses the existing `RemapTarget` + `InputDispatcher` dispatch so
  we're not blocked on parity.
- Pre-release: destructive schema changes are fine, no migrations needed.

### Decisions locked
- **Data model:** free-positioned buttons (normalized x/y + size), **not** the grid model.
  Plus a **snapping** toggle in the overlay's own settings for clean grouping/alignment.
- **Window granularity:** **one window per button.**
- **Editor:** undecided by design — both candidates are prototyped (in-app canvas +
  live on-overlay), then converged after hands-on. See Brick C.

---

## Architecture: shared foundation + thin editor layer

```
OverlayElement (Room entity, free-positioned)            ── Brick A
  → OverlayRepository / OverlayElementDao
OverlayElementWindowManager (one window per element)     ── Brick B
  → OverlayPresenter (show/hide/toggle, diffs repo→windows)
  → tap dispatch via OverlayTargetDispatcher → InputDispatcher
Editor candidates (write to OverlayRepository):          ── Brick C
  C1 in-app canvas editor   |   C2 live on-overlay editor
Snapping + overlay settings                              ── Brick D
```

### Reused from the old paradigm (grab-as-needed)
- `RemapTarget` + `InputDispatcher.injectKey` / `dispatchTargetAsClick` — what a button
  emits. `KeyboardController.dispatchButtonTarget` is the reference; we extract a shared
  `RemapTarget`-dispatch helper rather than depend on the keyboard controller.
- `OverlayLifecycleOwner`, the `KeyboardOverlayService` FGS, `MappoTheme` wrapping,
  `Settings.canDrawOverlays` gating, and the flag-matrix lesson (`FLAG_NOT_FOCUSABLE`
  is load-bearing so gamepad/key events reach the game).
- Button visual styling (`ButtonContent`, drop-shadow helpers in `MainScreen.kt`) —
  cribbed later; MVP uses a simple themed button.
- Drawer-entry pattern in `ProfileDrawerContent.kt`.

### Untouched (coexists)
`KeyboardOverlayManager`, `KeyboardOverlayPresenter`, `KeyboardHost`,
`KeyboardTileService`, `KeyLayout`/`GridLayout`/`GridButton`, grid edit mode, the
`OverlayTouchable*` reflection bridge. Removal is a post-decision brick.

---

## Bricks

Sequencing rule: **every brick boundary leaves remap + the legacy keyboard overlay
working end-to-end.**

### Brick 0 — Plan + paradigm note (no app code) — ✅ DONE
- This file (living doc).
- `CLAUDE.md` Virtual Keyboard Layouts section gains the new-overlay subsection; tabbed
  keyboard marked legacy/coexisting.
- Memory: overlay-rebuild project note; repoint multi-window-pivot / touchable-insets
  memories at this plan.

### Brick A — Free-positioned data model + repository — ✅ DONE
Landed: `OverlayElement` entity (`data/model/OverlayElement.kt`, normalized x/y/w/h +
encoded `RemapTarget` tapTarget + zIndex), `OverlayElementDao`, `OverlayRepository`,
registered in `AppDatabase` (v15→16, destructive), DAO provided in `AppModule`.
`OverlayRepositoryTest` (6 tests, fake-DAO pattern) green.

#### original Brick A spec
- New Room entity `OverlayElement`: `id`, `profileId` (FK, CASCADE), normalized
  `x`/`y`/`width`/`height` (display fractions), `label`, `tapTarget` (encoded
  `RemapTarget` string, like `GridButton.onTap`), `zIndex`. One flat overlay per profile.
- New `OverlayElementDao` + `OverlayRepository` (Flow by profile; add/update/move/delete).
  Register entity in `AppDatabase` (bump version; destructive migration ok). Provide DAO
  in `AppModule`.
- **FC1 seam:** element binding kept behind a small indirection so `RemapTarget →
  Binding/Activator` is a local swap; "elements of the active overlay" stays separable
  from "which overlay is active" (today per-profile; tomorrow per action set).
- Tests: `OverlayRepository` CRUD against in-memory Room (Robolectric).
- **Exit:** model persists; nothing renders it yet; legacy paths untouched.

### Brick B — Multi-window renderer + show/hide + read-only dispatch — 🔨 CODE COMPLETE (awaiting device verify)
Landed: `OverlayElementWindowManager` (one window per element; diff
add/remove/`updateViewLayout`; per-window `ComposeView` + `OverlayLifecycleOwner`; flag
matrix `NOT_FOCUSABLE|NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS`; display size
via `maximumWindowMetrics` API30+ / `getRealSize` fallback for minSdk 26).
`OverlayElementButton` composable (M3, secondaryContainer). `OverlayTargetDispatcher`
(shared `RemapTarget`→`InputDispatcher`). `OverlayPresenter` (`show/hide/toggle`,
collects active profile's elements, error relay). Drawer entry "Toggle button overlay"
wired through `MainViewModel.toggleOverlay()` + `MainScreen`. Reuses `KeyboardOverlayService`
FGS (shared-service caveat noted in the manager doc). Build + unit tests green (5
pre-existing unrelated `toggleRemap`/auto-switch-defaults failures from in-flight WIP,
not from this work). **Next: on-device verification (see hand-off below).**

#### original Brick B spec
- `OverlayElementWindowManager` (sibling of `KeyboardOverlayManager`): one window per
  element; diff the element list against live windows → `addView` /
  `removeViewImmediate` / `updateViewLayout`.
  - Window flags: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN |
    FLAG_LAYOUT_NO_LIMITS`, gravity `TOP|START`, x/y/w/h from the element's normalized
    rect × display metrics. Window == button bounds ⇒ whole window touchable, everything
    outside passthrough. **No `OverlayTouchable*` reflection.**
  - Content: one themed Compose button per window (`MappoTheme` + `OverlayLifecycleOwner`).
    Reuse the FGS (`KeyboardOverlayService`) for process priority.
- `OverlayPresenter` (`@Singleton`): `show()/hide()/toggle()/isShowing()`; collects
  `OverlayRepository` and drives the manager. Tap → `OverlayTargetDispatcher` →
  `InputDispatcher` (digital keys: no Shizuku; mouse/gamepad: existing dispatch — no
  *new* Shizuku dependency).
- New drawer entry **"Show overlay"** (toggle), separate from the legacy keyboard entry.
- **Verify (device):** buttons render at position over a game; taps inject; empty space
  passthrough; gamepad still drives game; survives Mappo backgrounding.

### Brick C — Editor spike: BOTH candidates over the shared foundation — ✅ DONE
**Decision (2026-06-04): the live on-overlay editor (C2) wins.** C1 (in-app canvas) was
pruned: deleted `OverlayEditorScreen.kt` + `OverlayEditorViewModel.kt`, removed the
`OVERLAY_EDITOR` nav route + the "canvas" drawer entry; the live entry is now just
"Edit overlay". Shared pieces kept (`OverlayEditor`, `OverlayElementConfigContent`).
The chosen editor is `OverlayLiveEditController`.

#### spike record
Landed shared foundation: `OverlayEditor` (`@Singleton` edit ops + active-profile
`elements` flow, written by both editors), `OverlayElementConfigContent` (shared label +
common-command-chip config UI, instant-commit, no auto-focus). **C1** —
`OverlayEditorScreen` + `OverlayEditorViewModel` (in-app canvas: drag/resize/add/select/
configure/delete; nav route `OVERLAY_EDITOR`). **C2** — `OverlayLiveEditController`
(live over the game: dim scrim window + raw-coordinate-drag element windows + floating
toolbar window [add / zoom± resize / configure / delete / done] + focusable config
window; hides the run overlay on start to avoid stacking). Temporary dual drawer entries
"Edit overlay — canvas" / "Edit overlay — live". Build + unit tests green (same 5
pre-existing unrelated failures; the Steam-WIP `steamCredentialStore` ctor param was
patched into the VM test wiring to keep the build compiling). **Next: hands-on, pick
C1 vs C2; prune the loser.** Prototype simplifications: C2 resize via toolbar zoom
(not corner-drag); config uses a chip palette, not the full `remapTargetPicker`.

#### original Brick C spec
Shared plumbing: select / add (default rect at center) / configure (`RemapTarget` +
label sheet, no auto-focused TextField) / delete; all writes to `OverlayRepository`;
renderer reflects live.
- **C1 — In-app canvas editor** (`OverlayEditorScreen`, Scaffold/M3): display-aspect
  canvas, each element a draggable+resizable Box; FAB add; tap-select → config sheet.
- **C2 — Live on-overlay editor:** edit mode mounts real element windows draggable
  (`ACTION_MOVE` → `updateViewLayout`; resize handle; persist on drag-end) + a focusable
  toolbar window (Add/Done) + a full-screen dim scrim window (signals edit mode, absorbs
  stray touches so the game underneath isn't disturbed).
- Temporary **dual drawer entries** to try each; user picks after hands-on; loser pruned
  in a later brick.
- **Verify (device):** in each editor add/drag/resize/bind a button → renders + injects.

### Brick D — Snapping + overlay settings — 🔨 CODE COMPLETE (awaiting device feel)
Landed: `OverlaySettings` (`SharedPreferences` + `StateFlow`, mirrors `AutoSwitchSettings`;
`snapEnabled` default on; `GRID_DIVISIONS`/`SNAP_THRESHOLD_DP` consts). Snap toggle in the
live editor's toolbar (the overlay's own settings surface in edit mode) — `GridOn/GridOff`
`FilledIconToggleButton`. Snap math in `OverlayLiveEditController.snapPosition`: during the
raw-touch drag, the dragged button's top-left snaps to the nearest grid line OR sibling
edge/center within ~16dp (per axis: grid, sibling near/far edges incl. adjacent stacking,
sibling centers). Build + unit tests green (only the 5 pre-existing unrelated failures).

#### original Brick D spec
- Per-overlay settings (snap on/off + grid size) in an overlay-settings sheet (M3,
  sentence case). Snap-on-drag math: grid + nearby-sibling-edge alignment guides.

### Brick E — Layered skeuomorphic appearance system — 🔨 CODE COMPLETE (awaiting device verify)
The "shape builder" (2026-07-19, from the user's Illustrator reference
`shape_and_gradient_examples.svg`): buttons are painted as an ordered stack of fills and
strokes over one rounded-rect geometry. This is also the planned foundation for the
user-facing appearance editor generally — the handheld frame is intended to eventually be
authored in it and saved as a template.

- **Model** `data/model/overlay/ElementAppearance.kt`: `cornerRadius` (fraction of half the
  short side; 0 = square, 1 = pill/circle) + `layers: List<AppearanceLayer>` (bottom→top).
  Layer = FILL|STROKE, `LayerPaint.Solid|Gradient` (multi-stop, per-stop color/opacity +
  Illustrator midpoints, 0–360° angle), layer opacity; stroke width/align
  (inside/center/outside)/style (solid/dashed/dotted)/x-y offsets; gradient strokes are
  LINEAR (shape-space gradient visible within the band → one-edge highlights) or ACROSS
  (ramp runs outer→inner edge, wrapping corners). Hand-rolled org.json codec
  (`encode`/`decodeElementAppearance`) — the JSON is the future template/sharing format.
- **Storage**: `OverlayElement.appearanceJson: String?` (DB v19, destructive fallback).
  Null = legacy light-appearance rendering; the two coexist so old elements keep their
  look until first edited.
- **Renderer** `ui/screen/overlay/AppearanceRenderer.kt`: pure draw-phase; ACROSS
  gradients band into ≤32 concentric ~1dp sub-strokes (Illustrator itself rasterizes
  these — the SVG embeds PNGs). Wired into `OverlayElementButton`.
- **Editor** in `OverlayElementConfigContent`: corner-radius slider replaces the shape
  presets; reorderable layer panel (top-first, secondaryContainer selection) with +Fill /
  +Stroke (stroke seeds as a white top-edge highlight gradient); per-layer paint/opacity/
  stroke controls; `ui/component/GradientEditor.kt` = Illustrator-style ramp with
  draggable stops + midpoint diamonds, tap-ramp-to-add, per-stop dialog color picker.
  Legacy elements seed their stack from shape/fill fields on first edit. Text color moved
  to a ColorPickerButton row.
- Known caveats: OUTSIDE strokes/offsets clip at the element's own window edge; layered
  elements lose the Surface tonalElevation tint; decoding corrupt JSON silently falls
  back to legacy rendering.
- **Fix round (2026-07-19, on-device feedback):** (a) the live editor's `EditableElement`
  was a hand-copied Surface replica that ignored `appearanceJson` — every appearance edit
  was invisible in edit mode. Extracted `OverlayElementVisual` (run mode + edit replica
  share it; `selectionColor` draws the outline along the actual silhouette via
  `drawAppearanceOutline`). (b) The three `MappoColorPickerDialog`s crashed — dialog
  composables can't attach inside `TYPE_APPLICATION_OVERLAY` windows (the pre-existing
  reason the drawer used the inline `ColorPicker`); all color editing in the drawer +
  `GradientEditor` is now the inline picker, expanded under the swatch. (c) Corner radius
  is now per-corner (`CornerRadii`; renderer moved from `drawRoundRect` to per-corner
  `Path`s; JSON `"corners":[tl,tr,br,bl]` with legacy `"cornerRadius"` decode fallback;
  master slider + "Per-corner radii" expander). (d) Default new button = plain unbound
  circle: `SHAPE_CIRCLE`, square-in-px via display aspect (`DEFAULT_DIAMETER` 0.10 of
  width; OverlayEditor now injects context), no label — `OverlayElementLabel` renders
  nothing for blank-label Unbound elements ("(Device default)" is physical-remap
  vocabulary; overlay buttons have no device default), and the config drawer says
  "Fires: nothing".

### Later (out of MVP scope, noted as seams)
- Converge to the chosen editor; delete the other + the `OverlayTouchable*` bridge; retire
  the tabbed keyboard once superseded.
- Migrate element binding `RemapTarget → Binding/Activator` (shared pipeline; FC1, ties
  into action-set-governed overlays).
- Per-button styling parity (shapes/shadows/regions) cribbed from the grid renderer.
- Thor / multi-display routing via `KeyboardDisplayRouter` (FC2).

---

## Verification (end-to-end)

Device (single-screen phone primary; Thor secondary sanity):
1. Grant accessibility + overlay permissions; pick a profile.
2. Brick B: show overlay over a game → buttons at position; taps inject; empty space
   passthrough; gamepad drives game; survives Mappo backgrounding.
3. Brick C: each editor — add/drag/resize/bind → renders + injects. C2: scrim protects
   game; toolbar doesn't eat gamepad.
4. Brick D: snapping on → edges/centers align; off → free.
5. Legacy keyboard overlay (QS tile / drawer) + physical remap still work throughout.

Tests: full Robolectric suite green; new `OverlayRepository` / `OverlayPresenter` tests
pass; no regressions in `ComposeSmokeTest`, `MainViewModelTest`, input-pipeline tests.
