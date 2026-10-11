# Self-built JVMTI agent for Compose inspection.
#
# Mirrors the upstream Bazel target //tools/base/ui-inspector/agent/native:lib_ui_inspector_agent.so
# while replacing Bazel with the Android NDK toolchain. The path variables are supplied by
# :layout-inspector:compose-agent-build so the vendored trees stay untouched.

cmake_minimum_required(VERSION 3.22.1)
project(ui_inspector_agent CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

foreach(required AGENT_SRC_DIR TRANSPORT_ROOT SLICER_ROOT JVMTI_INCLUDE_DIR)
    if(NOT DEFINED ${required})
        message(FATAL_ERROR "${required} must be defined by the Gradle build")
    endif()
endforeach()

set(TRANSPORT_NATIVE "${TRANSPORT_ROOT}/tools/base/transport/native")
file(GLOB SLICER_SOURCES "${SLICER_ROOT}/*.cc")

add_library(ui_inspector_agent SHARED
    # Upstream ui-inspector agent sources.
    ${AGENT_SRC_DIR}/agent.cc
    ${AGENT_SRC_DIR}/array_params_entry_hook.cc
    ${AGENT_SRC_DIR}/art_tooling_bridge_jni.cc
    ${AGENT_SRC_DIR}/jvmti_art_tooling.cc
    ${AGENT_SRC_DIR}/array_params_entry_hook.h
    ${AGENT_SRC_DIR}/jvmti_art_tooling.h
    ${AGENT_SRC_DIR}/ui_inspector_transform.h

    # transport/native closure (jvmti helpers + the utils the agent actually references).
    ${TRANSPORT_NATIVE}/jvmti/hidden_api_silencer.cc
    ${TRANSPORT_NATIVE}/jvmti/jvmti_helper.cc
    ${TRANSPORT_NATIVE}/utils/bash_command.cc
    ${TRANSPORT_NATIVE}/utils/bash_command.android.cc
    ${TRANSPORT_NATIVE}/utils/device_info.android.cc
    ${TRANSPORT_NATIVE}/utils/log.android.cc
    ${TRANSPORT_NATIVE}/utils/tokenizer.cc
    ${TRANSPORT_NATIVE}/utils/trace.cc
    ${TRANSPORT_NATIVE}/utils/fs/dir.cc
    ${TRANSPORT_NATIVE}/utils/fs/disk_file_system.cc
    ${TRANSPORT_NATIVE}/utils/fs/file.cc
    ${TRANSPORT_NATIVE}/utils/fs/file_system.cc
    ${TRANSPORT_NATIVE}/utils/fs/path.cc

    # slicer: dex IR + entry-point instrumentation.
    ${SLICER_SOURCES}
)

target_include_directories(ui_inspector_agent PRIVATE
    # jvmti.h is not part of the NDK; the Gradle task copies the JDK's header here so that its
    # #include "jni.h" still resolves to the NDK's jni.h.
    ${JVMTI_INCLUDE_DIR}
    ${AGENT_SRC_DIR}
    # tools/base/transport/native/... as included by the agent sources.
    ${TRANSPORT_ROOT}
    # utils/... as included by the transport sources themselves.
    ${TRANSPORT_NATIVE}
    # slicer/dex_ir.h and export/slicer/*.h respectively.
    ${SLICER_ROOT}/export
    ${SLICER_ROOT}
)

find_library(log-lib log)
find_library(z-lib z)
target_link_libraries(ui_inspector_agent ${log-lib} ${z-lib})

# 16 KiB page-size devices.
target_link_options(ui_inspector_agent PRIVATE "-Wl,-z,max-page-size=16384")
