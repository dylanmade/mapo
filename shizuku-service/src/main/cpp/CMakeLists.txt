cmake_minimum_required(VERSION 3.22.1)
project("mappo_uinput")

# Shared native library backing two virtual InputDevices that Mappo's shell-UID
# Shizuku UserService drives via /dev/uinput. Both devices live in the same .so
# because they share build infra + the shell-UID context that grants
# /dev/uinput access without root.
#
#  - uinput.c           — virtual SOURCE_MOUSE: REL_X/Y, BTN_LEFT/MIDDLE/RIGHT,
#                         REL_WHEEL/HWHEEL. Drives the Joystick Mouse / Joystick
#                         Camera / trackpad cursor paths (Brick J).
#  - uinput_gamepad.c   — virtual XInput-style gamepad: ABS_X/Y/Z/RZ,
#                         ABS_BRAKE/GAS, ABS_HAT0X/Y, BTN_A..BTN_THUMBR.
#                         Drives Joystick Move + NONE-mode analog silence
#                         (Brick C).
#  - uinput_stylus.c    — virtual stylus / pen: BTN_TOOL_PEN + ABS_X/Y.
#                         Drives Mouse Region's absolute positioning path,
#                         taking advantage of Wine treating stylus tool-type
#                         as absolute (vs. the relative model it uses for
#                         finger touch + REL mouse). Brick C.4 follow-up.
#  - evdev.c            — ioctl ops on /dev/input/event* devices (READ side,
#                         not uinput WRITE side). Currently just EVIOCGRAB
#                         for taking exclusive access to physical
#                         controllers so the OS InputReader stops
#                         dispatching their events to games while Mappo
#                         routes them through its virtual gamepad
#                         (resolves the dual-controller race when gyro
#                         stick modes are active).
add_library(mappo_uinput SHARED uinput.c uinput_gamepad.c uinput_stylus.c evdev.c)

find_library(log-lib log)
target_link_libraries(mappo_uinput ${log-lib})
