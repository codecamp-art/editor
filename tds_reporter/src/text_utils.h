#ifndef TDS_REPORTER_TEXT_UTILS_H
#define TDS_REPORTER_TEXT_UTILS_H

#include <string>

namespace tds_reporter {

std::string DecodeVendorText(const std::string& value);
std::string DescribeTdsErrorCode(int error_code);
std::string FormatTdsApiError(
    const std::string& operation,
    int error_code,
    const std::string& error_message);

} // namespace tds_reporter

#endif
