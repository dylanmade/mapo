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
- UX improvements and next-priority features ongoing

## Tech
- Android application, Kotlin + Jetpack Compose + Hilt + Room
- Git repo on `main` branch
