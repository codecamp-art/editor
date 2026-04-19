cmake_minimum_required(VERSION 3.20)
project(tds_reporter LANGUAGES CXX)

include(GNUInstallDirs)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(TDS_VENDOR_LIBRARY "" CACHE FILEPATH "Path to vendor link library: .so on Linux, .lib on Windows")
set(TDS_VENDOR_RUNTIME "" CACHE FILEPATH "Path to vendor runtime library: .so on Linux, .dll on Windows")
set(TDS_REPORTER_STAGE_DIR "${CMAKE_BINARY_DIR}/stage" CACHE PATH "Directory used by the tds_reporter_stage target")

add_library(
    tds_reporter_lib
    src/app.cpp
    src/tds_client.cpp
)

target_include_directories(
    tds_reporter_lib
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        ${CMAKE_CURRENT_SOURCE_DIR}/../tds/include
)

set(TDS_VENDOR_LIBRARY_DIR "")
set(TDS_VENDOR_LIBRARY_ABS "")
set(TDS_VENDOR_RUNTIME_ABS "")
set(TDS_REPORTER_HAS_VENDOR_API OFF)

if(WIN32)
    if(NOT TDS_VENDOR_LIBRARY)
        file(GLOB TDS_WIN32_LIB_CANDIDATES "${CMAKE_CURRENT_SOURCE_DIR}/../tds/win32/*.lib")
        list(LENGTH TDS_WIN32_LIB_CANDIDATES TDS_WIN32_LIB_COUNT)
        if(TDS_WIN32_LIB_COUNT EQUAL 1)
            set(TDS_VENDOR_LIBRARY "${TDS_WIN32_LIB_CANDIDATES}" CACHE FILEPATH "Auto-detected vendor import library" FORCE)
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
        else()
            file(GLOB TDS_WIN32_DLL_CANDIDATES "${CMAKE_CURRENT_SOURCE_DIR}/../tds/win32/*.dll")
            list(LENGTH TDS_WIN32_DLL_CANDIDATES TDS_WIN32_DLL_COUNT)
            if(TDS_WIN32_DLL_COUNT EQUAL 1)
                set(TDS_VENDOR_RUNTIME "${TDS_WIN32_DLL_CANDIDATES}" CACHE FILEPATH "Auto-detected vendor runtime library" FORCE)
            endif()
        endif()
    endif()
endif()

if(UNIX)
    if(NOT TDS_VENDOR_LIBRARY)
        message(FATAL_ERROR "Linux build requires -DTDS_VENDOR_LIBRARY=/path/to/tds_api.so")
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
        message(STATUS "Windows build is stub-only. Copy supplier win32/*.lib and *.dll into ../tds/win32 or pass TDS_VENDOR_LIBRARY/TDS_VENDOR_RUNTIME to enable local live debugging.")
    endif()
else()
    message(FATAL_ERROR "Unsupported platform. This project only supports Windows local development and Linux/RHEL8 builds.")
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

if(WIN32 AND TDS_VENDOR_RUNTIME_ABS)
    add_custom_command(
        TARGET tds_reporter
        POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different
            "${TDS_VENDOR_RUNTIME_ABS}"
            "$<TARGET_FILE_DIR:tds_reporter>"
    )

    add_custom_command(
        TARGET tds_reporter_tests
        POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different
            "${TDS_VENDOR_RUNTIME_ABS}"
            "$<TARGET_FILE_DIR:tds_reporter_tests>"
    )
endif()

install(
    TARGETS tds_reporter
    RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
)

if(UNIX)
    install(
        FILES "${TDS_VENDOR_RUNTIME_ABS}"
        DESTINATION "${CMAKE_INSTALL_LIBDIR}"
    )
endif()

if(WIN32 AND TDS_VENDOR_RUNTIME_ABS)
    install(
        FILES "${TDS_VENDOR_RUNTIME_ABS}"
        DESTINATION "${CMAKE_INSTALL_BINDIR}"
    )
endif()

install(
    FILES
        "${CMAKE_CURRENT_SOURCE_DIR}/config/dev.properties"
        "${CMAKE_CURRENT_SOURCE_DIR}/config/qa.properties"
        "${CMAKE_CURRENT_SOURCE_DIR}/config/prod.properties.template"
    DESTINATION config
)

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
