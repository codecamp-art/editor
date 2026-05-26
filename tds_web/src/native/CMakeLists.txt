cmake_minimum_required(VERSION 3.20)
project(tds_adapter LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(TDS_SDK_ROOT "" CACHE PATH "Path to the prepared TDS SDK root")
if(NOT TDS_SDK_ROOT)
    message(FATAL_ERROR "TDS_SDK_ROOT is required")
endif()

set(TDS_INCLUDE_DIR "${TDS_SDK_ROOT}/include")

if(WIN32)
    if(NOT CMAKE_SIZEOF_VOID_P EQUAL 4)
        message(
            FATAL_ERROR
            "Windows local TDS adapter builds require a Win32/x86 generator because the vendor SDK "
            "provides 32-bit dll/lib files under win32."
        )
    endif()

    set(TDS_RUNTIME_DIR "${TDS_SDK_ROOT}/win32")
    set(TDS_LIBRARY "${TDS_RUNTIME_DIR}/tds_api.lib")
    set(TDS_CPACK "${TDS_RUNTIME_DIR}/cpack.dat")
    file(GLOB TDS_RUNTIME_FILES "${TDS_RUNTIME_DIR}/*.dll" "${TDS_RUNTIME_DIR}/*.DLL" "${TDS_RUNTIME_DIR}/*.dat")
else()
    set(TDS_RUNTIME_DIR "${TDS_SDK_ROOT}/linux_x86_64")
    set(TDS_LIBRARY "${TDS_RUNTIME_DIR}/libtds_api.so")
    set(TDS_CPACK "${TDS_RUNTIME_DIR}/cpack.dat")
    set(TDS_RUNTIME_FILES "${TDS_LIBRARY}" "${TDS_CPACK}")
endif()

foreach(REQUIRED_FILE IN ITEMS
    "${TDS_INCLUDE_DIR}/tds_api.h"
    "${TDS_INCLUDE_DIR}/tds_api_define.h"
    "${TDS_INCLUDE_DIR}/tds_api_struct_type.h"
    "${TDS_LIBRARY}"
    "${TDS_CPACK}"
)
    if(NOT EXISTS "${REQUIRED_FILE}")
        message(FATAL_ERROR "Missing required TDS SDK file: ${REQUIRED_FILE}")
    endif()
endforeach()

if(WIN32 AND NOT TDS_RUNTIME_FILES)
    message(FATAL_ERROR "Windows local TDS adapter build requires .dll/.dat runtime files under ${TDS_RUNTIME_DIR}")
endif()

add_executable(tds_adapter tds_adapter.cpp)
target_include_directories(tds_adapter PRIVATE "${TDS_INCLUDE_DIR}")
target_link_libraries(tds_adapter PRIVATE "${TDS_LIBRARY}")
set_target_properties(
    tds_adapter
    PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}"
        BUILD_RPATH "$ORIGIN"
        INSTALL_RPATH "$ORIGIN"
)

foreach(TDS_RUNTIME_FILE IN LISTS TDS_RUNTIME_FILES)
    add_custom_command(
        TARGET tds_adapter
        POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different
            "${TDS_RUNTIME_FILE}"
            "$<TARGET_FILE_DIR:tds_adapter>"
    )
endforeach()
