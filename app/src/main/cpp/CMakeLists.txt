cmake_minimum_required(VERSION 3.22.1)
project(kodelab_pty C)

add_library(kodelab_pty SHARED kodelab_pty.c)
target_link_libraries(kodelab_pty log)

# Android 15+ ships devices with 16 KB memory pages. A .so whose LOAD segments
# are aligned to the old 4 KB page can't be mapped there, which the platform
# reports as "this app isn't 16 KB compatible — ELF alignment check failed".
# NDK r28 defaults to this; we set it explicitly so the build is correct on the
# r26 toolchain too. 32-bit ABIs keep 4 KB pages and ignore the flag.
target_link_options(kodelab_pty PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
