cmake_minimum_required(VERSION 3.22.1)
project("cipher_security")

add_library(
    cipher_security
    SHARED
    security.c
)

target_link_libraries(
    cipher_security
    android
    log
)