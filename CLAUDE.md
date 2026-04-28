# Mapo - Project Context

## Overview
Android input utility app for Android-based gaming devices (primary target: AYN Thor, dual-screen device).
Designed for the Thor's bottom screen while games/apps run on the top primary screen.
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

## Profile System
- Both features governed per-profile
- Profiles planned to be per-app and per-game (not fully implemented yet)

## Status
- Both features functional at baseline level
- UX improvements and next-priority features ongoing

## Tech
- Android application, Kotlin + Jetpack Compose + Hilt + Room
- Git repo on `main` branch
