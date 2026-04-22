#include "logging.h"

#include <cctype>
#include <cstdlib>
#include <algorithm>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <stdexcept>

namespace tds_reporter {
namespace {

std::mutex g_logger_mutex;
std::ofstream g_log_stream;
std::string g_log_directory;
std::string g_log_level = "info";
std::string g_log_env = "dev";
std::string g_log_service = "tds_reporter";
std::string g_log_date;
std::string g_log_path;
bool g_logger_initialized = false;

std::tm LocalTime(std::time_t timestamp)
{
    std::tm local_time {};
#ifdef _WIN32
    localtime_s(&local_time, &timestamp);
#else
    localtime_r(&timestamp, &local_time);
#endif
    return local_time;
}

std::tm UtcTime(std::time_t timestamp)
{
    std::tm utc_time {};
#ifdef _WIN32
    gmtime_s(&utc_time, &timestamp);
#else
    gmtime_r(&timestamp, &utc_time);
#endif
    return utc_time;
}

std::string CurrentLogDate()
{
    const std::time_t now = std::time(nullptr);
    const std::tm local_time = LocalTime(now);
    std::ostringstream output;
    output << std::put_time(&local_time, "%Y-%m-%d");
    return output.str();
}

std::string CurrentTimestamp()
{
    const std::time_t now = std::time(nullptr);
    const std::tm local_time = LocalTime(now);
    const std::tm utc_time = UtcTime(now);
    std::tm local_copy = local_time;
    std::tm utc_copy = utc_time;
    const long offset_seconds = static_cast<long>(std::mktime(&local_copy) - std::mktime(&utc_copy));
    const long offset_minutes = offset_seconds / 60L;
    const long offset_hours = offset_minutes / 60L;
    const long remaining_minutes = std::labs(offset_minutes % 60L);

    std::ostringstream output;
    output << std::put_time(&local_time, "%Y-%m-%dT%H:%M:%S")
           << (offset_minutes >= 0 ? '+' : '-')
           << std::setw(2) << std::setfill('0') << std::labs(offset_hours)
           << ':'
           << std::setw(2) << std::setfill('0') << remaining_minutes;
    return output.str();
}

std::string NormalizeLevel(std::string level)
{
    std::transform(
        level.begin(),
        level.end(),
        level.begin(),
        [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });

    if (level == "debug" || level == "info" || level == "warn" || level == "error")
    {
        return level;
    }

    throw std::runtime_error("invalid log.level: " + level);
}

int LevelRank(const std::string& level)
{
    if (level == "debug")
    {
        return 10;
    }
    if (level == "info")
    {
        return 20;
    }
    if (level == "warn")
    {
        return 30;
    }
    return 40;
}

std::string EscapeJson(const std::string& value)
{
    std::ostringstream output;
    for (const unsigned char ch : value)
    {
        switch (ch)
        {
        case '\\':
            output << "\\\\";
            break;
        case '"':
            output << "\\\"";
            break;
        case '\b':
            output << "\\b";
            break;
        case '\f':
            output << "\\f";
            break;
        case '\n':
            output << "\\n";
            break;
        case '\r':
            output << "\\r";
            break;
        case '\t':
            output << "\\t";
            break;
        default:
            if (ch < 0x20U)
            {
                output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                       << static_cast<int>(ch) << std::dec << std::setfill(' ');
            }
            else
            {
                output << static_cast<char>(ch);
            }
            break;
        }
    }
    return output.str();
}

void EnsureLogStreamLocked()
{
    const std::string log_date = CurrentLogDate();
    if (g_log_stream.is_open() && g_log_date == log_date)
    {
        return;
    }

    if (!g_log_directory.empty())
    {
        std::filesystem::create_directories(g_log_directory);
    }

    const std::filesystem::path log_path =
        std::filesystem::absolute(std::filesystem::path(g_log_directory) / ("tds_reporter-" + log_date + ".log"));

    if (g_log_stream.is_open())
    {
        g_log_stream.close();
    }

    g_log_stream.open(log_path, std::ios::app);
    if (!g_log_stream.is_open())
    {
        throw std::runtime_error("failed to open log file: " + log_path.string());
    }

    g_log_date = log_date;
    g_log_path = log_path.string();
}

void WriteLog(
    const std::string& level,
    const std::string& event,
    const std::string& message,
    const LogFields& fields)
{
    std::lock_guard<std::mutex> lock(g_logger_mutex);
    if (!g_logger_initialized || LevelRank(level) < LevelRank(g_log_level))
    {
        return;
    }

    EnsureLogStreamLocked();

    g_log_stream << "{\"ts\":\"" << EscapeJson(CurrentTimestamp())
                 << "\",\"level\":\"" << EscapeJson(level)
                 << "\",\"service\":\"" << EscapeJson(g_log_service)
                 << "\",\"env\":\"" << EscapeJson(g_log_env)
                 << "\",\"event\":\"" << EscapeJson(event)
                 << "\",\"msg\":\"" << EscapeJson(message) << "\"";

    for (const auto& [key, value] : fields)
    {
        g_log_stream << ",\"" << EscapeJson(key) << "\":\"" << EscapeJson(value) << "\"";
    }

    g_log_stream << "}\n";
    g_log_stream.flush();
}

} // namespace

void InitializeLogger(const AppConfig& config)
{
    std::lock_guard<std::mutex> lock(g_logger_mutex);

    g_log_directory = config.log.directory;
    g_log_level = NormalizeLevel(config.log.level);
    g_log_env = config.env_name;
    g_log_service = "tds_reporter";
    g_log_date.clear();
    g_log_path.clear();
    g_logger_initialized = true;
    EnsureLogStreamLocked();
}

void ShutdownLogger() noexcept
{
    std::lock_guard<std::mutex> lock(g_logger_mutex);
    if (g_log_stream.is_open())
    {
        g_log_stream.flush();
        g_log_stream.close();
    }
    g_logger_initialized = false;
    g_log_date.clear();
    g_log_path.clear();
}

bool IsLoggerInitialized() noexcept
{
    std::lock_guard<std::mutex> lock(g_logger_mutex);
    return g_logger_initialized;
}

std::string CurrentLogFilePath()
{
    std::lock_guard<std::mutex> lock(g_logger_mutex);
    return g_log_path;
}

void LogDebug(const std::string& event, const std::string& message, const LogFields& fields)
{
    WriteLog("debug", event, message, fields);
}

void LogInfo(const std::string& event, const std::string& message, const LogFields& fields)
{
    WriteLog("info", event, message, fields);
}

void LogWarn(const std::string& event, const std::string& message, const LogFields& fields)
{
    WriteLog("warn", event, message, fields);
}

void LogError(const std::string& event, const std::string& message, const LogFields& fields)
{
    WriteLog("error", event, message, fields);
}

} // namespace tds_reporter
