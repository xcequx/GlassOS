# Builds libglasses_bridge.so — the JNI bridge between GlassOS and the native VITURE SDK.
#
# If the proprietary SDK is not vendored (libglasses.so + headers), we compile a stub
# so the rest of the app still builds: display / trackpad / workspace keep working,
# head tracking and device control stay no-ops until the SDK is dropped in.
cmake_minimum_required(VERSION 3.22.1)
project(glasses_bridge)

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")
set(SDK_SO "${JNILIBS_DIR}/libglasses.so")
set(SDK_HEADER "${INCLUDE_DIR}/viture_glasses_provider.h")

if(EXISTS "${SDK_SO}" AND EXISTS "${SDK_HEADER}")
    message(STATUS "VITURE SDK found — building real glasses_bridge")
    add_library(glasses SHARED IMPORTED)
    set_target_properties(glasses PROPERTIES
        IMPORTED_LOCATION "${SDK_SO}")
    add_library(glasses_bridge SHARED glasses_bridge.cpp)
    target_include_directories(glasses_bridge PRIVATE ${INCLUDE_DIR})
    target_link_libraries(glasses_bridge glasses android log)
else()
    message(WARNING "VITURE SDK not vendored — building stub glasses_bridge (no IMU / device control)")
    add_library(glasses_bridge SHARED glasses_bridge_stub.cpp)
    target_link_libraries(glasses_bridge android log)
endif()
