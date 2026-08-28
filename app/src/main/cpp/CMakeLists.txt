cmake_minimum_required(VERSION 3.22.1)
project(kodelab_pty C)

add_library(kodelab_pty SHARED kodelab_pty.c)
target_link_libraries(kodelab_pty log)
