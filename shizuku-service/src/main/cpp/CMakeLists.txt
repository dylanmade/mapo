cmake_minimum_required(VERSION 3.22.1)
project("mapo_uinput")

# Single-file native library that opens /dev/uinput and exposes a virtual
# SOURCE_MOUSE InputDevice for Mapo's shell-UID Shizuku UserService to drive.
# The shell-UID context is what makes /dev/uinput accessible without root.
add_library(mapo_uinput SHARED uinput.c)

find_library(log-lib log)
target_link_libraries(mapo_uinput ${log-lib})
