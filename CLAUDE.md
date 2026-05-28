# Mapo - Project Context

## Overview
Android input utility app for Android-based gaming devices. **Primary target: any single-screen
Android device** — the design intent is virtual keyboards overlaid on the main screen while a
game runs "underneath" (transparent overlay over the foregrounded game). The AYN Thor's dual-screen
form factor is now a **secondary** use case: the bottom screen is bonus real estate for virtual
keyboards, but the core architecture targets the single-screen overlay pattern first.
Background remapping works even when Mapo is not visible on any screen.

## Core Features

### 1. OS-Level Button Remapping
- Maps physical button inputs to any gamepad/keyboard/mouse input at Android OS level
- Example: gamepad A -> keyboard ENTER, or any cross-type mapping
- Works in background (no UI required)
- **Digital remap** (button → key/button) works on any Mapo install; no setup beyond
  accessibility + overlay permissions.
- **Analog modes** (Trigger Soft Pull, Mouse Joystick, Joystick Camera, Dpad-on-stick)
  require Shizuku — they read raw `/dev/input/event*` via a UID-2000 (shell) UserService
  to capture gamepad motion that's otherwise unreachable on unrooted Android 13. Mouse
  output uses a `/dev/uinput` virtual SOURCE_MOUSE device for real `BTN_LEFT`/`REL_WHEEL`/etc.
  events. Without Shizuku, analog mode bindings are inert; a state-aware dialog at pick
  time + persistent health notification + Shizuku Setup screen surface the requirement.
  A per-binding "Send as gesture" toggle in activator settings lets the user emit
  synthetic touch via `AccessibilityService.dispatchGesture` instead of real mouse —
  needed for emulator frontends (RetroArch / DOSBox Pure / GameNative) that consume
  touch but not mouse events.

### 2. Virtual Keyboard Layouts
- On-screen customizable virtual keyboard layouts
- Default layouts: Keys Main, Keys Alt, Mouse
- Provides additional easily-accessible input options
- **Run mode** renders inside a system overlay (`TYPE_APPLICATION_OVERLAY`),
  activated via a Quick Settings tile or the in-app drawer toggle. The overlay
  is `FLAG_NOT_FOCUSABLE` so gamepad input flows past it to the foreground game.
- **Edit / configure mode** lives inside the Mapo activity (`MainActivity` →
  `KeyboardHost(mode = Activity, ...)`). Same composable as the overlay, with
  edit affordances enabled.

## Profile System
- Both features governed per-profile
- Profiles planned to be per-app and per-game (not fully implemented yet)

## Status
- Both features functional at baseline level
- Phase 6 (analog input modes via Shizuku) shipped 2026-05-25 — Trigger Soft Pull,
  Mouse Joystick, Joystick Camera, Dpad-on-stick. True Joystick Move (XInput analog
  passthrough) is reserved for a future brick — needs a virtual XInput gamepad via
  uinput (parallel to the mouse uinput work). Scroll Wheel / 2D Scroll / Absolute
  Mouse / Radial Menu / Touch Menu modes remain stubbed.
- UX improvements and next-priority features ongoing

## Tech
- Android application, Kotlin + Jetpack Compose + Hilt + Room
- Multi-module Gradle: `:app` (main), `:input-shared` (AIDL + Parcelables across
  the Shizuku process boundary), `:shizuku-service` (UID-2000 UserService with
  /dev/input reader + /dev/uinput virtual mouse, via JNI)
- Git repo on `main` branch
