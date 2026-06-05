package com.mapo.service.overlay.element

import com.mapo.data.model.OverlayElement
import com.mapo.data.model.RemapTarget
import com.mapo.data.repository.OverlayRepository
import com.mapo.data.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared edit operations on the rebuilt overlay's [OverlayElement]s (Brick C of
 * `OVERLAY_REBUILD_PLAN.md`). `@Singleton` so **both** editor prototypes write through
 * the same source of truth:
 *  - the in-app canvas editor (`OverlayEditorScreen`, via `OverlayEditorViewModel`), and
 *  - the live on-overlay editor (`OverlayLiveEditController`, which runs outside the
 *    activity and can't reach a `ViewModel`).
 *
 * Operations target the active profile's overlay. The run-mode [OverlayPresenter] and
 * both editors all observe the repo, so an edit here reflects everywhere immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OverlayEditor @Inject constructor(
    private val overlayRepository: OverlayRepository,
    private val profileRepository: ProfileRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val activeProfileId: StateFlow<Long?> = profileRepository.activeProfile
        .map { it?.id }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The active profile's elements, live. Empty when no profile is active. */
    val elements: StateFlow<List<OverlayElement>> = profileRepository.activeProfile
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList())
            else overlayRepository.elementsByProfile(profile.id)
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Add a button near screen center, nudged so successive adds don't stack exactly. */
    fun addDefaultElement() {
        val profileId = activeProfileId.value ?: return
        scope.launch {
            val n = elements.value.size
            val nudge = (n % 5) * 0.04f
            overlayRepository.add(
                OverlayElement(
                    profileId = profileId,
                    label = "",
                    x = (0.40f + nudge).coerceIn(0f, 0.84f),
                    y = (0.40f + nudge).coerceIn(0f, 0.88f),
                    width = DEFAULT_WIDTH,
                    height = DEFAULT_HEIGHT,
                    zIndex = n,
                ),
            )
        }
    }

    /** Persist a new normalized rect for [id]. Inputs are clamped to sane bounds. */
    fun moveResize(id: Long, x: Float, y: Float, width: Float, height: Float) {
        scope.launch {
            val current = overlayRepository.getById(id) ?: return@launch
            val w = width.coerceIn(MIN_SIZE, 1f)
            val h = height.coerceIn(MIN_SIZE, 1f)
            overlayRepository.update(
                current.copy(
                    x = x.coerceIn(0f, 1f - w),
                    y = y.coerceIn(0f, 1f - h),
                    width = w,
                    height = h,
                ),
            )
        }
    }

    /** Update the label + tap command for [id]. */
    fun setBinding(id: Long, label: String, target: RemapTarget) {
        scope.launch {
            val current = overlayRepository.getById(id) ?: return@launch
            overlayRepository.update(current.copy(label = label, tapTarget = target.encode()))
        }
    }

    fun delete(id: Long) {
        scope.launch { overlayRepository.deleteById(id) }
    }

    companion object {
        const val DEFAULT_WIDTH = 0.16f
        const val DEFAULT_HEIGHT = 0.10f
        const val MIN_SIZE = 0.04f
    }
}
