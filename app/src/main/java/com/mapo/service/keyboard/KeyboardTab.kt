package com.mapo.service.keyboard

/**
 * FC1 forward-compat seam (single-screen refactor, Brick 2): tabs are exposed as
 * opaque descriptors — id + display label — rather than [com.mapo.data.model.GridLayout]
 * rows. Today's resolver maps `layoutId → GridLayout` through `LayoutRepository`;
 * post-parity, when overlays are governed by `ActionSet` / `ActionLayer` instead of
 * a single per-profile layout list, the same `KeyboardTab` surface will describe
 * action sets (with layers nested) without rippling into `KeyboardHost`.
 */
data class KeyboardTab(
    val id: Long,
    val label: String,
)
