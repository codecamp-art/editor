cmake_minimum_required(VERSION 3.20)
project(tds_reporter LANGUAGES CXX)

include(GNUInstallDirs)
include(CheckCXXSourceCompiles)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(TDS_VENDOR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/tds")
set(TDS_VENDOR_INCLUDE_DIR "${TDS_VENDOR_ROOT}/include" CACHE PATH "Path to vendor header directory")
set(TDS_VENDOR_LIBRARY "" CACHE FILEPATH "Path to vendor link library: .so on Linux, .lib on Windows")
set(TDS_VENDOR_RUNTIME "" CACHE FILEPATH "Path to vendor runtime library: .so on Linux, .dll on Windows")
set(TDS_REPORTER_STAGE_DIR "${CMAKE_BINARY_DIR}/stage" CACHE PATH "Directory used by the tds_reporter_stage target")

add_library(
    tds_reporter_lib
    src/app.cpp
    src/logging.cpp
    src/text_utils.cpp
    src/tds_client.cpp
)

target_include_directories(
    tds_reporter_lib
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        ${TDS_VENDOR_INCLUDE_DIR}
)

set(TDS_REPORTER_FILESYSTEM_TEST_SOURCE
"#include <filesystem>
int main() {
    const std::filesystem::path path{\".\"};
    return std::filesystem::absolute(path).empty() ? 1 : 0;
}
")

check_cxx_source_compiles(
    "${TDS_REPORTER_FILESYSTEM_TEST_SOURCE}"
    TDS_REPORTER_FILESYSTEM_LINKS_WITHOUT_EXTRA_LIB
)

if(NOT TDS_REPORTER_FILESYSTEM_LINKS_WITHOUT_EXTRA_LIB)
    set(TDS_REPORTER_FILESYSTEM_REQUIRED_LIBRARIES "${CMAKE_REQUIRED_LIBRARIES}")
    list(APPEND CMAKE_REQUIRED_LIBRARIES stdc++fs)
    check_cxx_source_compiles(
        "${TDS_REPORTER_FILESYSTEM_TEST_SOURCE}"
        TDS_REPORTER_FILESYSTEM_LINKS_WITH_STDCXXFS
    )
    set(CMAKE_REQUIRED_LIBRARIES "${TDS_REPORTER_FILESYSTEM_REQUIRED_LIBRARIES}")

    if(TDS_REPORTER_FILESYSTEM_LINKS_WITH_STDCXXFS)
        message(STATUS "Linking stdc++fs for std::filesystem compatibility on this toolchain")
        target_link_libraries(tds_reporter_lib PUBLIC stdc++fs)
    else()
        message(
            FATAL_ERROR
            "The current C++ toolchain cannot link std::filesystem symbols, even with stdc++fs. "
            "Install a newer libstdc++ or use a toolchain with complete C++17 filesystem support."
        )
    endif()
endif()

set(TDS_VENDOR_LIBRARY_DIR "")
set(TDS_VENDOR_LIBRARY_ABS "")
set(TDS_VENDOR_RUNTIME_ABS "")
set(TDS_VENDOR_INSTALL_FILES "")
set(TDS_REPORTER_HAS_VENDOR_API OFF)

if(WIN32)
    set(TDS_WINDOWS_TARGET_BITS 32)
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        set(TDS_WINDOWS_TARGET_BITS 64)
    endif()

    if(NOT TDS_VENDOR_LIBRARY)
        if(TDS_WINDOWS_TARGET_BITS EQUAL 32)
            if(EXISTS "${TDS_VENDOR_ROOT}/win32/tds_api.lib")
                set(TDS_VENDOR_LIBRARY "${TDS_VENDOR_ROOT}/win32/tds_api.lib" CACHE FILEPATH "Auto-detected vendor import library" FORCE)
            endif()
        else()
            message(STATUS "Skipping auto-detect from ./tds/win32 for x64 build to avoid x86/x64 linker mismatch. Set TDS_VENDOR_LIBRARY and TDS_VENDOR_RUNTIME explicitly for x64 live mode, or use the windows-live-x86 preset with the supplier win32 package.")
        endif()
    endif()

    if(NOT TDS_VENDOR_RUNTIME)
        if(TDS_VENDOR_LIBRARY)
            get_filename_component(TDS_VENDOR_LIBRARY_DIR_HINT "${TDS_VENDOR_LIBRARY}" DIRECTORY)
            get_filename_component(TDS_VENDOR_LIBRARY_NAME_WE "${TDS_VENDOR_LIBRARY}" NAME_WE)
            set(TDS_VENDOR_RUNTIME_GUESS "${TDS_VENDOR_LIBRARY_DIR_HINT}/${TDS_VENDOR_LIBRARY_NAME_WE}.dll")
            if(EXISTS "${TDS_VENDOR_RUNTIME_GUESS}")
                set(TDS_VENDOR_RUNTIME "${TDS_VENDOR_RUNTIME_GUESS}" CACHE FILEPATH "Auto-detected vendor runtime library" FORCE)
            else()
                file(GLOB TDS_WIN32_DLL_CANDIDATES "${TDS_VENDOR_LIBRARY_DIR_HINT}/*.dll")
                list(LENGTH TDS_WIN32_DLL_CANDIDATES TDS_WIN32_DLL_COUNT)
                if(TDS_WIN32_DLL_COUNT EQUAL 1)
                    set(TDS_VENDOR_RUNTIME "${TDS_WIN32_DLL_CANDIDATES}" CACHE FILEPATH "Auto-detected vendor runtime library" FORCE)
                endif()
            endif()
        elseif(TDS_WINDOWS_TARGET_BITS EQUAL 32)
            if(EXISTS "${TDS_VENDOR_ROOT}/win32/tds_api.dll")
                set(TDS_VENDOR_RUNTIME "${TDS_VENDOR_ROOT}/win32/tds_api.dll" CACHE FILEPATH "Auto-detected vendor runtime library" FORCE)
            endif()
        endif()
    endif()
endif()

if(UNIX)
    if(NOT TDS_VENDOR_LIBRARY)
        if(EXISTS "${TDS_VENDOR_ROOT}/linux_x86_64/libtds_api.so")
            set(TDS_VENDOR_LIBRARY "${TDS_VENDOR_ROOT}/linux_x86_64/libtds_api.so" CACHE FILEPATH "Auto-detected vendor shared library" FORCE)
        elseif(EXISTS "${TDS_VENDOR_ROOT}/linux_x86_64/tds_api.so")
            set(TDS_VENDOR_LIBRARY "${TDS_VENDOR_ROOT}/linux_x86_64/tds_api.so" CACHE FILEPATH "Auto-detected vendor shared library" FORCE)
        else()
            message(FATAL_ERROR "Linux build requires -DTDS_VENDOR_LIBRARY=/path/to/tds_api.so or a local ./tds/linux_x86_64/libtds_api.so")
        endif()
    endif()
    if(NOT EXISTS "${TDS_VENDOR_LIBRARY}")
        message(FATAL_ERROR "TDS_VENDOR_LIBRARY does not exist: ${TDS_VENDOR_LIBRARY}")
    endif()

    get_filename_component(TDS_VENDOR_LIBRARY_ABS "${TDS_VENDOR_LIBRARY}" ABSOLUTE)
    get_filename_component(TDS_VENDOR_LIBRARY_DIR "${TDS_VENDOR_LIBRARY_ABS}" DIRECTORY)
    set(TDS_VENDOR_RUNTIME_ABS "${TDS_VENDOR_LIBRARY_ABS}")
    target_link_libraries(tds_reporter_lib PRIVATE "${TDS_VENDOR_LIBRARY_ABS}")
    set(TDS_REPORTER_HAS_VENDOR_API ON)
elseif(WIN32)
    target_compile_definitions(tds_reporter_lib PRIVATE _WINDOWS)

    if(TDS_VENDOR_LIBRARY)
        if(NOT EXISTS "${TDS_VENDOR_LIBRARY}")
            message(FATAL_ERROR "TDS_VENDOR_LIBRARY does not exist: ${TDS_VENDOR_LIBRARY}")
        endif()
        get_filename_component(TDS_VENDOR_LIBRARY_ABS "${TDS_VENDOR_LIBRARY}" ABSOLUTE)
        get_filename_component(TDS_VENDOR_LIBRARY_DIR "${TDS_VENDOR_LIBRARY_ABS}" DIRECTORY)
        target_link_libraries(tds_reporter_lib PRIVATE "${TDS_VENDOR_LIBRARY_ABS}")
        set(TDS_REPORTER_HAS_VENDOR_API ON)

        if(TDS_VENDOR_RUNTIME)
            if(NOT EXISTS "${TDS_VENDOR_RUNTIME}")
                message(FATAL_ERROR "TDS_VENDOR_RUNTIME does not exist: ${TDS_VENDOR_RUNTIME}")
            endif()
            get_filename_component(TDS_VENDOR_RUNTIME_ABS "${TDS_VENDOR_RUNTIME}" ABSOLUTE)
        else()
            message(WARNING "Windows live build is enabled, but TDS_VENDOR_RUNTIME is not set. The program will link with the vendor .lib, but local execution still needs the matching .dll beside the .exe or on PATH.")
        endif()
        message(STATUS "Windows build will enable live TDS calls using ${TDS_VENDOR_LIBRARY_ABS}")
    else()
        message(STATUS "Windows build is stub-only. Copy supplier win32/*.lib and *.dll into ./tds/win32 or pass TDS_VENDOR_LIBRARY/TDS_VENDOR_RUNTIME to enable local live debugging.")
    endif()
else()
    message(FATAL_ERROR "Unsupported platform. This project only supports Windows local development and Linux/RHEL8 builds.")
endif()

if(TDS_REPORTER_HAS_VENDOR_API AND NOT EXISTS "${TDS_VENDOR_INCLUDE_DIR}/tds_api.h")
    message(FATAL_ERROR "TDS_VENDOR_INCLUDE_DIR must contain tds_api.h: ${TDS_VENDOR_INCLUDE_DIR}")
endif()

if(TDS_REPORTER_HAS_VENDOR_API)
    target_compile_definitions(tds_reporter_lib PRIVATE TDS_REPORTER_HAS_VENDOR_API=1)
endif()

add_executable(tds_reporter src/main.cpp)
target_link_libraries(tds_reporter PRIVATE tds_reporter_lib)

enable_testing()

add_executable(tds_reporter_tests tests/tds_reporter_tests.cpp)
target_link_libraries(tds_reporter_tests PRIVATE tds_reporter_lib)

if(TDS_VENDOR_LIBRARY_DIR)
    set_target_properties(
        tds_reporter
        PROPERTIES
            BUILD_RPATH "${TDS_VENDOR_LIBRARY_DIR}"
            INSTALL_RPATH "$ORIGIN/../${CMAKE_INSTALL_LIBDIR}"
    )
endif()

if(UNIX AND TDS_VENDOR_LIBRARY_DIR)
    file(
        GLOB TDS_VENDOR_INSTALL_FILES
        "${TDS_VENDOR_LIBRARY_DIR}/*.so"
        "${TDS_VENDOR_LIBRARY_DIR}/*.so.*"
        "${TDS_VENDOR_LIBRARY_DIR}/*.dat"
    )
    list(REMOVE_DUPLICATES TDS_VENDOR_INSTALL_FILES)
elseif(WIN32 AND TDS_VENDOR_LIBRARY_DIR)
    file(
        GLOB TDS_VENDOR_INSTALL_FILES
        "${TDS_VENDOR_LIBRARY_DIR}/*.dll"
        "${TDS_VENDOR_LIBRARY_DIR}/*.dat"
    )
    list(REMOVE_DUPLICATES TDS_VENDOR_INSTALL_FILES)
endif()

if(WIN32 AND TDS_VENDOR_INSTALL_FILES)
    foreach(TDS_VENDOR_FILE IN LISTS TDS_VENDOR_INSTALL_FILES)
        add_custom_command(
            TARGET tds_reporter
            POST_BUILD
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                "${TDS_VENDOR_FILE}"
                "$<TARGET_FILE_DIR:tds_reporter>"
        )

        add_custom_command(
            TARGET tds_reporter_tests
            POST_BUILD
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                "${TDS_VENDOR_FILE}"
                "$<TARGET_FILE_DIR:tds_reporter_tests>"
        )
    endforeach()
endif()

install(
    TARGETS tds_reporter
    RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
)

if(UNIX AND TDS_VENDOR_INSTALL_FILES)
    install(
        FILES ${TDS_VENDOR_INSTALL_FILES}
        DESTINATION "${CMAKE_INSTALL_LIBDIR}"
    )
endif()

if(WIN32 AND TDS_VENDOR_INSTALL_FILES)
    install(
        FILES ${TDS_VENDOR_INSTALL_FILES}
        DESTINATION "${CMAKE_INSTALL_BINDIR}"
    )
endif()

install(
    FILES
        "${CMAKE_CURRENT_SOURCE_DIR}/config/dev.properties"
        "${CMAKE_CURRENT_SOURCE_DIR}/config/qa.properties"
        "${CMAKE_CURRENT_SOURCE_DIR}/config/tds_reporter.properties.template"
        "${CMAKE_CURRENT_SOURCE_DIR}/config/prod.properties.template"
    DESTINATION config
)

install(
    PROGRAMS
        "${CMAKE_CURRENT_SOURCE_DIR}/scripts/render-config.sh"
        "${CMAKE_CURRENT_SOURCE_DIR}/scripts/render-config.ps1"
    DESTINATION scripts
)

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/config/tds_reporter.properties")
    install(
        FILES "${CMAKE_CURRENT_SOURCE_DIR}/config/tds_reporter.properties"
        DESTINATION config
    )
endif()

install(CODE "file(MAKE_DIRECTORY \"\${CMAKE_INSTALL_PREFIX}/logs\")")

install(
    FILES "${CMAKE_CURRENT_SOURCE_DIR}/README.md"
    DESTINATION .
)

add_custom_target(
    tds_reporter_stage
    COMMAND "${CMAKE_COMMAND}" --install "${CMAKE_BINARY_DIR}" --prefix "${TDS_REPORTER_STAGE_DIR}" --config "$<CONFIG>"
    DEPENDS tds_reporter
    COMMENT "Stage tds_reporter under ${TDS_REPORTER_STAGE_DIR}"
)

add_test(
    NAME tds_reporter_tests
    COMMAND tds_reporter_tests
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
)
