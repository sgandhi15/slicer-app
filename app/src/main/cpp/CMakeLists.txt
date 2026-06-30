cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_ANDROID_STL_TYPE "c++_shared" CACHE STRING "Force matching STL for native codec." FORCE)
set(ANDROID_STL "c++_shared" CACHE STRING "Force matching STL for native codec." FORCE)

project(slicer_mozjpeg)

include(ExternalProject)

set(MOZJPEG_VERSION "v4.1.5")
set(MOZJPEG_SOURCE_DIR "${CMAKE_CURRENT_BINARY_DIR}/mozjpeg-src")
set(MOZJPEG_BUILD_DIR "${CMAKE_CURRENT_BINARY_DIR}/mozjpeg-build")
set(MOZJPEG_INSTALL_DIR "${CMAKE_CURRENT_BINARY_DIR}/mozjpeg-install")
set(MOZJPEG_LIBRARY_NAME "${CMAKE_STATIC_LIBRARY_PREFIX}turbojpeg${CMAKE_STATIC_LIBRARY_SUFFIX}")
set(MOZJPEG_LIBRARY "${MOZJPEG_INSTALL_DIR}/lib/${MOZJPEG_LIBRARY_NAME}")
set(MOZJPEG_INCLUDE_DIR "${MOZJPEG_INSTALL_DIR}/include")

file(MAKE_DIRECTORY
    "${MOZJPEG_SOURCE_DIR}"
    "${MOZJPEG_BUILD_DIR}"
    "${MOZJPEG_INSTALL_DIR}"
    "${MOZJPEG_INCLUDE_DIR}"
    "${MOZJPEG_INSTALL_DIR}/lib"
)

add_library(
    slicer_mozjpeg
    SHARED
    mozjpeg_encoder.cpp
)

ExternalProject_Add(
    mozjpeg_project
    PREFIX "${CMAKE_CURRENT_BINARY_DIR}/_deps/mozjpeg"
    SOURCE_DIR "${MOZJPEG_SOURCE_DIR}"
    BINARY_DIR "${MOZJPEG_BUILD_DIR}"
    STAMP_DIR "${CMAKE_CURRENT_BINARY_DIR}/_deps/mozjpeg/stamp"
    DOWNLOAD_DIR "${CMAKE_CURRENT_BINARY_DIR}/_deps/mozjpeg/download"
    UPDATE_DISCONNECTED 1
    GIT_REPOSITORY "https://github.com/mozilla/mozjpeg.git"
    GIT_TAG "${MOZJPEG_VERSION}"
    GIT_SHALLOW TRUE
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DCMAKE_ANDROID_ARCH_ABI=${CMAKE_ANDROID_ARCH_ABI}
        -DANDROID_STL=${ANDROID_STL}
        -DCMAKE_ANDROID_STL_TYPE=${CMAKE_ANDROID_STL_TYPE}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_INSTALL_PREFIX=${MOZJPEG_INSTALL_DIR}
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=ON
        -DWITH_JAVA=OFF
        -DWITH_SIMD=OFF
        -DPNG_SUPPORTED=OFF
        -DBUILD_TESTING=OFF
    BUILD_BYPRODUCTS "${MOZJPEG_INSTALL_DIR}/lib/${MOZJPEG_LIBRARY_NAME}"
)

set(MOZJPEG_SOURCE "source:${MOZJPEG_VERSION}")

add_library(
    mozjpeg_native
    STATIC
    IMPORTED
)

set_target_properties(
    mozjpeg_native
    PROPERTIES
    IMPORTED_LOCATION "${MOZJPEG_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${MOZJPEG_INCLUDE_DIR}"
)

add_dependencies(mozjpeg_native mozjpeg_project)

target_include_directories(
    slicer_mozjpeg
    PRIVATE
    ${MOZJPEG_INCLUDE_DIR}
)

target_link_libraries(
    slicer_mozjpeg
    PRIVATE
    mozjpeg_native
    log
)

target_compile_definitions(
    slicer_mozjpeg
    PRIVATE
    MOZJPEG_ENABLED=1
)

message(STATUS "MozJPEG native codec enabled using source=${MOZJPEG_SOURCE}")
