// Mapo virtual SOURCE_MOUSE InputDevice via /dev/uinput.
//
// Background: synthetic MotionEvents injected via IInputManager.injectInputEvent
// with SOURCE_MOUSE require an existing SOURCE_MOUSE InputDevice to be
// registered with Android's InputReader — without one, no pointer controller
// exists and the events deliver to nothing visible. Real USB/Bluetooth mice
// register through the kernel's uinput interface. We do the same: create a
// kernel-level virtual mouse from the shell-UID UserService process, the
// kernel surfaces it via /dev/input/eventN, Android's InputReader picks it up
// and creates a real InputDevice + pointer controller. The OS renders the
// cursor; we just write REL_X/REL_Y/SYN_REPORT events.
//
// This file's surface is intentionally tiny — open/move/buttons/wheel/close —
// because everything else (cursor position, bounds, rendering, gesture-detector
// bypass, multi-display routing) is handled by the OS itself once the device
// is registered. No more MotionEvent gymnastics; no more bounds margins; no
// touch-pipeline gesture-detector contamination.

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

#define LOG_TAG "UinputMouse"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper: set an ioctl bit on the uinput fd, log + bail on failure.
static int set_bit(int fd, unsigned long req, int bit, const char *desc) {
    if (ioctl(fd, req, bit) < 0) {
        LOGE("ioctl(%s, %d) failed: errno=%d (%s)", desc, bit, errno, strerror(errno));
        return -1;
    }
    return 0;
}

// Open /dev/uinput and create a virtual SOURCE_MOUSE InputDevice.
// Returns the uinput fd on success (>=0), or -1 on any failure.
JNIEXPORT jint JNICALL
Java_com_mapo_shizuku_service_UinputMouse_nativeOpen(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    LOGI("opened /dev/uinput, fd=%d", fd);

    // Buttons (EV_KEY): left/right/middle/back/forward — full mouse complement
    // so a downstream brick can wire BTN_LEFT to Mapo's analog stick click
    // without re-opening the device.
    if (set_bit(fd, UI_SET_EVBIT, EV_KEY, "UI_SET_EVBIT EV_KEY") < 0) goto fail;
    if (set_bit(fd, UI_SET_KEYBIT, BTN_LEFT, "BTN_LEFT") < 0) goto fail;
    if (set_bit(fd, UI_SET_KEYBIT, BTN_RIGHT, "BTN_RIGHT") < 0) goto fail;
    if (set_bit(fd, UI_SET_KEYBIT, BTN_MIDDLE, "BTN_MIDDLE") < 0) goto fail;
    if (set_bit(fd, UI_SET_KEYBIT, BTN_SIDE, "BTN_SIDE") < 0) goto fail;
    if (set_bit(fd, UI_SET_KEYBIT, BTN_EXTRA, "BTN_EXTRA") < 0) goto fail;

    // Relative motion (EV_REL): X/Y for cursor, WHEEL/HWHEEL for scroll.
    if (set_bit(fd, UI_SET_EVBIT, EV_REL, "UI_SET_EVBIT EV_REL") < 0) goto fail;
    if (set_bit(fd, UI_SET_RELBIT, REL_X, "REL_X") < 0) goto fail;
    if (set_bit(fd, UI_SET_RELBIT, REL_Y, "REL_Y") < 0) goto fail;
    if (set_bit(fd, UI_SET_RELBIT, REL_WHEEL, "REL_WHEEL") < 0) goto fail;
    if (set_bit(fd, UI_SET_RELBIT, REL_HWHEEL, "REL_HWHEEL") < 0) goto fail;

    // SYN — required for the kernel to dispatch the batched event group.
    if (set_bit(fd, UI_SET_EVBIT, EV_SYN, "UI_SET_EVBIT EV_SYN") < 0) goto fail;

    // Device identity. The kernel exposes this through /sys/class/input/eventN
    // and the Android InputReader uses it for device naming. BUS_VIRTUAL makes
    // it clear in `getevent -i` output that this is a synthetic device.
    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Mapo Virtual Mouse");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor = 0x4D41;   // 'MA'
    uidev.id.product = 0x504F;  // 'PO'
    uidev.id.version = 1;

    if (write(fd, &uidev, sizeof(uidev)) != sizeof(uidev)) {
        LOGE("write uinput_user_dev failed: errno=%d (%s)", errno, strerror(errno));
        goto fail;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: errno=%d (%s)", errno, strerror(errno));
        goto fail;
    }

    LOGI("uinput mouse created successfully, fd=%d", fd);
    return fd;

fail:
    close(fd);
    return -1;
}

// Write a relative motion event group to the uinput fd.
// Emits REL_X (if dx != 0), REL_Y (if dy != 0), then SYN_REPORT to dispatch.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputMouse_nativeMove(JNIEnv *env, jclass clazz, jint fd, jint dx, jint dy) {
    (void)env; (void)clazz;
    if (fd < 0) return;

    struct input_event ev[3];
    memset(ev, 0, sizeof(ev));
    int n = 0;

    if (dx != 0) {
        ev[n].type = EV_REL;
        ev[n].code = REL_X;
        ev[n].value = dx;
        n++;
    }
    if (dy != 0) {
        ev[n].type = EV_REL;
        ev[n].code = REL_Y;
        ev[n].value = dy;
        n++;
    }
    // SYN_REPORT — without this the kernel buffers events and never dispatches.
    ev[n].type = EV_SYN;
    ev[n].code = SYN_REPORT;
    ev[n].value = 0;
    n++;

    ssize_t expected = sizeof(struct input_event) * n;
    ssize_t actual = write(fd, ev, expected);
    if (actual != expected) {
        LOGW("write motion failed: expected=%zd actual=%zd errno=%d", expected, actual, errno);
    }
}

// Write a scroll-wheel event group: REL_WHEEL (vertical) and/or REL_HWHEEL
// (horizontal), then SYN_REPORT. Values are integer "click counts" — 1 means
// one notch up/right, -1 means one notch down/left. Skips axes whose value
// is zero so the SYN_REPORT only batches non-trivial events.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputMouse_nativeScroll(JNIEnv *env, jclass clazz, jint fd, jint dx, jint dy) {
    (void)env; (void)clazz;
    if (fd < 0) return;

    struct input_event ev[3];
    memset(ev, 0, sizeof(ev));
    int n = 0;

    if (dy != 0) {
        ev[n].type = EV_REL;
        ev[n].code = REL_WHEEL;
        ev[n].value = dy;
        n++;
    }
    if (dx != 0) {
        ev[n].type = EV_REL;
        ev[n].code = REL_HWHEEL;
        ev[n].value = dx;
        n++;
    }
    if (n == 0) return; // nothing to scroll

    ev[n].type = EV_SYN;
    ev[n].code = SYN_REPORT;
    ev[n].value = 0;
    n++;

    ssize_t expected = sizeof(struct input_event) * n;
    ssize_t actual = write(fd, ev, expected);
    if (actual != expected) {
        LOGW("write scroll failed: expected=%zd actual=%zd errno=%d", expected, actual, errno);
    }
}

// Write a button-state event followed by SYN_REPORT.
// btnCode is BTN_LEFT/BTN_RIGHT/etc; pressed = 1 for down, 0 for up.
// Unused in the spike but exposed for the eventual click-binding brick.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputMouse_nativeButton(JNIEnv *env, jclass clazz, jint fd, jint btnCode, jint pressed) {
    (void)env; (void)clazz;
    if (fd < 0) return;

    struct input_event ev[2];
    memset(ev, 0, sizeof(ev));

    ev[0].type = EV_KEY;
    ev[0].code = (unsigned short)btnCode;
    ev[0].value = pressed;

    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;

    if (write(fd, ev, sizeof(ev)) != sizeof(ev)) {
        LOGW("write button failed: errno=%d", errno);
    }
}

// Destroy the virtual device and close the fd. Idempotent on -1.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputMouse_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    if (fd < 0) return;
    if (ioctl(fd, UI_DEV_DESTROY) < 0) {
        LOGW("UI_DEV_DESTROY failed: errno=%d (%s)", errno, strerror(errno));
    }
    close(fd);
    LOGI("uinput mouse destroyed, fd=%d", fd);
}
