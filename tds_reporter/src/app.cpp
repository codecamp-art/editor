#include "app.h"
#include "logging.h"

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <tuple>
#include <unordered_map>
#include <utility>
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <unistd.h>
#endif
#ifdef REPORT_HAS_LIBCURL
#include <curl/curl.h>
#endif

namespace report {
namespace {

std::string Trim(const std::string& value)
{
    std::size_t begin = 0;
    while (begin < value.size() && std::isspace(static_cast<unsigned char>(value[begin])) != 0)
    {
        ++begin;
    }

    std::size_t end = value.size();
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1])) != 0)
    {
        --end;
    }

    return value.substr(begin, end - begin);
}

std::string ToLower(std::string value)
{
    std::transform(
        value.begin(),
        value.end(),
        value.begin(),
        [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
    return value;
}

bool IsAscii(const std::string& value)
{
    return std::all_of(
        value.begin(),
        value.end(),
        [](unsigned char ch) { return ch <= 0x7F; });
}

std::string ExpandEnvReference(const std::string& value)
{
    if (value.size() < 4 || value[0] != '$' || value[1] != '{' || value.back() != '}')
    {
        return value;
    }

    const std::string body = value.substr(2, value.size() - 3);
    const std::size_t delimiter = body.find(':');
    const std::string key = delimiter == std::string::npos ? body : body.substr(0, delimiter);
    const std::string fallback = delimiter == std::string::npos ? "" : body.substr(delimiter + 1);
    const char* env_value = std::getenv(key.c_str());

    if (env_value == nullptr || env_value[0] == '\0')
    {
        return fallback;
    }

    return env_value;
}

std::unordered_map<std::string, std::string> ReadPropertiesFile(const std::string& path)
{
    std::ifstream input(path);
    if (!input.is_open())
    {
        throw std::runtime_error("failed to open config file: " + path);
    }

    std::unordered_map<std::string, std::string> properties;
    std::string line;

    while (std::getline(input, line))
    {
        const std::string trimmed = Trim(line);
        if (trimmed.empty() || trimmed[0] == '#')
        {
            continue;
        }

        const std::size_t pos = trimmed.find('=');
        if (pos == std::string::npos)
        {
            throw std::runtime_error("invalid config line: " + trimmed);
        }

        const std::string key = Trim(trimmed.substr(0, pos));
        const std::string value = ExpandEnvReference(Trim(trimmed.substr(pos + 1)));
        properties[key] = value;
    }

    return properties;
}

std::unordered_map<std::string, std::string> ReadProperties(const std::string& path)
{
    const std::filesystem::path resolved_path = std::filesystem::absolute(path);
    const std::filesystem::path config_dir = resolved_path.parent_path();
    const std::filesystem::path shared_path = config_dir / "report.properties";

    std::unordered_map<std::string, std::string> properties;
    if (resolved_path.filename() != "report.properties" && std::filesystem::exists(shared_path))
    {
        properties = ReadPropertiesFile(shared_path.string());
    }

    const auto overlay_properties = ReadPropertiesFile(resolved_path.string());
    for (const auto& [key, value] : overlay_properties)
    {
        properties[key] = value;
    }

    return properties;
}

std::string GetValue(
    const std::unordered_map<std::string, std::string>& properties,
    const std::string& key,
    const std::string& fallback = "")
{
    const auto it = properties.find(key);
    if (it == properties.end())
    {
        return fallback;
    }
    return it->second;
}

std::string GetRequiredValue(
    const std::unordered_map<std::string, std::string>& properties,
    const std::string& key)
{
    const std::string value = GetValue(properties, key);
    if (value.empty())
    {
        throw std::runtime_error("missing required config key: " + key);
    }
    return value;
}

int ParseIntValue(const std::string& raw, const std::string& field_name)
{
    try
    {
        return std::stoi(raw);
    }
    catch (const std::exception&)
    {
        throw std::runtime_error("invalid integer for " + field_name + ": " + raw);
    }
}

bool ParseBoolValue(const std::string& raw, const std::string& field_name)
{
    const std::string normalized = ToLower(Trim(raw));
    if (normalized == "true" || normalized == "1" || normalized == "yes" || normalized == "y")
    {
        return true;
    }
    if (normalized == "false" || normalized == "0" || normalized == "no" || normalized == "n")
    {
        return false;
    }
    throw std::runtime_error("invalid boolean for " + field_name + ": " + raw);
}

std::string ParseLogLevelValue(const std::string& raw)
{
    const std::string normalized = ToLower(Trim(raw));
    if (normalized == "debug" || normalized == "info" || normalized == "warn" || normalized == "error")
    {
        return normalized;
    }
    throw std::runtime_error("invalid log.level: " + raw);
}

std::vector<std::string> SplitDelimitedList(const std::string& raw)
{
    std::vector<std::string> values;
    std::string current;

    for (const char ch : raw)
    {
        if (ch == ',' || ch == ';')
        {
            const std::string trimmed = Trim(current);
            if (!trimmed.empty())
            {
                values.push_back(trimmed);
            }
            current.clear();
        }
        else
        {
            current += ch;
        }
    }

    const std::string trimmed = Trim(current);
    if (!trimmed.empty())
    {
        values.push_back(trimmed);
    }

    return values;
}

TdsEndpoint ParseDrtpEndpoint(const std::string& raw, const std::string& field_name)
{
    const std::string value = Trim(raw);
    if (value.empty())
    {
        throw std::runtime_error("empty DRTP endpoint in " + field_name);
    }

    std::string host;
    std::string port_text;
    if (value.front() == '[')
    {
        const std::size_t close = value.find(']');
        if (close != std::string::npos)
        {
            host = value.substr(1, close - 1);
            if (close + 1 < value.size())
            {
                if (value[close + 1] != ':')
                {
                    throw std::runtime_error("invalid DRTP endpoint in " + field_name + ": " + value);
                }
                port_text = value.substr(close + 2);
            }
        }
    }
    else
    {
        const std::size_t delimiter = value.rfind(':');
        if (delimiter != std::string::npos)
        {
            host = value.substr(0, delimiter);
            port_text = value.substr(delimiter + 1);
        }
        else
        {
            host = value;
        }
    }

    host = Trim(host);
    if (host.empty())
    {
        throw std::runtime_error("invalid DRTP endpoint host in " + field_name + ": " + value);
    }

    if (port_text.empty())
    {
        throw std::runtime_error("DRTP endpoint must include host and port in " + field_name + ": " + value);
    }

    const int port = ParseIntValue(Trim(port_text), field_name);
    if (port <= 0)
    {
        throw std::runtime_error(
            "DRTP endpoint port is required and must be positive in " + field_name + ": " + value);
    }

    return {host, port};
}

std::vector<TdsEndpoint> ParseDrtpEndpointList(
    const std::string& raw,
    const std::string& field_name)
{
    std::vector<TdsEndpoint> endpoints;
    for (const std::string& item : SplitDelimitedList(raw))
    {
        endpoints.push_back(ParseDrtpEndpoint(item, field_name));
    }
    return endpoints;
}

std::vector<TdsEndpoint> ApplyDrtpCliOverrides(std::vector<TdsEndpoint> endpoints, const CliOptions& cli)
{
    if (!cli.drtp_endpoints.empty())
    {
        return ParseDrtpEndpointList(cli.drtp_endpoints, "--drtp-endpoints");
    }

    return endpoints;
}

std::string CurrentTimestamp()
{
    const auto now = std::chrono::system_clock::now();
    const std::time_t now_time = std::chrono::system_clock::to_time_t(now);
    std::tm time_info {};
#ifdef _WIN32
    localtime_s(&time_info, &now_time);
#else
    localtime_r(&now_time, &time_info);
#endif

    std::ostringstream output;
    output << std::put_time(&time_info, "%Y%m%d_%H%M%S");
    return output.str();
}

std::string FormatDouble(double value)
{
    std::ostringstream output;
    output << std::fixed << std::setprecision(6) << value;
    std::string text = output.str();

    while (!text.empty() && text.back() == '0')
    {
        text.pop_back();
    }
    if (!text.empty() && text.back() == '.')
    {
        text.pop_back();
    }
    if (text.empty())
    {
        return "0";
    }
    return text;
}

std::string EscapeCsv(const std::string& value)
{
    if (value.find_first_of(",\"\n\r") == std::string::npos)
    {
        return value;
    }

    std::string escaped = "\"";
    for (const char ch : value)
    {
        if (ch == '"')
        {
            escaped += "\"\"";
        }
        else
        {
            escaped += ch;
        }
    }
    escaped += "\"";
    return escaped;
}

std::string EscapeHtml(const std::string& value)
{
    std::string escaped;
    escaped.reserve(value.size());

    for (const char ch : value)
    {
        switch (ch)
        {
        case '&':
            escaped += "&amp;";
            break;
        case '<':
            escaped += "&lt;";
            break;
        case '>':
            escaped += "&gt;";
            break;
        case '"':
            escaped += "&quot;";
            break;
        case '\'':
            escaped += "&#39;";
            break;
        default:
            escaped += ch;
            break;
        }
    }

    return escaped;
}

std::string Base64Encode(const std::string& input)
{
    static const char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        "abcdefghijklmnopqrstuvwxyz"
        "0123456789+/";

    std::string encoded;
    encoded.reserve(((input.size() + 2) / 3) * 4);

    for (std::size_t index = 0; index < input.size(); index += 3)
    {
        const unsigned int octet_a = static_cast<unsigned char>(input[index]);
        const unsigned int octet_b =
            index + 1 < input.size() ? static_cast<unsigned char>(input[index + 1]) : 0;
        const unsigned int octet_c =
            index + 2 < input.size() ? static_cast<unsigned char>(input[index + 2]) : 0;
        const unsigned int triple = (octet_a << 16U) | (octet_b << 8U) | octet_c;

        encoded += kAlphabet[(triple >> 18U) & 0x3FU];
        encoded += kAlphabet[(triple >> 12U) & 0x3FU];
        encoded += index + 1 < input.size() ? kAlphabet[(triple >> 6U) & 0x3FU] : '=';
        encoded += index + 2 < input.size() ? kAlphabet[triple & 0x3FU] : '=';
    }

    return encoded;
}

std::string WrapBase64(const std::string& encoded)
{
    std::ostringstream output;
    for (std::size_t index = 0; index < encoded.size(); index += 76)
    {
        output << encoded.substr(index, 76) << "\r\n";
    }
    return output.str();
}

std::string EncodeMimeHeader(const std::string& value)
{
    if (IsAscii(value))
    {
        return value;
    }

    return "=?UTF-8?B?" + Base64Encode(value) + "?=";
}

std::string ReadBinaryFile(const std::string& path)
{
    std::ifstream input(path, std::ios::binary);
    if (!input.is_open())
    {
        throw std::runtime_error("failed to open attachment: " + path);
    }

    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

std::string QuoteForShell(const std::string& value)
{
#ifdef _WIN32
    std::string quoted = "\"";
    for (const char ch : value)
    {
        if (ch == '"')
        {
            quoted += "\"\"";
        }
        else
        {
            quoted += ch;
        }
    }
    quoted += "\"";
    return quoted;
#else
    std::string quoted = "'";
    for (const char ch : value)
    {
        if (ch == '\'')
        {
            quoted += "'\\''";
        }
        else
        {
            quoted += ch;
        }
    }
    quoted += "'";
    return quoted;
#endif
}

std::string EscapeCurlConfigValue(const std::string& value)
{
    std::string escaped;
    escaped.reserve(value.size());

    for (const char ch : value)
    {
        if (ch == '\\' || ch == '"')
        {
            escaped += '\\';
        }
        escaped += ch;
    }

    return escaped;
}

std::string ResolveConfigRelativePath(
    const std::filesystem::path& config_dir,
    const std::string& raw_path)
{
    if (raw_path.empty())
    {
        return raw_path;
    }

    if (!raw_path.empty() && (raw_path[0] == '/' || raw_path[0] == '\\'))
    {
        return raw_path;
    }

    const std::filesystem::path path_value(raw_path);
    if (path_value.is_absolute())
    {
        return path_value.string();
    }

    return std::filesystem::absolute(config_dir / path_value).string();
}

std::filesystem::path CurrentExecutablePath()
{
#ifdef _WIN32
    std::wstring buffer(512, L'\0');
    for (;;)
    {
        const DWORD length = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
        if (length == 0)
        {
            return {};
        }
        if (length < buffer.size() - 1)
        {
            buffer.resize(length);
            return std::filesystem::path(buffer);
        }
        buffer.resize(buffer.size() * 2);
    }
#else
    std::string buffer(512, '\0');
    for (;;)
    {
        const ssize_t length = readlink("/proc/self/exe", buffer.data(), buffer.size() - 1);
        if (length < 0)
        {
            return {};
        }
        if (static_cast<std::size_t>(length) < buffer.size() - 1)
        {
            buffer.resize(static_cast<std::size_t>(length));
            return std::filesystem::path(buffer);
        }
        buffer.resize(buffer.size() * 2);
    }
#endif
}

std::vector<std::filesystem::path> DefaultConfigRoots()
{
    std::vector<std::filesystem::path> roots;
    const std::filesystem::path executable_path = CurrentExecutablePath();
    if (!executable_path.empty())
    {
        const std::filesystem::path executable_dir = executable_path.parent_path();
        roots.push_back((executable_dir / ".." / "config").lexically_normal());
        roots.push_back((executable_dir / "config").lexically_normal());
        roots.push_back((executable_dir.parent_path() / "config").lexically_normal());
    }
    roots.emplace_back("config");
    return roots;
}

std::string GetEnvironmentValue(const std::string& key)
{
    const char* value = std::getenv(key.c_str());
    if (value == nullptr)
    {
        return "";
    }
    return value;
}

std::string EffectiveVaultValue(
    const std::string& configured_value,
    const std::string& env_name,
    const std::string& fallback = "")
{
    if (!configured_value.empty())
    {
        return configured_value;
    }

    const std::string env_value = GetEnvironmentValue(env_name);
    if (!env_value.empty())
    {
        return env_value;
    }

    return fallback;
}

VaultConfig BuildEffectiveVaultConfig(const VaultConfig& configured)
{
    VaultConfig effective = configured;
    effective.address = EffectiveVaultValue(configured.address, "VAULT_ADDR");
    effective.namespace_name = EffectiveVaultValue(configured.namespace_name, "VAULT_NAMESPACE");
    effective.secret_engine = EffectiveVaultValue(configured.secret_engine, "VAULT_SECRET_ENGINE");
    effective.secret_path = EffectiveVaultValue(configured.secret_path, "VAULT_SECRET_PATH");
    effective.secret_key = EffectiveVaultValue(configured.secret_key, "VAULT_SECRET_KEY");
    return effective;
}

void SecureClear(std::string& value)
{
    if (!value.empty())
    {
        volatile char* data = &value[0];
        for (std::size_t index = 0; index < value.size(); ++index)
        {
            data[index] = '\0';
        }
    }
    value.clear();
}

struct SecureStringScope
{
    explicit SecureStringScope(std::string& scoped_value)
        : value(scoped_value)
    {
    }

    ~SecureStringScope()
    {
        SecureClear(value);
    }

    std::string& value;
};

struct SecureStringVector
{
    explicit SecureStringVector(std::vector<std::string> initial_values)
        : values(std::move(initial_values))
    {
    }

    ~SecureStringVector()
    {
        for (std::string& value : values)
        {
            SecureClear(value);
        }
    }

    std::vector<std::string> values;
};

#ifdef REPORT_HAS_LIBCURL
struct CurlEasyDeleter
{
    void operator()(CURL* curl) const
    {
        curl_easy_cleanup(curl);
    }
};

struct CurlHeaderListDeleter
{
    void operator()(curl_slist* headers) const
    {
        curl_slist_free_all(headers);
    }
};

struct VaultHttpResponse
{
    CURLcode curl_code = CURLE_OK;
    long http_status = 0;
    std::string body;
    std::string error;
};

void EnsureCurlGlobalInitialized()
{
    static std::once_flag init_once;
    static CURLcode init_result = CURLE_FAILED_INIT;
    std::call_once(init_once, []() {
        init_result = curl_global_init(CURL_GLOBAL_DEFAULT);
    });

    if (init_result != CURLE_OK)
    {
        throw std::runtime_error(
            std::string("failed to initialize libcurl: ") + curl_easy_strerror(init_result));
    }
}

std::size_t WriteCurlResponse(char* data, std::size_t size, std::size_t count, void* user_data)
{
    const std::size_t bytes = size * count;
    auto* output = static_cast<std::string*>(user_data);
    output->append(data, bytes);
    return bytes;
}

std::string CurlFailureSummary(const VaultHttpResponse& response)
{
    std::ostringstream message;
    if (response.curl_code != CURLE_OK)
    {
        message << "libcurl error " << static_cast<int>(response.curl_code)
                << " (" << curl_easy_strerror(response.curl_code) << ")";
        if (!response.error.empty())
        {
            message << ": " << response.error;
        }
        return message.str();
    }

    message << "HTTP status " << response.http_status;
    return message.str();
}

VaultHttpResponse VaultHttpRequest(
    const VaultConfig& vault,
    const std::string& method,
    const std::string& url,
    std::vector<std::string> headers,
    bool kerberos_negotiate)
{
    EnsureCurlGlobalInitialized();

    SecureStringVector header_storage(std::move(headers));
    if (!vault.namespace_name.empty())
    {
        header_storage.values.push_back("X-Vault-Namespace: " + vault.namespace_name);
    }

    std::unique_ptr<CURL, CurlEasyDeleter> curl(curl_easy_init());
    if (!curl)
    {
        throw std::runtime_error("failed to initialize a libcurl easy handle");
    }

    curl_slist* raw_headers = nullptr;
    for (const std::string& header : header_storage.values)
    {
        curl_slist* appended_headers = curl_slist_append(raw_headers, header.c_str());
        if (appended_headers == nullptr)
        {
            curl_slist_free_all(raw_headers);
            throw std::runtime_error("failed to allocate libcurl HTTP headers");
        }
        raw_headers = appended_headers;
    }
    std::unique_ptr<curl_slist, CurlHeaderListDeleter> header_list(raw_headers);

    VaultHttpResponse response;
    char error_buffer[CURL_ERROR_SIZE] = {};

    curl_easy_setopt(curl.get(), CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl.get(), CURLOPT_ERRORBUFFER, error_buffer);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, WriteCurlResponse);
    curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &response.body);
    curl_easy_setopt(curl.get(), CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl.get(), CURLOPT_CONNECTTIMEOUT, 15L);
    curl_easy_setopt(curl.get(), CURLOPT_TIMEOUT, 60L);

    if (header_list)
    {
        curl_easy_setopt(curl.get(), CURLOPT_HTTPHEADER, header_list.get());
    }

    if (kerberos_negotiate)
    {
        curl_easy_setopt(curl.get(), CURLOPT_HTTPAUTH, CURLAUTH_NEGOTIATE);
        curl_easy_setopt(curl.get(), CURLOPT_USERPWD, ":");
    }

    if (method == "POST")
    {
        curl_easy_setopt(curl.get(), CURLOPT_POST, 1L);
        curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDS, "");
        curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDSIZE, 0L);
    }
    else if (method == "GET")
    {
        curl_easy_setopt(curl.get(), CURLOPT_HTTPGET, 1L);
    }
    else
    {
        curl_easy_setopt(curl.get(), CURLOPT_CUSTOMREQUEST, method.c_str());
    }

    response.curl_code = curl_easy_perform(curl.get());
    response.error = error_buffer;
    if (response.curl_code == CURLE_OK)
    {
        curl_easy_getinfo(curl.get(), CURLINFO_RESPONSE_CODE, &response.http_status);
    }

    return response;
}
#endif

struct VaultSecretReference
{
    std::string path;
    std::string field;
};

std::string TrimSlashes(std::string value)
{
    while (!value.empty() && value.front() == '/')
    {
        value.erase(value.begin());
    }
    while (!value.empty() && value.back() == '/')
    {
        value.pop_back();
    }
    return value;
}

std::string VaultApiUrl(const VaultConfig& vault, const std::string& api_path)
{
    if (vault.address.empty())
    {
        throw std::runtime_error("VAULT_ADDR or vault.address is required for vault-backed secrets");
    }

    std::string address = vault.address;
    while (!address.empty() && address.back() == '/')
    {
        address.pop_back();
    }

    return address + "/v1/" + TrimSlashes(api_path);
}

std::string VaultKerberosLoginPath()
{
    return "auth/kerberos/login";
}

std::string VaultKvV2SecretPath(const VaultConfig& vault, const std::string& secret_path)
{
    if (vault.secret_engine.empty())
    {
        throw std::runtime_error("VAULT_SECRET_ENGINE or vault.secret_engine is required for vault-backed secrets");
    }
    if (secret_path.empty())
    {
        throw std::runtime_error("VAULT_SECRET_PATH or vault.secret_path is required for vault-backed secrets");
    }

    return TrimSlashes(vault.secret_engine) + "/data/" + TrimSlashes(secret_path);
}

std::string JsonUnescapeString(const std::string& raw)
{
    std::string output;
    output.reserve(raw.size());

    for (std::size_t index = 0; index < raw.size(); ++index)
    {
        const char ch = raw[index];
        if (ch != '\\' || index + 1 >= raw.size())
        {
            output += ch;
            continue;
        }

        const char escaped = raw[++index];
        switch (escaped)
        {
            case '"': output += '"'; break;
            case '\\': output += '\\'; break;
            case '/': output += '/'; break;
            case 'b': output += '\b'; break;
            case 'f': output += '\f'; break;
            case 'n': output += '\n'; break;
            case 'r': output += '\r'; break;
            case 't': output += '\t'; break;
            default:
                output += escaped;
                break;
        }
    }

    return output;
}

std::string ExtractJsonStringField(const std::string& json, const std::string& field)
{
    const std::string quoted_field = "\"" + field + "\"";
    std::size_t search_pos = 0;

    while ((search_pos = json.find(quoted_field, search_pos)) != std::string::npos)
    {
        std::size_t pos = search_pos + quoted_field.size();
        while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos])) != 0)
        {
            ++pos;
        }
        if (pos >= json.size() || json[pos] != ':')
        {
            search_pos = pos;
            continue;
        }
        ++pos;
        while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos])) != 0)
        {
            ++pos;
        }
        if (pos >= json.size() || json[pos] != '"')
        {
            search_pos = pos;
            continue;
        }

        ++pos;
        std::string raw_value;
        bool escaped = false;
        for (; pos < json.size(); ++pos)
        {
            const char ch = json[pos];
            if (escaped)
            {
                raw_value += '\\';
                raw_value += ch;
                escaped = false;
                continue;
            }
            if (ch == '\\')
            {
                escaped = true;
                continue;
            }
            if (ch == '"')
            {
                return JsonUnescapeString(raw_value);
            }
            raw_value += ch;
        }

        break;
    }

    return "";
}

bool IsVaultSecretReference(const std::string& value)
{
    return value.rfind("vault://", 0) == 0;
}

VaultSecretReference ParseVaultSecretReference(const std::string& value)
{
    const std::string body = value.substr(std::string("vault://").size());
    const std::size_t delimiter = body.rfind('#');
    if (body.empty() || delimiter == std::string::npos || delimiter == 0 || delimiter + 1 >= body.size())
    {
        throw std::runtime_error(
            "invalid vault secret reference, expected vault://<secret-path>#<field>: " + value);
    }

    return {body.substr(0, delimiter), body.substr(delimiter + 1)};
}

#ifdef REPORT_HAS_LIBCURL
std::string LoginToVault(const VaultConfig& configured_vault)
{
    const VaultConfig vault = BuildEffectiveVaultConfig(configured_vault);
    const std::string login_url = VaultApiUrl(vault, VaultKerberosLoginPath());
    VaultHttpResponse response = VaultHttpRequest(vault, "POST", login_url, {}, true);
    if (response.curl_code != CURLE_OK || response.http_status < 200 || response.http_status >= 300)
    {
        const std::string failure = CurlFailureSummary(response);
        SecureClear(response.body);
        throw std::runtime_error(
            "vault kerberos HTTP login failed: " + failure);
    }

    const std::string token = ExtractJsonStringField(response.body, "client_token");
    SecureClear(response.body);
    if (token.empty())
    {
        throw std::runtime_error("vault kerberos HTTP login did not return auth.client_token");
    }

    return token;
}

std::string ReadVaultSecretField(
    const VaultConfig& configured_vault,
    const std::string& secret_path,
    const std::string& secret_key,
    const std::string& secret_description)
{
    const VaultConfig vault = BuildEffectiveVaultConfig(configured_vault);
    if (secret_key.empty())
    {
        throw std::runtime_error("VAULT_SECRET_KEY or vault.secret_key is required for vault-backed secrets");
    }

    std::string token = LoginToVault(vault);
    SecureStringScope clear_token_on_return(token);

    {
        std::vector<std::string> request_headers;
        std::string token_header = "X-Vault-Token: ";
        token_header += token;
        request_headers.push_back(std::move(token_header));

        VaultHttpResponse response = VaultHttpRequest(
            vault,
            "GET",
            VaultApiUrl(vault, VaultKvV2SecretPath(vault, secret_path)),
            std::move(request_headers),
            false);
        if (response.curl_code != CURLE_OK || response.http_status < 200 || response.http_status >= 300)
        {
            const std::string last_error = CurlFailureSummary(response);
            SecureClear(response.body);
            throw std::runtime_error(
                "vault HTTP read failed for " + secret_description + ": " + last_error);
        }

        const std::string secret_value = ExtractJsonStringField(response.body, secret_key);
        SecureClear(response.body);
        if (!secret_value.empty())
        {
            return secret_value;
        }

        throw std::runtime_error(
            "vault HTTP read failed for " + secret_description + ": field not found in vault response");
    }
}

std::string ReadVaultSecret(const std::string& reference, const VaultConfig& configured_vault)
{
    const VaultSecretReference secret = ParseVaultSecretReference(reference);
    return ReadVaultSecretField(configured_vault, secret.path, secret.field, secret.path + "#" + secret.field);
}
#else
std::string ReadVaultSecretField(
    const VaultConfig& configured_vault,
    const std::string& secret_path,
    const std::string& secret_key,
    const std::string& secret_description)
{
    (void)configured_vault;
    (void)secret_path;
    (void)secret_key;
    (void)secret_description;
    throw std::runtime_error(
        "Vault-backed secrets require C++ libcurl support; install libcurl-devel on RHEL8 and rebuild");
}

std::string ReadVaultSecret(const std::string& reference, const VaultConfig& configured_vault)
{
    (void)reference;
    (void)configured_vault;
    throw std::runtime_error(
        "Vault-backed secrets require C++ libcurl support; install libcurl-devel on RHEL8 and rebuild");
}
#endif

std::string ResolveSecretReference(const std::string& value, const VaultConfig& vault)
{
    if (!IsVaultSecretReference(value))
    {
        return value;
    }

    return ReadVaultSecret(value, vault);
}

std::string ResolveTdsPassword(const std::string& configured_password, const VaultConfig& vault)
{
    if (!configured_password.empty())
    {
        return ResolveSecretReference(configured_password, vault);
    }

    const VaultConfig effective_vault = BuildEffectiveVaultConfig(vault);
    if (!effective_vault.secret_path.empty())
    {
        return ReadVaultSecretField(
            effective_vault,
            effective_vault.secret_path,
            effective_vault.secret_key,
            effective_vault.secret_path + "#" + effective_vault.secret_key);
    }

    return "";
}

std::vector<CustomerFundRecord> SortRecords(std::vector<CustomerFundRecord> records)
{
    std::sort(
        records.begin(),
        records.end(),
        [](const CustomerFundRecord& left, const CustomerFundRecord& right) {
            return std::tie(left.cust_no, left.fund_account_no) <
                   std::tie(right.cust_no, right.fund_account_no);
        });
    return records;
}

std::vector<std::string> EffectiveRecipients(
    const std::vector<std::string>& cli_recipients,
    const std::vector<std::string>& config_recipients)
{
    return cli_recipients.empty() ? config_recipients : cli_recipients;
}

struct MailReportRow
{
    std::string cust_no;
    std::string cust_name;
    double total_equity = 0.0;
    double mtm_pnl = 0.0;
    double available_funds = 0.0;
    double risk_ratio1 = 0.0;
    double risk_ratio2 = 0.0;
};

std::string InsertThousandsSeparators(std::string text)
{
    const std::size_t decimal_pos = text.find('.');
    const std::size_t insert_end = decimal_pos == std::string::npos ? text.size() : decimal_pos;
    const std::size_t begin = !text.empty() && text.front() == '-' ? 1U : 0U;

    for (std::size_t pos = insert_end; pos > begin + 3; pos -= 3)
    {
        text.insert(pos - 3, ",");
    }

    return text;
}

std::string FormatAmount(double value)
{
    std::ostringstream output;
    output << std::fixed << std::setprecision(2) << value;
    return InsertThousandsSeparators(output.str());
}

std::string FormatPercentage(double value)
{
    std::ostringstream output;
    output << std::fixed << std::setprecision(2) << (value * 100.0);
    return InsertThousandsSeparators(output.str()) + "%";
}

std::string BuildReportTitle(const AppConfig& config, int trade_date)
{
    return config.email_subject + " - Market Close " + std::to_string(trade_date);
}

std::vector<MailReportRow> BuildMailReportRows(const std::vector<CustomerFundRecord>& records)
{
    const std::vector<CustomerFundRecord> sorted = SortRecords(records);
    std::vector<MailReportRow> rows;

    for (const CustomerFundRecord& record : sorted)
    {
        auto existing = std::find_if(
            rows.begin(),
            rows.end(),
            [&](const MailReportRow& row) { return row.cust_no == record.cust_no; });

        if (existing == rows.end())
        {
            MailReportRow row;
            row.cust_no = record.cust_no;
            row.cust_name = record.cust_name;
            row.total_equity = record.dyn_rights;
            row.mtm_pnl = record.hold_profit;
            row.available_funds = record.avail_fund;
            row.risk_ratio1 = record.risk_degree1;
            row.risk_ratio2 = record.risk_degree2;
            rows.push_back(row);
            continue;
        }

        existing->total_equity += record.dyn_rights;
        existing->mtm_pnl += record.hold_profit;
        existing->available_funds += record.avail_fund;
        existing->risk_ratio1 = std::max(existing->risk_ratio1, record.risk_degree1);
        existing->risk_ratio2 = std::max(existing->risk_ratio2, record.risk_degree2);
        if (existing->cust_name.empty() && !record.cust_name.empty())
        {
            existing->cust_name = record.cust_name;
        }
    }

    return rows;
}

std::string BuildHtmlBody(const std::vector<CustomerFundRecord>& records, const AppConfig& config, int trade_date)
{
    const std::vector<MailReportRow> rows = BuildMailReportRows(records);
    double total_dyn_rights = 0.0;
    double total_hold_profit = 0.0;
    double total_avail_fund = 0.0;
    int issue_count = 0;

    for (const MailReportRow& row : rows)
    {
        total_dyn_rights += row.total_equity;
        total_hold_profit += row.mtm_pnl;
        total_avail_fund += row.available_funds;
        if (row.risk_ratio1 > 0.70 || row.risk_ratio2 > 1.00)
        {
            ++issue_count;
        }
    }

    const std::string report_title = BuildReportTitle(config, trade_date);

    std::ostringstream output;
    output << "<html><body style=\"margin:0;padding:18px 28px;font-family:Calibri,Arial,sans-serif;"
              "font-size:14px;line-height:1.35;color:#111;\">";
    output << "<div style=\"max-width:768px;\">";
    output << "<p style=\"margin:0 0 22px 0;font-size:16px;font-weight:700;\">"
           << EscapeHtml(report_title) << "</p>";

    if (issue_count == 0)
    {
        output << "<p style=\"margin:0 0 22px 0;font-size:15px;\">"
               << "As of 15:00 of T Day, no client funding and risk ratio issues identified from Risk Management."
               << "</p>";
    }
    else
    {
        output << "<p style=\"margin:0 0 22px 0;font-size:15px;\">"
               << "As of 15:00 of T Day, "
               << issue_count
               << " client funding and risk ratio issue(s) identified from Risk Management."
               << "</p>";
    }

    output << "<p style=\"margin:0 0 8px 0;font-size:16px;font-weight:700;\">"
           << u8"\u5BA2\u6237\u8D44\u91D1\u53CA\u98CE\u9669\u5EA6 Funding &amp; Risk ratio"
           << "</p>";
    output << "<table cellpadding=\"0\" cellspacing=\"0\" style=\"width:768px;max-width:100%;border-collapse:collapse;"
              "border:1px solid #29445d;table-layout:fixed;\">";
    output << "<tr style=\"background:#0f4f8a;color:#fff;font-weight:700;text-align:center;\">"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:12%;font-size:13px;\">"
           << u8"\u5BA2\u6237\u53F7"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:16%;font-size:13px;\">"
           << u8"\u5BA2\u6237\u540D\u79F0"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:14%;font-size:13px;\">"
           << u8"\u5BA2\u6237\u6743\u76CA"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:14%;font-size:13px;\">"
           << u8"\u6D6E\u52A8\u76C8\u4E8F"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:18%;font-size:13px;\">"
           << u8"\u53EF\u7528\u8D44\u91D1"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:13%;font-size:13px;\">"
           << u8"*\u98CE\u9669\u5EA6 1"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:13%;font-size:13px;\">"
           << u8"*\u98CE\u9669\u5EA6 2"
           << "</th>"
           << "</tr>";
    output << "<tr style=\"background:#0f4f8a;color:#fff;font-weight:700;text-align:center;\">"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">Account</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">Client</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">Total Equity</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">MTM PnL</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">Available Funds</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">*Risk Ratio 1</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">*Risk Ratio 2</th>"
           << "</tr>";
    output << "<tr style=\"background:#ffffff;\">"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;font-weight:700;\">"
           << u8"Total \u5408\u8BA1:"
           << "</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;\">&nbsp;</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
           << FormatAmount(total_dyn_rights) << "</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
           << FormatAmount(total_hold_profit) << "</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
           << FormatAmount(total_avail_fund) << "</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;background:#dfe9d9;\">&nbsp;</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;background:#dfe9d9;\">&nbsp;</td>"
           << "</tr>";

    for (const MailReportRow& row : rows)
    {
        output << "<tr style=\"background:#ffffff;\">"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;\">"
               << EscapeHtml(row.cust_no) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;\">"
               << EscapeHtml(row.cust_name) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
               << FormatAmount(row.total_equity) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
               << FormatAmount(row.mtm_pnl) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
               << FormatAmount(row.available_funds) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;background:#dfe9d9;\">"
               << FormatPercentage(row.risk_ratio1) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;background:#dfe9d9;\">"
               << FormatPercentage(row.risk_ratio2) << "</td>"
               << "</tr>";
    }

    output << "</table>";
    output << "<div style=\"margin-top:32px;max-width:360px;\">";
    output << "<p style=\"margin:0 0 8px 0;font-size:14px;font-weight:700;\">"
           << "*Note: Risk ratio escalation threshold"
           << "</p>";
    output << "<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;min-width:360px;\">";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#dcead2;font-size:12px;font-weight:700;\">No Action Required</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 1</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#dcead2;font-size:12px;font-weight:700;\">&lt;70%</td>"
           << "</tr>";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#f1d54d;font-size:12px;font-weight:700;\">Alert</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 1</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#f1d54d;font-size:12px;font-weight:700;\">&gt;70%</td>"
           << "</tr>";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#d8a06c;font-size:12px;font-weight:700;\">Official Margin Call</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 1</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#d8a06c;font-size:12px;font-weight:700;\">&gt;80%</td>"
           << "</tr>";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#c41212;color:#111;font-size:12px;font-weight:700;\">Final Official Margin</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 1</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#c41212;color:#111;font-size:12px;font-weight:700;\">&gt;100%</td>"
           << "</tr>";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#3b0202;color:#fff;font-size:12px;font-weight:700;\">Forced Liquidation</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 2</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#3b0202;color:#fff;font-size:12px;font-weight:700;\">&gt;100%</td>"
           << "</tr>";
    output << "</table>";
    output << "</div>";
    output << "</div>";
    output << "</body></html>";
    return output.str();
}

} // namespace

std::vector<std::string> SplitList(const std::string& raw)
{
    std::vector<std::string> values;
    std::string current;

    for (const char ch : raw)
    {
        if (ch == ',' || ch == ';')
        {
            const std::string trimmed = Trim(current);
            if (!trimmed.empty())
            {
                values.push_back(trimmed);
            }
            current.clear();
        }
        else
        {
            current += ch;
        }
    }

    const std::string trimmed = Trim(current);
    if (!trimmed.empty())
    {
        values.push_back(trimmed);
    }

    return values;
}

std::string Join(const std::vector<std::string>& values, const std::string& delimiter)
{
    std::ostringstream output;
    for (std::size_t index = 0; index < values.size(); ++index)
    {
        if (index > 0)
        {
            output << delimiter;
        }
        output << values[index];
    }
    return output.str();
}

CliOptions ParseCli(int argc, char** argv)
{
    CliOptions options;

    auto read_value = [&](const std::string& current_arg, const std::string& name, int& index) -> std::string {
        const std::string prefix = name + "=";
        if (current_arg.rfind(prefix, 0) == 0)
        {
            return current_arg.substr(prefix.size());
        }
        if (current_arg == name)
        {
            if (index + 1 >= argc)
            {
                throw std::runtime_error("missing value for " + name);
            }
            ++index;
            return argv[index];
        }
        return "";
    };

    for (int index = 1; index < argc; ++index)
    {
        const std::string arg = argv[index];

        if (arg == "--help" || arg == "-h")
        {
            options.help = true;
            continue;
        }
        if (arg == "--dry-run")
        {
            options.dry_run = true;
            continue;
        }

        if (const std::string value = read_value(arg, "--env", index); !value.empty())
        {
            options.env = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--config", index); !value.empty())
        {
            options.config_path = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--drtp-endpoints", index); !value.empty())
        {
            options.drtp_endpoints = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--to", index); !value.empty())
        {
            options.to = SplitList(value);
            continue;
        }
        if (const std::string value = read_value(arg, "--cc", index); !value.empty())
        {
            options.cc = SplitList(value);
            continue;
        }
        if (const std::string value = read_value(arg, "--cust-list", index); !value.empty())
        {
            options.cust_filters = SplitList(value);
            continue;
        }
        if (const std::string value = read_value(arg, "--output-dir", index); !value.empty())
        {
            options.output_dir = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--stub-file", index); !value.empty())
        {
            options.stub_file = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--trade-date", index); !value.empty())
        {
            options.trade_date_override = ParseIntValue(value, "--trade-date");
            continue;
        }

        throw std::runtime_error("unknown argument: " + arg);
    }

    return options;
}

std::string DefaultConfigPath(const std::string& env_name)
{
    if (env_name.empty())
    {
        throw std::runtime_error("missing --env; use --env dev|qa|prod or pass --config path");
    }

    for (const std::filesystem::path& config_root : DefaultConfigRoots())
    {
        const std::filesystem::path env_config = config_root / (env_name + ".properties");
        if (std::filesystem::exists(env_config))
        {
            return std::filesystem::absolute(env_config).string();
        }
    }

    return (std::filesystem::path("config") / (env_name + ".properties")).string();
}

AppConfig LoadConfig(const std::string& path, const CliOptions& cli)
{
    const std::filesystem::path config_dir = std::filesystem::absolute(path).parent_path();
    const auto properties = ReadProperties(path);
    AppConfig config;

    config.env_name = cli.env.empty() ? GetValue(properties, "env.name", "dev") : cli.env;
    config.email_subject = GetValue(properties, "email.subject", config.email_subject);
    config.attachment_name = GetValue(properties, "report.attachment_name", config.attachment_name);
    config.output_dir = cli.output_dir.empty()
        ? ResolveConfigRelativePath(config_dir, GetValue(properties, "report.output_dir", config.output_dir))
        : cli.output_dir;
    config.default_to = SplitList(GetValue(properties, "email.default_to"));
    config.default_cc = SplitList(GetValue(properties, "email.default_cc"));
    config.log.directory = ResolveConfigRelativePath(config_dir, GetValue(properties, "log.dir", "../logs"));
    config.log.level = ParseLogLevelValue(GetValue(properties, "log.level", config.log.level));

    config.vault.address = GetValue(properties, "vault.address");
    config.vault.namespace_name = GetValue(properties, "vault.namespace");
    config.vault.secret_engine = GetValue(properties, "vault.secret_engine");
    config.vault.secret_path = GetValue(properties, "vault.secret_path");
    config.vault.secret_key = GetValue(properties, "vault.secret_key");

    std::vector<TdsEndpoint> drtp_endpoints =
        ParseDrtpEndpointList(GetRequiredValue(properties, "tds.drtp_endpoints"), "tds.drtp_endpoints");
    drtp_endpoints = ApplyDrtpCliOverrides(std::move(drtp_endpoints), cli);
    if (drtp_endpoints.empty())
    {
        throw std::runtime_error("at least one DRTP endpoint is required");
    }
    config.tds.drtp_endpoints = std::move(drtp_endpoints);
    config.tds.user = GetRequiredValue(properties, "tds.user");
    config.tds.password = ResolveTdsPassword(GetValue(properties, "tds.password"), config.vault);
    config.tds.req_timeout_ms = ParseIntValue(
        GetValue(properties, "tds.req_timeout_ms", std::to_string(config.tds.req_timeout_ms)),
        "tds.req_timeout_ms");
    config.tds.log_level = ParseIntValue(
        GetValue(properties, "tds.log_level", std::to_string(config.tds.log_level)),
        "tds.log_level");
    config.tds.klg_enable = ParseBoolValue(
        GetValue(properties, "tds.klg_enable", config.tds.klg_enable ? "true" : "false"),
        "tds.klg_enable");
    config.tds.function_no = ParseIntValue(
        GetValue(properties, "tds.function_no", std::to_string(config.tds.function_no)),
        "tds.function_no");

    config.smtp.host = GetRequiredValue(properties, "smtp.host");
    config.smtp.port = ParseIntValue(
        GetValue(properties, "smtp.port", std::to_string(config.smtp.port)),
        "smtp.port");
    config.smtp.from = GetRequiredValue(properties, "smtp.from");
    config.smtp.security = ToLower(GetValue(properties, "smtp.security", config.smtp.security));
    config.smtp.client_cert_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "smtp.client_cert_path"));
    config.smtp.client_key_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "smtp.client_key_path"));
    config.smtp.client_cert_type = GetValue(properties, "smtp.client_cert_type", config.smtp.client_cert_type);
    config.smtp.client_key_type = GetValue(properties, "smtp.client_key_type", config.smtp.client_key_type);
    config.smtp.client_key_password = GetValue(properties, "smtp.client_key_password");
    config.smtp.insecure = ParseBoolValue(
        GetValue(properties, "smtp.insecure", config.smtp.insecure ? "true" : "false"),
        "smtp.insecure");
    config.smtp.connect_timeout_seconds = ParseIntValue(
        GetValue(
            properties,
            "smtp.connect_timeout_seconds",
            std::to_string(config.smtp.connect_timeout_seconds)),
        "smtp.connect_timeout_seconds");

    return config;
}

std::string WriteCsvReport(const std::vector<CustomerFundRecord>& records, const AppConfig& config, int trade_date)
{
    std::filesystem::create_directories(config.output_dir);

    const std::string filename =
        config.attachment_name + "_" + config.env_name + "_" + std::to_string(trade_date) + ".csv";
    const std::filesystem::path output_path =
        std::filesystem::absolute(std::filesystem::path(config.output_dir) / filename);
    std::ofstream output(output_path);

    if (!output.is_open())
    {
        throw std::runtime_error("failed to create csv report: " + output_path.string());
    }

    output << "trade_date,cust_no,cust_name,fund_account_no,"
           << "dyn_rights,hold_profit,avail_fund,risk_degree1,risk_degree2\n";

    for (const CustomerFundRecord& record : SortRecords(records))
    {
        output << record.trade_date << ','
               << EscapeCsv(record.cust_no) << ','
               << EscapeCsv(record.cust_name) << ','
               << EscapeCsv(record.fund_account_no) << ','
               << FormatDouble(record.dyn_rights) << ','
               << FormatDouble(record.hold_profit) << ','
               << FormatDouble(record.avail_fund) << ','
               << FormatDouble(record.risk_degree1) << ','
               << FormatDouble(record.risk_degree2) << '\n';
    }

    LogInfo(
        "csv_report_written",
        "CSV report written",
        {
            {"trade_date", std::to_string(trade_date)},
            {"row_count", std::to_string(records.size())},
            {"path", output_path.string()}
        });
    return output_path.string();
}

MailRequest BuildMailRequest(
    const std::vector<CustomerFundRecord>& records,
    const AppConfig& config,
    const CliOptions& cli,
    int trade_date,
    const std::string& attachment_path)
{
    MailRequest request;
    request.from = config.smtp.from;
    request.to = EffectiveRecipients(cli.to, config.default_to);
    request.cc = EffectiveRecipients(cli.cc, config.default_cc);

    if (request.to.empty())
    {
        throw std::runtime_error("no email recipients configured; use config email.default_to or --to");
    }

    request.subject = BuildReportTitle(config, trade_date);
    request.html_body = BuildHtmlBody(records, config, trade_date);
    request.attachment_path = attachment_path;
    request.attachment_name = std::filesystem::path(attachment_path).filename().string();
    return request;
}

std::string BuildMimeMessage(const MailRequest& request)
{
    const std::string boundary = "----REPORT_BOUNDARY_" + CurrentTimestamp();
    const std::string attachment_bytes = ReadBinaryFile(request.attachment_path);
    const std::string encoded_attachment = WrapBase64(Base64Encode(attachment_bytes));

    std::ostringstream output;
    output << "From: " << request.from << "\r\n";
    output << "To: " << Join(request.to, ", ") << "\r\n";
    if (!request.cc.empty())
    {
        output << "Cc: " << Join(request.cc, ", ") << "\r\n";
    }
    output << "Subject: " << EncodeMimeHeader(request.subject) << "\r\n";
    output << "MIME-Version: 1.0\r\n";
    output << "Content-Type: multipart/mixed; boundary=\"" << boundary << "\"\r\n";
    output << "\r\n";
    output << "--" << boundary << "\r\n";
    output << "Content-Type: text/html; charset=\"utf-8\"\r\n";
    output << "Content-Transfer-Encoding: 8bit\r\n";
    output << "\r\n";
    output << request.html_body << "\r\n";
    output << "\r\n";
    output << "--" << boundary << "\r\n";
    output << "Content-Type: text/csv; charset=\"utf-8\"; name=\"" << request.attachment_name << "\"\r\n";
    output << "Content-Transfer-Encoding: base64\r\n";
    output << "Content-Disposition: attachment; filename=\"" << request.attachment_name << "\"\r\n";
    output << "\r\n";
    output << encoded_attachment;
    output << "--" << boundary << "--\r\n";
    return output.str();
}

std::string BuildCurlConfig(const MailRequest& request, const AppConfig& config, const std::string& message_path)
{
    const bool has_client_cert = !config.smtp.client_cert_path.empty();
    const bool has_client_key = !config.smtp.client_key_path.empty();
    if (has_client_cert != has_client_key)
    {
        throw std::runtime_error("smtp.client_cert_path and smtp.client_key_path must be configured together");
    }

    std::ostringstream curl_config;
    const std::string scheme = config.smtp.security == "ssl" ? "smtps" : "smtp";
    curl_config << "url = \"" << EscapeCurlConfigValue(
        scheme + "://" + config.smtp.host + ":" + std::to_string(config.smtp.port)) << "\"\n";
    curl_config << "mail-from = \"" << EscapeCurlConfigValue(request.from) << "\"\n";
    for (const std::string& recipient : request.to)
    {
        curl_config << "mail-rcpt = \"" << EscapeCurlConfigValue(recipient) << "\"\n";
    }
    for (const std::string& recipient : request.cc)
    {
        curl_config << "mail-rcpt = \"" << EscapeCurlConfigValue(recipient) << "\"\n";
    }
    if (has_client_cert)
    {
        curl_config << "cert = \"" << EscapeCurlConfigValue(config.smtp.client_cert_path) << "\"\n";
        curl_config << "key = \"" << EscapeCurlConfigValue(config.smtp.client_key_path) << "\"\n";
        curl_config << "cert-type = \"" << EscapeCurlConfigValue(config.smtp.client_cert_type) << "\"\n";
        curl_config << "key-type = \"" << EscapeCurlConfigValue(config.smtp.client_key_type) << "\"\n";
        if (!config.smtp.client_key_password.empty())
        {
            curl_config << "pass = \"" << EscapeCurlConfigValue(config.smtp.client_key_password) << "\"\n";
        }
    }
    curl_config << "upload-file = \"" << EscapeCurlConfigValue(std::filesystem::path(message_path).generic_string()) << "\"\n";
    curl_config << "connect-timeout = \"" << config.smtp.connect_timeout_seconds << "\"\n";
    curl_config << "silent\n";
    curl_config << "show-error\n";
    if (config.smtp.security == "starttls")
    {
        curl_config << "ssl-reqd\n";
    }
    if (config.smtp.insecure)
    {
        curl_config << "insecure\n";
    }

    return curl_config.str();
}

SendMailResult SendMailWithCurl(const MailRequest& request, const AppConfig& config, bool dry_run)
{
    const std::string mime_message = BuildMimeMessage(request);
    const std::filesystem::path output_dir = config.output_dir;
    std::filesystem::create_directories(output_dir);

    if (dry_run)
    {
        const std::filesystem::path preview_path =
            std::filesystem::absolute(output_dir / ("mail_preview_" + CurrentTimestamp() + ".eml"));
        std::ofstream preview(preview_path, std::ios::binary);
        preview << mime_message;
        LogInfo(
            "mail_preview_written",
            "Dry-run preview file written",
            {
                {"path", preview_path.string()},
                {"recipient_count", std::to_string(request.to.size() + request.cc.size())}
            });
        return SendMailResult {false, preview_path.string()};
    }

    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path();
    const std::filesystem::path message_path =
        temp_dir / ("report_message_" + CurrentTimestamp() + ".eml");
    const std::filesystem::path curl_config_path =
        temp_dir / ("report_curl_" + CurrentTimestamp() + ".cfg");

    try
    {
        {
            std::ofstream message_file(message_path, std::ios::binary);
            if (!message_file.is_open())
            {
                throw std::runtime_error("failed to create temporary mime file");
            }
            message_file << mime_message;
        }

        {
            std::ofstream curl_file(curl_config_path);
            if (!curl_file.is_open())
            {
                throw std::runtime_error("failed to create temporary curl config");
            }
            curl_file << BuildCurlConfig(request, config, message_path.string());
        }

        const std::string command = "curl --config " + QuoteForShell(curl_config_path.string());
        LogInfo(
            "smtp_send_start",
            "Invoking curl SMTP delivery",
            {
                {"smtp_host", config.smtp.host},
                {"smtp_port", std::to_string(config.smtp.port)},
                {"recipient_count", std::to_string(request.to.size() + request.cc.size())}
            });
        const int exit_code = std::system(command.c_str());
        std::filesystem::remove(message_path);
        std::filesystem::remove(curl_config_path);

        if (exit_code != 0)
        {
            throw std::runtime_error("curl exited with code " + std::to_string(exit_code));
        }

        LogInfo("smtp_send_complete", "curl SMTP delivery completed successfully");
        return SendMailResult {true, ""};
    }
    catch (...)
    {
        std::error_code ignored;
        std::filesystem::remove(message_path, ignored);
        std::filesystem::remove(curl_config_path, ignored);
        throw;
    }
}

} // namespace report
