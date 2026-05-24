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
set(TDS_LIBRARY "${TDS_SDK_ROOT}/linux_x86_64/libtds_api.so")
set(TDS_CPACK "${TDS_SDK_ROOT}/linux_x86_64/cpack.dat")

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

add_executable(tds_adapter tds_adapter.cpp)
target_include_directories(tds_adapter PRIVATE "${TDS_INCLUDE_DIR}")
target_link_libraries(tds_adapter PRIVATE "${TDS_LIBRARY}")
set_target_properties(
    tds_adapter
    PROPERTIES
        BUILD_RPATH "${TDS_SDK_ROOT}/linux_x86_64"
        INSTALL_RPATH "$ORIGIN"
)
