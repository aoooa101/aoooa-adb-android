cmake_minimum_required(VERSION 3.22.1)

project("aoooa_adb_native")

add_library(
    aoooa_adb_native
    SHARED
    aoooa_adb_native.c
)

find_library(
    log-lib
    log
)

target_link_libraries(
    aoooa_adb_native
    ${log-lib}
)
