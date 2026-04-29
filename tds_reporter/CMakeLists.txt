cmake_minimum_required(VERSION 3.20)
project(report LANGUAGES CXX)

include(GNUInstallDirs)
include(CheckCXXSourceCompiles)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(TDS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/tds")
set(TDS_INCLUDE_DIR "${TDS_ROOT}/include")
set(TDS_WIN32_DIR "${TDS_ROOT}/win32")
set(TDS_LINUX_DIR "${TDS_ROOT}/linux_x86_64")
set(TDS_WIN32_LIBRARY "${TDS_WIN32_DIR}/tds_api.lib")
set(TDS_LINUX_LIBRARY "${TDS_LINUX_DIR}/libtds_api.so")
set(TDS_LINUX_CPACK "${TDS_LINUX_DIR}/cpack.dat")
option(TDS_SDK_AUTO_PREPARE "Download and extract the TDS SDK from Artifactory when tds/ is incomplete" ON)
option(TDS_SDK_FORCE "Force re-download of the TDS SDK even when tds/ already looks complete" OFF)
set(TDS_SDK_PROPERTIES_FILE "" CACHE FILEPATH "Local tds-sdk.properties file with Artifactory download settings")
set(TDS_SDK_URL "" CACHE STRING "Artifactory URL for tds_sdk.zip")
set(TDS_SDK_AUTH "" CACHE STRING "Artifactory auth mode for TDS SDK downloads: token or cert")
set(TDS_SDK_CERT_FILE "" CACHE FILEPATH "Artifactory client certificate path for certificate auth")
set(TDS_SDK_KEY_FILE "" CACHE FILEPATH "Artifactory client key path for certificate auth")
set(TDS_SDK_CERT_TYPE "PEM" CACHE STRING "Artifactory client certificate type passed to curl")
set(TDS_SDK_CURL_BIN "curl" CACHE STRING "curl executable used to download tds_sdk.zip")
set(REPORT_STAGE_DIR "${CMAKE_BINARY_DIR}/client_funding_risk_report" CACHE PATH "Directory used by the report_stage target")
set(REPORT_PACKAGE_PATH "${CMAKE_BINARY_DIR}/client_funding_risk_report.tar.gz" CACHE FILEPATH "Output path for the staged runtime tarball")
get_filename_component(REPORT_INSTALL_DIR_NAME "${REPORT_STAGE_DIR}" NAME)

function(report_tds_sdk_full_layout_ready OUT_VAR)
    set(_ready TRUE)

    if(NOT EXISTS "${TDS_INCLUDE_DIR}/tds_api.h")
        set(_ready FALSE)
    endif()
    if(NOT EXISTS "${TDS_LINUX_LIBRARY}")
        set(_ready FALSE)
    endif()
    if(NOT EXISTS "${TDS_LINUX_CPACK}")
        set(_ready FALSE)
    endif()
    if(NOT EXISTS "${TDS_WIN32_LIBRARY}")
        set(_ready FALSE)
    endif()
    if(NOT EXISTS "${TDS_WIN32_DIR}/cpack.dat")
        set(_ready FALSE)
    endif()

    file(GLOB _tds_win32_dlls "${TDS_WIN32_DIR}/*.dll" "${TDS_WIN32_DIR}/*.DLL")
    if(NOT _tds_win32_dlls)
        set(_ready FALSE)
    endif()

    set(${OUT_VAR} "${_ready}" PARENT_SCOPE)
endfunction()

if(WIN32)
    set(TDS_SDK_PLATFORM "windows")
elseif(UNIX)
    set(TDS_SDK_PLATFORM "linux")
else()
    set(TDS_SDK_PLATFORM "unknown")
endif()

if(TDS_SDK_AUTO_PREPARE)
    report_tds_sdk_full_layout_ready(REPORT_TDS_SDK_READY)
    if(NOT REPORT_TDS_SDK_READY OR TDS_SDK_FORCE)
        set(REPORT_TDS_SDK_ALLOW_MISSING_CONFIG ON)
        if(TDS_SDK_FORCE)
            set(REPORT_TDS_SDK_ALLOW_MISSING_CONFIG OFF)
        endif()

        set(REPORT_TDS_SDK_PREPARE_ARGS
            "-DTDS_SDK_PROJECT_DIR=${CMAKE_CURRENT_SOURCE_DIR}"
            "-DTDS_SDK_DEST_DIR=${TDS_ROOT}"
            "-DTDS_SDK_DOWNLOAD_DIR=${CMAKE_BINARY_DIR}/tds_sdk_download"
            "-DTDS_SDK_EXTRACT_DIR=${CMAKE_BINARY_DIR}/tds_sdk_extract"
            "-DTDS_SDK_PLATFORM=${TDS_SDK_PLATFORM}"
            "-DTDS_SDK_CONTEXT=local"
            "-DTDS_SDK_ALLOW_MISSING_CONFIG=${REPORT_TDS_SDK_ALLOW_MISSING_CONFIG}"
            "-DTDS_SDK_FORCE=${TDS_SDK_FORCE}"
            "-DTDS_SDK_CERT_TYPE=${TDS_SDK_CERT_TYPE}"
            "-DTDS_SDK_CURL_BIN=${TDS_SDK_CURL_BIN}"
        )

        foreach(REPORT_TDS_SDK_OPTION IN ITEMS
            TDS_SDK_PROPERTIES_FILE
            TDS_SDK_URL
            TDS_SDK_AUTH
            TDS_SDK_CERT_FILE
            TDS_SDK_KEY_FILE
        )
            if(DEFINED ${REPORT_TDS_SDK_OPTION} AND NOT "${${REPORT_TDS_SDK_OPTION}}" STREQUAL "")
                list(APPEND REPORT_TDS_SDK_PREPARE_ARGS "-D${REPORT_TDS_SDK_OPTION}=${${REPORT_TDS_SDK_OPTION}}")
            endif()
        endforeach()

        execute_process(
            COMMAND "${CMAKE_COMMAND}" ${REPORT_TDS_SDK_PREPARE_ARGS}
                -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/PrepareTdsSdk.cmake"
            RESULT_VARIABLE REPORT_TDS_SDK_PREPARE_RESULT
        )

        if(NOT REPORT_TDS_SDK_PREPARE_RESULT EQUAL 0)
            message(FATAL_ERROR "Failed to prepare TDS SDK from Artifactory")
        endif()
    endif()
endif()

if(UNIX)
    find_package(CURL QUIET)
    if(NOT CURL_FOUND)
        message(
            FATAL_ERROR
            "Linux/RHEL8 builds require libcurl development files for Vault Kerberos HTTP access. "
            "Install the RHEL8 package `libcurl-devel` and rerun CMake. "
            "`curl --version` only verifies the curl CLI; CMake also needs curl/curl.h and libcurl.so. "
            "Suggested checks: `rpm -q libcurl-devel` and `ls /usr/include/curl/curl.h /usr/lib64/libcurl.so`."
        )
    endif()
endif()

add_library(
    report_lib
    src/app.cpp
    src/logging.cpp
    src/text_utils.cpp
    src/tds_client.cpp
)

target_include_directories(
    report_lib
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        ${TDS_INCLUDE_DIR}
)

if(CURL_FOUND)
    target_link_libraries(report_lib PUBLIC CURL::libcurl)
    target_compile_definitions(report_lib PUBLIC REPORT_HAS_LIBCURL=1)
    message(STATUS "Vault HTTP access will use C++ libcurl")
else()
    message(STATUS "Vault HTTP access is disabled in this Windows local build")
endif()

set(REPORT_FILESYSTEM_TEST_SOURCE
"#include <filesystem>
int main() {
    const std::filesystem::path path{\".\"};
    return std::filesystem::absolute(path).empty() ? 1 : 0;
}
")

check_cxx_source_compiles(
    "${REPORT_FILESYSTEM_TEST_SOURCE}"
    REPORT_FILESYSTEM_LINKS_WITHOUT_EXTRA_LIB
)

if(NOT REPORT_FILESYSTEM_LINKS_WITHOUT_EXTRA_LIB)
    set(REPORT_FILESYSTEM_REQUIRED_LIBRARIES "${CMAKE_REQUIRED_LIBRARIES}")
    list(APPEND CMAKE_REQUIRED_LIBRARIES stdc++fs)
    check_cxx_source_compiles(
        "${REPORT_FILESYSTEM_TEST_SOURCE}"
        REPORT_FILESYSTEM_LINKS_WITH_STDCXXFS
    )
    set(CMAKE_REQUIRED_LIBRARIES "${REPORT_FILESYSTEM_REQUIRED_LIBRARIES}")

    if(REPORT_FILESYSTEM_LINKS_WITH_STDCXXFS)
        message(STATUS "Linking stdc++fs for std::filesystem compatibility on this toolchain")
        target_link_libraries(report_lib PUBLIC stdc++fs)
    else()
        message(
            FATAL_ERROR
            "The current C++ toolchain cannot link std::filesystem symbols, even with stdc++fs. "
            "Install a newer libstdc++ or use a toolchain with complete C++17 filesystem support."
        )
    endif()
endif()

set(TDS_LIBRARY_DIR "")
set(TDS_INSTALL_FILES "")
set(TDS_BIN_INSTALL_FILES "")
set(TDS_INSTALL_DESTINATION "")
set(REPORT_COPY_TDS_TO_BINARY_DIR OFF)
set(REPORT_HAS_VENDOR_API OFF)

if(WIN32)
    target_compile_definitions(report_lib PRIVATE _WINDOWS)
    if(NOT CMAKE_SIZEOF_VOID_P EQUAL 4)
        message(
            FATAL_ERROR
            "Windows local builds require the Win32/x86 generator because the supplier TDS SDK "
            "only provides 32-bit dll/lib files. Use --preset windows-local-x86.")
    endif()

    set(TDS_INSTALL_DESTINATION "${CMAKE_INSTALL_BINDIR}")
    set(REPORT_COPY_TDS_TO_BINARY_DIR ON)
    file(GLOB TDS_INSTALL_FILES "${TDS_WIN32_DIR}/*.dll" "${TDS_WIN32_DIR}/*.dat")

    if(EXISTS "${TDS_WIN32_LIBRARY}")
        file(GLOB TDS_WIN32_DLL_FILES "${TDS_WIN32_DIR}/*.dll")
        if(NOT TDS_WIN32_DLL_FILES)
            message(FATAL_ERROR "Windows live build requires at least one .dll under ${TDS_WIN32_DIR}")
        endif()
        if(NOT EXISTS "${TDS_WIN32_DIR}/cpack.dat")
            message(FATAL_ERROR "Windows live build requires ${TDS_WIN32_DIR}/cpack.dat")
        endif()
        get_filename_component(TDS_LIBRARY_DIR "${TDS_WIN32_LIBRARY}" DIRECTORY)
        target_link_libraries(report_lib PRIVATE "${TDS_WIN32_LIBRARY}")
        set(REPORT_HAS_VENDOR_API ON)
        message(STATUS "Windows Win32 local build will enable live TDS calls from ${TDS_WIN32_DIR}")
    else()
        message(STATUS "Windows Win32 local build is stub-only. Configure tds-sdk.properties with token auth to download supplier dll/lib/cpack.dat into ${TDS_WIN32_DIR} for local DRTP debugging.")
    endif()
elseif(UNIX)
    set(TDS_INSTALL_DESTINATION "${CMAKE_INSTALL_LIBDIR}")
    if(NOT EXISTS "${TDS_LINUX_LIBRARY}")
        message(FATAL_ERROR "Linux build requires ${TDS_LINUX_LIBRARY}. Configure tds-sdk.properties or run cmake -P cmake/PrepareTdsSdk.cmake before configuring.")
    endif()
    if(NOT EXISTS "${TDS_LINUX_CPACK}")
        message(FATAL_ERROR "Linux build requires ${TDS_LINUX_CPACK}. Configure tds-sdk.properties or run cmake -P cmake/PrepareTdsSdk.cmake before configuring.")
    endif()

    get_filename_component(TDS_LIBRARY_DIR "${TDS_LINUX_LIBRARY}" DIRECTORY)
    set(TDS_INSTALL_FILES "${TDS_LINUX_LIBRARY}")
    set(TDS_BIN_INSTALL_FILES "${TDS_LINUX_CPACK}")
    target_link_libraries(report_lib PRIVATE "${TDS_LINUX_LIBRARY}")
    set(REPORT_HAS_VENDOR_API ON)
    message(STATUS "Linux build will enable live TDS calls from ${TDS_LINUX_DIR}")
else()
    message(FATAL_ERROR "Unsupported platform. This project only supports Windows local development and Linux/RHEL8 builds.")
endif()

if(REPORT_HAS_VENDOR_API AND NOT EXISTS "${TDS_INCLUDE_DIR}/tds_api.h")
    message(FATAL_ERROR "Live builds require ${TDS_INCLUDE_DIR}/tds_api.h")
endif()

if(REPORT_HAS_VENDOR_API)
    target_compile_definitions(report_lib PRIVATE REPORT_HAS_VENDOR_API=1)
endif()

add_executable(report src/main.cpp)
target_link_libraries(report PRIVATE report_lib)

enable_testing()

add_executable(report_tests tests/report_tests.cpp)
target_link_libraries(report_tests PRIVATE report_lib)

if(TDS_LIBRARY_DIR)
    set_target_properties(
        report
        PROPERTIES
            BUILD_RPATH "${TDS_LIBRARY_DIR}"
            INSTALL_RPATH "$ORIGIN/../${CMAKE_INSTALL_LIBDIR}"
    )
endif()

if(TDS_INSTALL_FILES)
    list(REMOVE_DUPLICATES TDS_INSTALL_FILES)
endif()

if(TDS_BIN_INSTALL_FILES)
    list(REMOVE_DUPLICATES TDS_BIN_INSTALL_FILES)
endif()

if(REPORT_COPY_TDS_TO_BINARY_DIR AND TDS_INSTALL_FILES)
    foreach(TDS_FILE IN LISTS TDS_INSTALL_FILES)
        add_custom_command(
            TARGET report
            POST_BUILD
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                "${TDS_FILE}"
                "$<TARGET_FILE_DIR:report>"
        )

        add_custom_command(
            TARGET report_tests
            POST_BUILD
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                "${TDS_FILE}"
                "$<TARGET_FILE_DIR:report_tests>"
        )
    endforeach()
endif()

install(
    TARGETS report
    RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
)

if(TDS_INSTALL_FILES)
    install(
        FILES ${TDS_INSTALL_FILES}
        DESTINATION "${TDS_INSTALL_DESTINATION}"
    )
endif()

if(TDS_BIN_INSTALL_FILES)
    install(
        FILES ${TDS_BIN_INSTALL_FILES}
        DESTINATION "${CMAKE_INSTALL_BINDIR}"
    )
endif()

install(
    FILES
        "${CMAKE_CURRENT_SOURCE_DIR}/config/dev.properties"
        "${CMAKE_CURRENT_SOURCE_DIR}/config/qa.properties"
        "${CMAKE_CURRENT_SOURCE_DIR}/config/prod.properties"
        "${CMAKE_CURRENT_SOURCE_DIR}/config/report.properties"
    DESTINATION config
)

install(
    FILES
        "${CMAKE_CURRENT_SOURCE_DIR}/templates/client_funding_risk_report.html"
    DESTINATION templates
)

install(CODE "file(MAKE_DIRECTORY \"\${CMAKE_INSTALL_PREFIX}/logs\")")

add_custom_target(
    report_stage
    COMMAND "${CMAKE_COMMAND}" -E rm -rf "${REPORT_STAGE_DIR}"
    COMMAND "${CMAKE_COMMAND}" --install "${CMAKE_BINARY_DIR}" --prefix "${REPORT_STAGE_DIR}" --config "$<CONFIG>"
    DEPENDS report
    COMMENT "Stage report under ${REPORT_STAGE_DIR}"
)

add_custom_target(
    report_package
    COMMAND "${CMAKE_COMMAND}" -E tar czf "${REPORT_PACKAGE_PATH}" "${REPORT_INSTALL_DIR_NAME}"
    WORKING_DIRECTORY "${CMAKE_BINARY_DIR}"
    DEPENDS report_stage
    COMMENT "Package staged report runtime at ${REPORT_PACKAGE_PATH}"
)

add_test(
    NAME report_tests
    COMMAND report_tests
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
)
