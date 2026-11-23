cmake_minimum_required(VERSION 3.22.1)
project(psxemu CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(psxemu SHARED
        retro_frontend.cpp
)

find_library(ANDROID_LIBRARY android)
find_library(LOG_LIBRARY log)
find_library(EGL_LIBRARY EGL)
find_library(GLESV2_LIBRARY GLESv2)

target_link_libraries(psxemu
        ${ANDROID_LIBRARY}
        ${LOG_LIBRARY}
        ${EGL_LIBRARY}
        ${GLESV2_LIBRARY}
        dl
)
