cmake_minimum_required(VERSION 3.20)

function(_tds_log message_text)
    message(STATUS "[tds-sdk] ${message_text}")
endfunction()

function(_tds_fail message_text)
    message(FATAL_ERROR "[tds-sdk] ${message_text}")
endfunction()

function(_tds_curl_config_quote out value)
    string(REPLACE "\\" "\\\\" _escaped "${value}")
    string(REPLACE "\"" "\\\"" _escaped "${_escaped}")
    string(REPLACE "\r" "\\r" _escaped "${_escaped}")
    string(REPLACE "\n" "\\n" _escaped "${_escaped}")
    set(${out} "\"${_escaped}\"" PARENT_SCOPE)
endfunction()

function(_tds_curl_config_line out name value)
    if("${value}" STREQUAL "")
        set(${out} "${name}" PARENT_SCOPE)
    else()
        _tds_curl_config_quote(_quoted "${value}")
        set(${out} "${name} = ${_quoted}" PARENT_SCOPE)
    endif()
endfunction()

function(_tds_assign_property key value)
    if(key STREQUAL "tds.sdk.url" OR key STREQUAL "TDS_SDK_URL")
        set_property(GLOBAL PROPERTY TDS_PROP_URL "${value}")
    elseif(key STREQUAL "tds.sdk.auth" OR key STREQUAL "TDS_SDK_AUTH")
        set_property(GLOBAL PROPERTY TDS_PROP_AUTH "${value}")
    elseif(key STREQUAL "tds.sdk.authHeader" OR key STREQUAL "TDS_SDK_AUTH_HEADER")
        set_property(GLOBAL PROPERTY TDS_PROP_AUTH_HEADER "${value}")
    elseif(key STREQUAL "tds.sdk.token" OR key STREQUAL "TDS_SDK_TOKEN")
        set_property(GLOBAL PROPERTY TDS_PROP_TOKEN "${value}")
    elseif(key STREQUAL "tds.sdk.tokenHeaderName" OR key STREQUAL "TDS_SDK_TOKEN_HEADER_NAME")
        set_property(GLOBAL PROPERTY TDS_PROP_TOKEN_HEADER_NAME "${value}")
    elseif(key STREQUAL "tds.sdk.tokenPrefix" OR key STREQUAL "TDS_SDK_TOKEN_PREFIX")
        set_property(GLOBAL PROPERTY TDS_PROP_TOKEN_PREFIX "${value}")
    elseif(key STREQUAL "tds.sdk.certFile" OR key STREQUAL "TDS_SDK_CERT_FILE")
        set_property(GLOBAL PROPERTY TDS_PROP_CERT_FILE "${value}")
    elseif(key STREQUAL "tds.sdk.keyFile" OR key STREQUAL "TDS_SDK_KEY_FILE")
        set_property(GLOBAL PROPERTY TDS_PROP_KEY_FILE "${value}")
    elseif(key STREQUAL "tds.sdk.curlBin" OR key STREQUAL "TDS_SDK_CURL_BIN")
        set_property(GLOBAL PROPERTY TDS_PROP_CURL_BIN "${value}")
    endif()
endfunction()

function(_tds_load_properties properties_file)
    if(NOT EXISTS "${properties_file}")
        return()
    endif()

    _tds_log("reading properties from ${properties_file}")
    file(STRINGS "${properties_file}" _property_lines ENCODING UTF-8)
    foreach(_raw_line IN LISTS _property_lines)
        string(STRIP "${_raw_line}" _line)
        if(_line STREQUAL "" OR _line MATCHES "^[#;]")
            continue()
        endif()

        string(FIND "${_line}" "=" _equals_index)
        if(_equals_index LESS 1)
            continue()
        endif()

        string(SUBSTRING "${_line}" 0 ${_equals_index} _key)
        math(EXPR _value_index "${_equals_index} + 1")
        string(SUBSTRING "${_line}" ${_value_index} -1 _value)
        string(STRIP "${_key}" _key)
        string(STRIP "${_value}" _value)
        _tds_assign_property("${_key}" "${_value}")
    endforeach()
endfunction()

function(_tds_resolve out cmake_name prop_var default_value)
    get_property(_property_value GLOBAL PROPERTY "${prop_var}")

    if(DEFINED ${cmake_name} AND NOT "${${cmake_name}}" STREQUAL "")
        set(${out} "${${cmake_name}}" PARENT_SCOPE)
    elseif(DEFINED ENV{${cmake_name}} AND NOT "$ENV{${cmake_name}}" STREQUAL "")
        set(${out} "$ENV{${cmake_name}}" PARENT_SCOPE)
    elseif(NOT "${_property_value}" STREQUAL "")
        set(${out} "${_property_value}" PARENT_SCOPE)
    else()
        set(${out} "${default_value}" PARENT_SCOPE)
    endif()
endfunction()

function(_tds_full_layout_ready out root_dir)
    set(_ready TRUE)

    if(NOT EXISTS "${root_dir}/include/tds_api.h")
        set(_ready FALSE)
    endif()
    if(NOT EXISTS "${root_dir}/linux_x86_64/libtds_api.so")
        set(_ready FALSE)
    endif()
    if(NOT EXISTS "${root_dir}/linux_x86_64/cpack.dat")
        set(_ready FALSE)
    endif()
    if(NOT EXISTS "${root_dir}/win32/tds_api.lib")
        set(_ready FALSE)
    endif()
    if(NOT EXISTS "${root_dir}/win32/cpack.dat")
        set(_ready FALSE)
    endif()

    file(GLOB _win_dll_files
        "${root_dir}/win32/*.dll"
        "${root_dir}/win32/*.DLL"
    )
    if(NOT _win_dll_files)
        set(_ready FALSE)
    endif()

    set(${out} "${_ready}" PARENT_SCOPE)
endfunction()

function(_tds_require_file path description)
    if(NOT EXISTS "${path}")
        _tds_fail("${description} not found: ${path}")
    endif()
endfunction()

function(_tds_default_properties_file out project_dir)
    set(_project_properties "${project_dir}/tds.properties")
    if(EXISTS "${_project_properties}")
        set(${out} "${_project_properties}" PARENT_SCOPE)
        return()
    endif()

    if(DEFINED ENV{USERPROFILE} AND NOT "$ENV{USERPROFILE}" STREQUAL "")
        set(_home "$ENV{USERPROFILE}")
    elseif(DEFINED ENV{HOME} AND NOT "$ENV{HOME}" STREQUAL "")
        set(_home "$ENV{HOME}")
    else()
        set(_home "")
    endif()

    if(_home)
        set(${out} "${_home}/.tds/tds.properties" PARENT_SCOPE)
    else()
        set(${out} "" PARENT_SCOPE)
    endif()
endfunction()

get_filename_component(_script_dir "${CMAKE_CURRENT_LIST_FILE}" DIRECTORY)
get_filename_component(_default_project_dir "${_script_dir}/.." ABSOLUTE)

if(NOT DEFINED TDS_SDK_PROJECT_DIR OR "${TDS_SDK_PROJECT_DIR}" STREQUAL "")
    set(TDS_SDK_PROJECT_DIR "${_default_project_dir}")
endif()
get_filename_component(_project_dir "${TDS_SDK_PROJECT_DIR}" ABSOLUTE)

if(NOT DEFINED TDS_SDK_DEST_DIR OR "${TDS_SDK_DEST_DIR}" STREQUAL "")
    set(TDS_SDK_DEST_DIR "${_project_dir}/tds")
endif()
get_filename_component(_dest_dir "${TDS_SDK_DEST_DIR}" ABSOLUTE)

if(NOT DEFINED TDS_SDK_FORCE OR "${TDS_SDK_FORCE}" STREQUAL "")
    set(TDS_SDK_FORCE OFF)
endif()

_tds_full_layout_ready(_layout_ready "${_dest_dir}")
if(_layout_ready AND NOT TDS_SDK_FORCE)
    _tds_log("existing SDK layout is complete under ${_dest_dir}; skipping download")
    return()
endif()

if(DEFINED TDS_SDK_PROPERTIES_FILE AND NOT "${TDS_SDK_PROPERTIES_FILE}" STREQUAL "")
    set(_properties_file "${TDS_SDK_PROPERTIES_FILE}")
elseif(DEFINED ENV{TDS_SDK_PROPERTIES_FILE} AND NOT "$ENV{TDS_SDK_PROPERTIES_FILE}" STREQUAL "")
    set(_properties_file "$ENV{TDS_SDK_PROPERTIES_FILE}")
else()
    _tds_default_properties_file(_properties_file "${_project_dir}")
endif()

if(_properties_file AND EXISTS "${_properties_file}")
    _tds_load_properties("${_properties_file}")
endif()

_tds_resolve(_url TDS_SDK_URL TDS_PROP_URL "")
if(NOT _url)
    if(TDS_SDK_ALLOW_MISSING_CONFIG)
        _tds_log("TDS_SDK_URL is not configured; leaving ${_dest_dir} unchanged")
        return()
    endif()

    _tds_fail("TDS_SDK_URL is required. Set it in tds.properties, TDS_SDK_URL, or -DTDS_SDK_URL.")
endif()

if(NOT DEFINED TDS_SDK_PLATFORM OR "${TDS_SDK_PLATFORM}" STREQUAL "")
    if(WIN32)
        set(TDS_SDK_PLATFORM "windows")
    elseif(UNIX)
        set(TDS_SDK_PLATFORM "linux")
    else()
        set(TDS_SDK_PLATFORM "unknown")
    endif()
endif()
string(TOLOWER "${TDS_SDK_PLATFORM}" _platform)

if(NOT DEFINED TDS_SDK_CONTEXT OR "${TDS_SDK_CONTEXT}" STREQUAL "")
    set(TDS_SDK_CONTEXT "local")
endif()
string(TOLOWER "${TDS_SDK_CONTEXT}" _context)

_tds_resolve(_auth TDS_SDK_AUTH TDS_PROP_AUTH "")
_tds_resolve(_auth_header TDS_SDK_AUTH_HEADER TDS_PROP_AUTH_HEADER "")
_tds_resolve(_token TDS_SDK_TOKEN TDS_PROP_TOKEN "")
_tds_resolve(_token_header_name TDS_SDK_TOKEN_HEADER_NAME TDS_PROP_TOKEN_HEADER_NAME "Authorization")
_tds_resolve(_token_prefix TDS_SDK_TOKEN_PREFIX TDS_PROP_TOKEN_PREFIX "Bearer")
_tds_resolve(_cert_file TDS_SDK_CERT_FILE TDS_PROP_CERT_FILE "")
_tds_resolve(_key_file TDS_SDK_KEY_FILE TDS_PROP_KEY_FILE "")
_tds_resolve(_curl_bin TDS_SDK_CURL_BIN TDS_PROP_CURL_BIN "curl")

if(NOT DEFINED TDS_SDK_DOWNLOAD_DIR OR "${TDS_SDK_DOWNLOAD_DIR}" STREQUAL "")
    set(TDS_SDK_DOWNLOAD_DIR "${_project_dir}/.tds-sdk/download")
endif()
if(NOT DEFINED TDS_SDK_EXTRACT_DIR OR "${TDS_SDK_EXTRACT_DIR}" STREQUAL "")
    set(TDS_SDK_EXTRACT_DIR "${_project_dir}/.tds-sdk/extract")
endif()
get_filename_component(_download_dir "${TDS_SDK_DOWNLOAD_DIR}" ABSOLUTE)
get_filename_component(_extract_dir "${TDS_SDK_EXTRACT_DIR}" ABSOLUTE)
set(_archive_path "${_download_dir}/tds_sdk.zip")
set(_curl_config_path "${_download_dir}/artifactory-curl.conf")

if(NOT _auth)
    if(_auth_header OR _token)
        set(_auth "token")
    elseif(_cert_file OR _key_file)
        set(_auth "cert")
    elseif(_context STREQUAL "jenkins")
        set(_auth "cert")
    else()
        set(_auth "token")
    endif()
endif()
string(TOLOWER "${_auth}" _auth)

if(_context STREQUAL "jenkins" AND NOT _auth STREQUAL "cert")
    _tds_fail("Jenkins TDS SDK downloads must use certificate/key authentication.")
endif()

if(_platform STREQUAL "windows" AND NOT _auth STREQUAL "token")
    _tds_fail("Windows local TDS SDK downloads must use token authentication.")
endif()

set(_curl_config_lines "")
_tds_curl_config_line(_line "fail" "")
list(APPEND _curl_config_lines "${_line}")
_tds_curl_config_line(_line "silent" "")
list(APPEND _curl_config_lines "${_line}")
_tds_curl_config_line(_line "show-error" "")
list(APPEND _curl_config_lines "${_line}")
_tds_curl_config_line(_line "location" "")
list(APPEND _curl_config_lines "${_line}")
_tds_curl_config_line(_line "output" "${_archive_path}")
list(APPEND _curl_config_lines "${_line}")
_tds_curl_config_line(_line "url" "${_url}")
list(APPEND _curl_config_lines "${_line}")

if(_auth STREQUAL "token")
    if(_auth_header)
        set(_download_header "${_auth_header}")
    elseif(_token)
        if(_token_prefix)
            set(_download_header "${_token_header_name}: ${_token_prefix} ${_token}")
        else()
            set(_download_header "${_token_header_name}: ${_token}")
        endif()
    else()
        _tds_fail("token authentication requires tds.sdk.authHeader or tds.sdk.token.")
    endif()

    _tds_curl_config_line(_line "header" "${_download_header}")
    list(APPEND _curl_config_lines "${_line}")
elseif(_auth STREQUAL "cert")
    if(NOT _cert_file)
        _tds_fail("certificate authentication requires tds.sdk.certFile or TDS_SDK_CERT_FILE.")
    endif()
    if(NOT _key_file)
        _tds_fail("certificate authentication requires tds.sdk.keyFile or TDS_SDK_KEY_FILE.")
    endif()
    _tds_require_file("${_cert_file}" "Artifactory certificate")
    _tds_require_file("${_key_file}" "Artifactory key")

    _tds_curl_config_line(_line "cert-type" "PEM")
    list(APPEND _curl_config_lines "${_line}")
    _tds_curl_config_line(_line "cert" "${_cert_file}")
    list(APPEND _curl_config_lines "${_line}")
    _tds_curl_config_line(_line "key" "${_key_file}")
    list(APPEND _curl_config_lines "${_line}")
else()
    _tds_fail("unsupported TDS_SDK_AUTH '${_auth}'. Use token or cert.")
endif()

file(REMOVE_RECURSE "${_download_dir}" "${_extract_dir}")
file(MAKE_DIRECTORY "${_download_dir}" "${_extract_dir}")

list(JOIN _curl_config_lines "\n" _curl_config_text)
file(WRITE "${_curl_config_path}" "${_curl_config_text}\n")
file(CHMOD "${_curl_config_path}" PERMISSIONS OWNER_READ OWNER_WRITE)

_tds_log("downloading ${_url}")
execute_process(
    COMMAND "${_curl_bin}" --config "${_curl_config_path}"
    RESULT_VARIABLE _curl_result
    OUTPUT_VARIABLE _curl_stdout
    ERROR_VARIABLE _curl_stderr
)
file(REMOVE "${_curl_config_path}")

if(NOT _curl_result EQUAL 0)
    _tds_fail("Artifactory download failed with exit code ${_curl_result}: ${_curl_stderr}${_curl_stdout}")
endif()

if(NOT EXISTS "${_archive_path}")
    _tds_fail("curl completed but did not create ${_archive_path}")
endif()

_tds_log("extracting ${_archive_path}")
execute_process(
    COMMAND "${CMAKE_COMMAND}" -E tar xzf "${_archive_path}"
    WORKING_DIRECTORY "${_extract_dir}"
    RESULT_VARIABLE _extract_gzip_result
    OUTPUT_VARIABLE _extract_gzip_stdout
    ERROR_VARIABLE _extract_gzip_stderr
)

if(NOT _extract_gzip_result EQUAL 0)
    execute_process(
        COMMAND "${CMAKE_COMMAND}" -E tar xf "${_archive_path}"
        WORKING_DIRECTORY "${_extract_dir}"
        RESULT_VARIABLE _extract_result
        OUTPUT_VARIABLE _extract_stdout
        ERROR_VARIABLE _extract_stderr
    )
    if(NOT _extract_result EQUAL 0)
        _tds_fail("unsupported or invalid TDS SDK archive: ${_extract_stderr}${_extract_stdout}")
    endif()
endif()

if(EXISTS "${_extract_dir}/tds")
    set(_source_dir "${_extract_dir}/tds")
else()
    set(_source_dir "${_extract_dir}")
endif()

_tds_require_file("${_source_dir}/include/tds_api.h" "TDS header")
_tds_require_file("${_source_dir}/linux_x86_64/libtds_api.so" "Linux TDS shared library")
_tds_require_file("${_source_dir}/linux_x86_64/cpack.dat" "Linux TDS cpack.dat")
_tds_require_file("${_source_dir}/win32/tds_api.lib" "Windows TDS import library")
_tds_require_file("${_source_dir}/win32/cpack.dat" "Windows TDS cpack.dat")

file(GLOB _source_win_dlls
    "${_source_dir}/win32/*.dll"
    "${_source_dir}/win32/*.DLL"
)
if(NOT _source_win_dlls)
    _tds_fail("Windows TDS runtime DLL not found under ${_source_dir}/win32")
endif()

file(REMOVE_RECURSE "${_dest_dir}")
file(MAKE_DIRECTORY "${_dest_dir}")
file(GLOB _source_children LIST_DIRECTORIES TRUE "${_source_dir}/*")
foreach(_source_child IN LISTS _source_children)
    file(COPY "${_source_child}" DESTINATION "${_dest_dir}")
endforeach()

_tds_full_layout_ready(_copied_layout_ready "${_dest_dir}")
if(NOT _copied_layout_ready)
    _tds_fail("copied SDK layout is incomplete under ${_dest_dir}")
endif()

_tds_log("prepared SDK under ${_dest_dir}")
