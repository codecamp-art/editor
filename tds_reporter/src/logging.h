#ifndef TDS_REPORTER_LOGGING_H
#define TDS_REPORTER_LOGGING_H

#include "app.h"

#include <string>
#include <utility>
#include <vector>

namespace tds_reporter {

using LogField = std::pair<std::string, std::string>;
using LogFields = std::vector<LogField>;

void InitializeLogger(const AppConfig& config);
void ShutdownLogger() noexcept;
bool IsLoggerInitialized() noexcept;
std::string CurrentLogFilePath();
void LogDebug(const std::string& event, const std::string& message, const LogFields& fields = {});
void LogInfo(const std::string& event, const std::string& message, const LogFields& fields = {});
void LogWarn(const std::string& event, const std::string& message, const LogFields& fields = {});
void LogError(const std::string& event, const std::string& message, const LogFields& fields = {});

} // namespace tds_reporter

#endif
