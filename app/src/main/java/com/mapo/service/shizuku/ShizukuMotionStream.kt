package com.mapo.service.shizuku

import android.os.RemoteException
import android.util.Log
import com.mapo.di.ApplicationScope
import com.mapo.shizuku.IMapoInputCallback
import com.mapo.shizuku.RawAnalogEvent
import com.mapo.shizuku.ShizukuServiceHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side bridge to the Shizuku UserService's motion-event stream. Subscribes
 * to [ShizukuConnection.service] and re-registers our callback every time the
 * binder cycles (service restart, Shizuku service flap, etc.).
 *
 * **Brick C scope.** Emits raw `RawAnalogEvent`s onto [analogEvents] and logs
 * each at debug level. No conversion to `AnalogReading` yet — Brick D plumbs
 * the conversion + feeds `InputEvaluator.handleAnalogReadings`.
 *
 * **Threading.** [callback] methods run on a binder thread. We emit via a
 * SharedFlow that downstream consumers can collect on whatever scope they like.
 * Buffer capacity is sized for occasional bursts (a stick deflection can fire
 * 10-20 events per axis in a few ms while reaching its extreme).
 */
@Singleton
class ShizukuMotionStream @Inject constructor(
    private val connection: ShizukuConnection,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _analogEvents = MutableSharedFlow<RawAnalogEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val analogEvents: SharedFlow<RawAnalogEvent> = _analogEvents.asSharedFlow()

    private val callback = object : IMapoInputCallback.Stub() {
        override fun onAnalogEvent(event: RawAnalogEvent?) {
            if (event == null) return
            Log.d(TAG, "RawAnalogEvent ${event.sourceOrdinal} x=${event.x} y=${event.y}")
            _analogEvents.tryEmit(event)
        }

        override fun onDeviceAdded(deviceId: Int, name: String?, capsBitmap: Int) {
            Log.i(TAG, "device added: id=$deviceId name=$name caps=0x${capsBitmap.toString(16)}")
        }

        override fun onDeviceRemoved(deviceId: Int) {
            Log.i(TAG, "device removed: id=$deviceId")
        }

        override fun onServiceHealth(health: ShizukuServiceHealth?) {
            Log.d(TAG, "service health: $health")
        }
    }

    init {
        scope.launch {
            connection.service.collect { svc ->
                if (svc != null) {
                    try {
                        svc.registerCallback(callback)
                        Log.i(TAG, "registered callback on UserService")
                    } catch (e: RemoteException) {
                        Log.w(TAG, "registerCallback failed; UserService may be dying", e)
                    } catch (e: Throwable) {
                        Log.w(TAG, "registerCallback unexpected failure", e)
                    }
                }
                // Intentionally NOT calling unregisterCallback on null transition:
                // the binder is dead → the callback's binder is implicitly dropped
                // by RemoteCallbackList on the service side. Calling unregister via
                // a dead binder throws DeadObjectException; better to let the
                // service garbage-collect us.
            }
        }
    }

    companion object {
        private const val TAG = "ShizukuMotionStream"
    }
}
