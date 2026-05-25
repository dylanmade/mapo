package com.mapo.service.overlay.keyboard

import android.graphics.Region
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Reflection bridge to `ViewTreeObserver.OnComputeInternalInsetsListener` +
 * `InternalInsetsInfo`, which are `@hide` in the public Android SDK.
 *
 * **Why reflection.** No public API exists to declare per-rect touchable regions on a
 * system-overlay window. The internal `setTouchableInsets(TOUCHABLE_INSETS_REGION)` +
 * `touchableRegion` API on `InternalInsetsInfo` is the canonical (and only) way. Runtime
 * hidden-API enforcement is bypassed by the `HiddenApiBypass` exemption installed in
 * [com.mapo.MapoApplication.installHiddenApiExemptions]; the compile-time invisibility
 * is handled here via [Proxy] + [Method].
 *
 * Failure mode is "no passthrough" — if reflection breaks under a future Android release,
 * the overlay still works, it just absorbs touches in empty areas instead of leaking them
 * to the foreground app.
 */
internal object OverlayTouchableInsets {

    private const val TAG = "OverlayTouchableInsets"

    private inline fun <T> tryReflect(label: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "reflection failed: $label", e)
        null
    }

    private val listenerClass: Class<*>? = tryReflect("listenerClass") {
        Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
    }

    private val infoClass: Class<*>? = tryReflect("infoClass") {
        Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
    }

    private val touchableInsetsRegion: Int? = tryReflect("TOUCHABLE_INSETS_REGION") {
        infoClass?.getField("TOUCHABLE_INSETS_REGION")?.getInt(null)
    }

    private val setTouchableInsetsMethod: Method? = tryReflect("setTouchableInsets") {
        infoClass?.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
    }

    private val touchableRegionField = tryReflect("touchableRegion field") {
        infoClass?.getField("touchableRegion")
    }

    private val addListenerMethod: Method? = tryReflect("addOnComputeInternalInsetsListener") {
        listenerClass?.let { ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", it) }
    }

    private val removeListenerMethod: Method? = tryReflect("removeOnComputeInternalInsetsListener") {
        listenerClass?.let { ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", it) }
    }

    /**
     * Install a listener on [view] that populates the window's touchable region from
     * [populate] on every internal-insets compute pass. Returns an opaque handle to pass
     * back to [uninstall], or null if reflection failed (Android version mismatch).
     */
    fun install(view: View, populate: (Region) -> Unit): Any? {
        val lc = listenerClass ?: return null.also { Log.w(TAG, "listener class unavailable") }
        val tir = touchableInsetsRegion ?: return null.also { Log.w(TAG, "TOUCHABLE_INSETS_REGION unavailable") }
        val stm = setTouchableInsetsMethod ?: return null
        val trf = touchableRegionField ?: return null
        val alm = addListenerMethod ?: return null

        val proxy = Proxy.newProxyInstance(lc.classLoader, arrayOf(lc)) { _, method, args ->
            if (method.name == "onComputeInternalInsets" && !args.isNullOrEmpty()) {
                val info = args[0]
                try {
                    stm.invoke(info, tir)
                    val region = trf.get(info) as Region
                    region.setEmpty()
                    populate(region)
                } catch (e: Throwable) {
                    Log.w(TAG, "onComputeInternalInsets bridge failed", e)
                }
            }
            null
        }
        return try {
            alm.invoke(view.viewTreeObserver, proxy)
            Log.i(TAG, "installed touchable-region bridge on $view")
            proxy
        } catch (e: Throwable) {
            Log.w(TAG, "addOnComputeInternalInsetsListener failed", e)
            null
        }
    }

    fun uninstall(view: View, handle: Any?) {
        val proxy = handle ?: return
        val rlm = removeListenerMethod ?: return
        try {
            rlm.invoke(view.viewTreeObserver, proxy)
        } catch (_: Throwable) { /* viewTreeObserver may already be dead */ }
    }
}
