// Mapo virtual stylus InputDevice via /dev/uinput.
//
// Why this device class (vs. SOURCE_MOUSE / touchscreen): for Mouse Region
// we want *absolute* cursor positioning that Wine in GameNative honors. Real
// mouse REL events go through Wine's per-event acceleration curve which
// distorted Mapo's pin-then-move attempts (verified on AYN Thor 2026-05-30).
// Synthetic finger touch (dispatchGesture) goes through Android's touch
// pipeline, which Wine treats as a *relative* touchpad — also wrong. A
// stylus (BTN_TOOL_PEN + ABS_X/Y) is the third tool-type Android exposes;
// many Wine/Proton paths treat stylus tool-type as absolute pen positioning.
// Whether GameNative honors it is an empirical question — this device is the
// minimum-viable surface to find out.
//
// Capabilities declared:
//   - EV_KEY + BTN_TOOL_PEN ........... pen-in-proximity declaration; without
//                                       this the device is misclassified.
//   - EV_KEY + BTN_TOUCH .............. pen-on-surface. We leave it at 0 for
//                                       Mouse Region (hover-only — we just
//                                       want cursor tracking, no clicks).
//                                       Exposed in case a later brick wants
//                                       to drive taps from the stick.
//   - EV_ABS + ABS_X / ABS_Y .......... absolute position in [0, max].
//   - EV_SYN .......................... required for batching.
//
// `absmin` / `absmax` are set per-device-open via the maxX/maxY args; the
// caller passes the live display size so the device's coord space matches
// the screen 1:1 (Android InputReader scales viewport accordingly).

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "UinputStylus"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int set_bit_stylus(int fd, unsigned long req, int bit, const char *desc) {
    if (ioctl(fd, req, bit) < 0) {
        LOGE("ioctl(%s, %d) failed: errno=%d (%s)", desc, bit, errno, strerror(errno));
        return -1;
    }
    return 0;
}

// Open /dev/uinput and create the virtual stylus.
// `maxX` / `maxY` are the absmax for ABS_X / ABS_Y; pass the live display
// pixel width / height so the device's coord space matches the screen 1:1.
// Returns the uinput fd on success (>= 0), or -1 on any failure.
JNIEXPORT jint JNICALL
Java_com_mapo_shizuku_service_UinputStylus_nativeOpen(JNIEnv *env, jclass clazz, jint maxX, jint maxY) {
    (void)env; (void)clazz;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    LOGI("opened /dev/uinput, fd=%d, abs range %dx%d", fd, maxX, maxY);

    if (set_bit_stylus(fd, UI_SET_EVBIT, EV_KEY, "UI_SET_EVBIT EV_KEY") < 0) goto fail;
    if (set_bit_stylus(fd, UI_SET_KEYBIT, BTN_TOOL_PEN, "BTN_TOOL_PEN") < 0) goto fail;
    if (set_bit_stylus(fd, UI_SET_KEYBIT, BTN_TOUCH, "BTN_TOUCH") < 0) goto fail;

    if (set_bit_stylus(fd, UI_SET_EVBIT, EV_ABS, "UI_SET_EVBIT EV_ABS") < 0) goto fail;
    if (set_bit_stylus(fd, UI_SET_ABSBIT, ABS_X, "ABS_X") < 0) goto fail;
    if (set_bit_stylus(fd, UI_SET_ABSBIT, ABS_Y, "ABS_Y") < 0) goto fail;

    if (set_bit_stylus(fd, UI_SET_EVBIT, EV_SYN, "UI_SET_EVBIT EV_SYN") < 0) goto fail;

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Mapo Virtual Stylus");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor = 0x4D41;   // 'MA'
    uidev.id.product = 0x5354;  // 'ST'
    uidev.id.version = 1;

    uidev.absmin[ABS_X] = 0;
    uidev.absmax[ABS_X] = maxX;
    uidev.absmin[ABS_Y] = 0;
    uidev.absmax[ABS_Y] = maxY;

    if (write(fd, &uidev, sizeof(uidev)) != sizeof(uidev)) {
        LOGE("write uinput_user_dev failed: errno=%d (%s)", errno, strerror(errno));
        goto fail;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: errno=%d (%s)", errno, strerror(errno));
        goto fail;
    }

    // Announce the pen as "in proximity" immediately. Without an active
    // BTN_TOOL_PEN=1 the InputReader / dispatcher treat subsequent ABS_X/Y
    // events as not-yet-tracking-a-tool and may drop them.
    {
        struct input_event ev[2];
        memset(ev, 0, sizeof(ev));
        ev[0].type = EV_KEY;
        ev[0].code = BTN_TOOL_PEN;
        ev[0].value = 1;
        ev[1].type = EV_SYN;
        ev[1].code = SYN_REPORT;
        ev[1].value = 0;
        if (write(fd, ev, sizeof(ev)) != sizeof(ev)) {
            LOGW("initial BTN_TOOL_PEN=1 write failed: errno=%d", errno);
        }
    }

    LOGI("uinput stylus created successfully, fd=%d", fd);
    return fd;

fail:
    close(fd);
    return -1;
}

// Move the stylus to absolute position (x, y) within the device's ABS range.
// Caller must clamp to [0, maxX-1] / [0, maxY-1]. Emits EV_ABS + EV_SYN.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputStylus_nativeMoveAbsolute(JNIEnv *env, jclass clazz, jint fd, jint x, jint y) {
    (void)env; (void)clazz;
    if (fd < 0) return;

    struct input_event ev[3];
    memset(ev, 0, sizeof(ev));

    ev[0].type = EV_ABS;
    ev[0].code = ABS_X;
    ev[0].value = x;

    ev[1].type = EV_ABS;
    ev[1].code = ABS_Y;
    ev[1].value = y;

    ev[2].type = EV_SYN;
    ev[2].code = SYN_REPORT;
    ev[2].value = 0;

    ssize_t actual = write(fd, ev, sizeof(ev));
    if (actual != (ssize_t)sizeof(ev)) {
        LOGW("write move failed: expected=%zd actual=%zd errno=%d",
             (ssize_t)sizeof(ev), actual, errno);
    }
}

// Toggle BTN_TOUCH (pen on surface, value 1) or release (value 0). Mouse
// Region today doesn't call this — exposed for a later brick that wants
// stick-driven taps. Hovering-only (BTN_TOOL_PEN=1, BTN_TOUCH=0) gives us
// cursor tracking without click events.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputStylus_nativeSetContact(JNIEnv *env, jclass clazz, jint fd, jint pressed) {
    (void)env; (void)clazz;
    if (fd < 0) return;

    struct input_event ev[2];
    memset(ev, 0, sizeof(ev));

    ev[0].type = EV_KEY;
    ev[0].code = BTN_TOUCH;
    ev[0].value = pressed;

    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;

    if (write(fd, ev, sizeof(ev)) != sizeof(ev)) {
        LOGW("write touch failed: errno=%d", errno);
    }
}

// Destroy the virtual device and close the fd. Idempotent on -1.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputStylus_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    if (fd < 0) return;
    if (ioctl(fd, UI_DEV_DESTROY) < 0) {
        LOGW("UI_DEV_DESTROY failed: errno=%d (%s)", errno, strerror(errno));
    }
    close(fd);
    LOGI("uinput stylus destroyed, fd=%d", fd);
}
