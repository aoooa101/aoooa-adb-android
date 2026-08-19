cmake_minimum_required(VERSION 3.22.1)

project("webadb_native")

add_library(
    webadb_native
    SHARED
    webadb_native.c
)

find_library(
    log-lib
    log
)

target_link_libraries(
    webadb_native
    ${log-lib}
)
