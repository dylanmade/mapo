// Mapo virtual XInput-style gamepad InputDevice via /dev/uinput.
//
// Companion to uinput.c (virtual mouse). Same pattern: open /dev/uinput from the
// shell-UID UserService, declare the device's button + axis capabilities, register
// via UI_DEV_CREATE, then write input_event groups for each axis/button update.
// The kernel surfaces the device through /dev/input/eventN, Android's InputReader
// picks it up and registers a real InputDevice, and games read it like any other
// connected gamepad.
//
// Why this exists: JoystickMoveMode and MouseRegionMode need to push real analog
// stick values to the foreground app. AccessibilityService can't inject MotionEvents
// with SOURCE_JOYSTICK semantics, and per the focused-overlay tabling work, the
// only viable path on unrooted Android 13 is a real kernel-level gamepad device.
//
// Axis convention follows the Linux xpad driver's layout for Xbox 360 wired
// controllers (vendor 0x045E / product 0x028E). This is what Android's
// InputReader expects for events emitted by a device with this vendor/product
// pair — it's distinct from Thor's *physical* controller, which reports its
// own axes per Android-handheld convention (see reference_thor_axis_convention.md).
//
//   Left stick:    ABS_X / ABS_Y     (-32768..32767, signed int16)
//   Right stick:   ABS_RX / ABS_RY   (-32768..32767, signed int16)
//   Left trigger:  ABS_Z             (0..255 unsigned, xpad LT convention)
//   Right trigger: ABS_RZ            (0..255 unsigned, xpad RT convention)
//   D-pad hat:     ABS_HAT0X / ABS_HAT0Y (-1, 0, 1)
//
// **Why this matters (bug fix 2026-05-31).** A prior layout used ABS_Z/ABS_RZ
// for the right stick (Android-handheld convention) and ABS_BRAKE/ABS_GAS for
// triggers. With the Xbox 360 vendor/product ID, Android's xpad-derived
// mapping treats ABS_Z = LT and ABS_RZ = RT — so writing right-stick deflection
// to ABS_Z/RZ caused KEYCODE_BUTTON_L2/R2 to fire on every stick movement (Thor
// has a ~2% trigger click threshold). Fixed by aligning to the layout Android's
// xpad mapping expects for our declared identity.
//
// Device identity is Microsoft + Xbox 360 wired (0x045E / 0x028E) so games that
// gate "is this really a controller" on the vendor/product ID accept it. The
// device *name* stays "Mapo Virtual Gamepad" so it's identifiable in
// /proc/bus/input/devices and `getevent -l` for debugging.

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

#define LOG_TAG "UinputGamepad"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int set_bit_g(int fd, unsigned long req, int bit, const char *desc) {
    if (ioctl(fd, req, bit) < 0) {
        LOGE("ioctl(%s, %d) failed: errno=%d (%s)", desc, bit, errno, strerror(errno));
        return -1;
    }
    return 0;
}

// Open /dev/uinput, declare gamepad capabilities, create the virtual device.
// Returns the fd on success (>=0), or -1 on any failure.
JNIEXPORT jint JNICALL
Java_com_mapo_shizuku_service_UinputGamepad_nativeOpen(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: errno=%d (%s)", errno, strerror(errno));
        return -1;
    }
    LOGI("opened /dev/uinput, fd=%d", fd);

    // Buttons — XInput-equivalent set. BTN_MODE is the Xbox "guide" button.
    if (set_bit_g(fd, UI_SET_EVBIT, EV_KEY, "EV_KEY") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_A, "BTN_A") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_B, "BTN_B") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_X, "BTN_X") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_Y, "BTN_Y") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_TL, "BTN_TL") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_TR, "BTN_TR") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_TL2, "BTN_TL2") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_TR2, "BTN_TR2") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_SELECT, "BTN_SELECT") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_START, "BTN_START") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_MODE, "BTN_MODE") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_THUMBL, "BTN_THUMBL") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_KEYBIT, BTN_THUMBR, "BTN_THUMBR") < 0) goto fail;

    // Absolute axes — sticks, triggers, D-pad hat.
    if (set_bit_g(fd, UI_SET_EVBIT, EV_ABS, "EV_ABS") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_ABSBIT, ABS_X, "ABS_X") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_ABSBIT, ABS_Y, "ABS_Y") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_ABSBIT, ABS_RX, "ABS_RX") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_ABSBIT, ABS_RY, "ABS_RY") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_ABSBIT, ABS_Z, "ABS_Z") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_ABSBIT, ABS_RZ, "ABS_RZ") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_ABSBIT, ABS_HAT0X, "ABS_HAT0X") < 0) goto fail;
    if (set_bit_g(fd, UI_SET_ABSBIT, ABS_HAT0Y, "ABS_HAT0Y") < 0) goto fail;

    if (set_bit_g(fd, UI_SET_EVBIT, EV_SYN, "EV_SYN") < 0) goto fail;

    // Device identity + per-axis ranges. Microsoft + Xbox 360 wired controller
    // IDs maximize game compat (some games gate on a vendor/product allowlist).
    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "Mapo Virtual Gamepad");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor = 0x045E;    // Microsoft
    uidev.id.product = 0x028E;   // Xbox 360 wired controller
    uidev.id.version = 0x0114;

    // Sticks: signed int16 range. Fuzz=16 / flat=128 are typical Xbox-controller
    // values — they let the InputReader filter noise + apply a small deadzone.
    uidev.absmin[ABS_X] = -32768;  uidev.absmax[ABS_X] = 32767;
    uidev.absfuzz[ABS_X] = 16;     uidev.absflat[ABS_X] = 128;
    uidev.absmin[ABS_Y] = -32768;  uidev.absmax[ABS_Y] = 32767;
    uidev.absfuzz[ABS_Y] = 16;     uidev.absflat[ABS_Y] = 128;
    uidev.absmin[ABS_RX] = -32768; uidev.absmax[ABS_RX] = 32767;
    uidev.absfuzz[ABS_RX] = 16;    uidev.absflat[ABS_RX] = 128;
    uidev.absmin[ABS_RY] = -32768; uidev.absmax[ABS_RY] = 32767;
    uidev.absfuzz[ABS_RY] = 16;    uidev.absflat[ABS_RY] = 128;

    // Triggers: ABS_Z = LT, ABS_RZ = RT (xpad convention). 0..255 unsigned.
    uidev.absmin[ABS_Z] = 0;       uidev.absmax[ABS_Z] = 255;
    uidev.absmin[ABS_RZ] = 0;      uidev.absmax[ABS_RZ] = 255;

    // D-pad hat: -1 / 0 / 1.
    uidev.absmin[ABS_HAT0X] = -1;  uidev.absmax[ABS_HAT0X] = 1;
    uidev.absmin[ABS_HAT0Y] = -1;  uidev.absmax[ABS_HAT0Y] = 1;

    if (write(fd, &uidev, sizeof(uidev)) != sizeof(uidev)) {
        LOGE("write uinput_user_dev failed: errno=%d (%s)", errno, strerror(errno));
        goto fail;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: errno=%d (%s)", errno, strerror(errno));
        goto fail;
    }

    LOGI("uinput gamepad created successfully, fd=%d", fd);
    return fd;

fail:
    close(fd);
    return -1;
}

// Batch-write all eight analog axes (left stick X/Y, right stick X/Y, left/right
// triggers, dpad hat X/Y), followed by SYN_REPORT. Caller decides cadence —
// typically one call per AnalogEvent the evaluator routes to this device.
//
// Stick + dpad values are int16-range signed; triggers are 0..255 unsigned.
// Caller is responsible for clamping into the appropriate ranges before
// calling (no clamping done here — invalid values would just be passed to
// the kernel, which clamps on its own).
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputGamepad_nativeWriteAxes(
    JNIEnv *env, jclass clazz, jint fd,
    jint lx, jint ly, jint rx, jint ry,
    jint lt, jint rt, jint hatX, jint hatY) {
    (void)env; (void)clazz;
    if (fd < 0) return;

    struct input_event ev[9];
    memset(ev, 0, sizeof(ev));

    ev[0].type = EV_ABS; ev[0].code = ABS_X;       ev[0].value = lx;
    ev[1].type = EV_ABS; ev[1].code = ABS_Y;       ev[1].value = ly;
    ev[2].type = EV_ABS; ev[2].code = ABS_RX;      ev[2].value = rx;
    ev[3].type = EV_ABS; ev[3].code = ABS_RY;      ev[3].value = ry;
    ev[4].type = EV_ABS; ev[4].code = ABS_Z;       ev[4].value = lt;
    ev[5].type = EV_ABS; ev[5].code = ABS_RZ;      ev[5].value = rt;
    ev[6].type = EV_ABS; ev[6].code = ABS_HAT0X;   ev[6].value = hatX;
    ev[7].type = EV_ABS; ev[7].code = ABS_HAT0Y;   ev[7].value = hatY;
    ev[8].type = EV_SYN; ev[8].code = SYN_REPORT;  ev[8].value = 0;

    ssize_t expected = sizeof(ev);
    ssize_t actual = write(fd, ev, expected);
    if (actual != expected) {
        LOGW("write axes failed: expected=%zd actual=%zd errno=%d", expected, actual, errno);
    }
}

// Press or release a single button (BTN_A / BTN_TL / etc.). Emits the
// EV_KEY event followed by SYN_REPORT. Caller passes the standard
// linux/input.h BTN_* constant as `btnCode`.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputGamepad_nativeWriteButton(
    JNIEnv *env, jclass clazz, jint fd, jint btnCode, jint pressed) {
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

// Tear down the virtual device + close the fd. Idempotent on -1.
JNIEXPORT void JNICALL
Java_com_mapo_shizuku_service_UinputGamepad_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void)env; (void)clazz;
    if (fd < 0) return;
    if (ioctl(fd, UI_DEV_DESTROY) < 0) {
        LOGW("UI_DEV_DESTROY failed: errno=%d (%s)", errno, strerror(errno));
    }
    close(fd);
    LOGI("uinput gamepad destroyed, fd=%d", fd);
}
