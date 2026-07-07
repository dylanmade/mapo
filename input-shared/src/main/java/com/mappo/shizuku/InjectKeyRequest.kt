package com.mappo.shizuku

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Cross-process KeyEvent inject request. Not `KeyEvent` directly because
 * `android.view.KeyEvent`'s source-id assumptions break when reconstructed
 * in a different process — the service side rebuilds a fresh `KeyEvent`
 * from these fields and stamps `displayId` via the same HiddenApiBypass
 * reflection :app uses for its in-process inject path.
 *
 * @param keyCode `KeyEvent.KEYCODE_*` constant.
 * @param action `KeyEvent.ACTION_DOWN` (0) or `KeyEvent.ACTION_UP` (1).
 * @param displayId Target display. Most devices use 0. AYN Thor's bottom
 *  screen is 4 — stamping wrong loses the inject on the bottom display.
 * @param eventTime Monotonic nanos from `SystemClock.uptimeMillis()` at the
 *  call site. Forwarded to `KeyEvent.setDownTime` / `eventTime` constructor
 *  so the inject preserves event-time relativity (matters for hold-to-repeat).
 */
@Parcelize
data class InjectKeyRequest(
    val keyCode: Int,
    val action: Int,
    val displayId: Int,
    val eventTime: Long,
) : Parcelable
