cmake_minimum_required(VERSION 3.22)
project(slicer_native_audit LANGUAGES C CXX)

if(NOT MOZJPEG_SOURCE_DIR)
    message(FATAL_ERROR "Pass -DMOZJPEG_SOURCE_DIR pointing to the pinned MozJPEG v4.1.5 checkout")
endif()
get_filename_component(SLICER_NATIVE "${CMAKE_CURRENT_LIST_DIR}/../../main/cpp" ABSOLUTE)
include(ExternalProject)
set(CODEC_BUILD "${CMAKE_CURRENT_BINARY_DIR}/mozjpeg")
file(MAKE_DIRECTORY "${CODEC_BUILD}")
ExternalProject_Add(audit_mozjpeg
    SOURCE_DIR "${MOZJPEG_SOURCE_DIR}"
    BINARY_DIR "${CODEC_BUILD}"
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    BUILD_ALWAYS 1
    CMAKE_ARGS -DCMAKE_BUILD_TYPE=Debug -DENABLE_SHARED=OFF -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=OFF -DWITH_SIMD=OFF -DPNG_SUPPORTED=OFF -DBUILD_TESTING=OFF
        "-DCMAKE_C_FLAGS=-include \"${SLICER_NATIVE}/codec_allocation_redirect.h\" -fsanitize=address,undefined -fno-omit-frame-pointer"
    BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target jpeg-static
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${CODEC_BUILD}/libjpeg.a")
add_library(audit_codec STATIC IMPORTED)
set_target_properties(audit_codec PROPERTIES IMPORTED_LOCATION "${CODEC_BUILD}/libjpeg.a")
add_dependencies(audit_codec audit_mozjpeg)
add_executable(transcode_audit_test transcode_audit_test.cpp
    "${SLICER_NATIVE}/jpeg_transcode.cpp" "${SLICER_NATIVE}/codec_allocations.cpp")
target_include_directories(transcode_audit_test PRIVATE
    "${SLICER_NATIVE}" "${MOZJPEG_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mozjpeg")
target_compile_features(transcode_audit_test PRIVATE cxx_std_17)
target_compile_options(transcode_audit_test PRIVATE -Wall -Wextra -Werror
    -fsanitize=address,undefined -fno-omit-frame-pointer)
target_link_options(transcode_audit_test PRIVATE -fsanitize=address,undefined)
target_link_libraries(transcode_audit_test PRIVATE audit_codec)
