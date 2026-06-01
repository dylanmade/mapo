// JNI ops on /dev/input/event* devices Mapo's UserService reads. The
// reader / event-parsing path itself is all Kotlin via Os.read / Os.open;
// this file is the home for ioctl operations the Kotlin layer can't do
// directly (android.system.Os doesn't expose arbitrary ioctls).
//
// Currently: EVIOCGRAB. Linux's "take exclusive access of an evdev device"
// ioctl. While Mapo holds the grab, no other process — including Android's
// system InputReader — receives events from the device. This is how Mapo
// stops the game from seeing the physical controller's events directly,
// so Mapo's virtual gamepad becomes the only path that reaches the game
// (avoiding the dual-controller race the user reported on AYN Thor
// 2026-06-01).

#include <errno.h>
#include <jni.h>
#include <linux/input.h>
#include <string.h>
#include <sys/ioctl.h>

#include <android/log.h>

#define TAG "MapoEvdev"

/**
 * Acquire (grab=1) or release (grab=0) exclusive access to the evdev
 * device referenced by [fd]. Returns 0 on success or a negative errno
 * on failure.
 *
 * Common failure modes:
 *  - EBUSY: another process already holds the grab.
 *  - EBADF: fd was closed.
 *  - ENOTTY: fd doesn't point at an evdev device.
 *
 * Idempotent at the kernel level — calling grab(1) twice from the same
 * fd is a no-op; calling grab(0) on an ungrabbed fd is fine.
 */
JNIEXPORT jint JNICALL
Java_com_mapo_shizuku_service_EvdevGrab_nativeGrab(JNIEnv *env, jclass clazz, jint fd, jint grab) {
    (void) env;
    (void) clazz;
    if (ioctl(fd, EVIOCGRAB, (unsigned long) grab) < 0) {
        int err = errno;
        __android_log_print(
            ANDROID_LOG_WARN,
            TAG,
            "EVIOCGRAB(%d) on fd=%d failed: %s",
            grab, fd, strerror(err)
        );
        return -err;
    }
    return 0;
}
