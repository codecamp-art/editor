#ifndef REPORT_TEXT_UTILS_H
#define REPORT_TEXT_UTILS_H

#include <string>

namespace report {

std::string DecodeVendorText(const std::string& value);
std::string DescribeTdsErrorCode(int error_code);
bool IsTdsNoMoreDataResult(int error_code, const std::string& error_message);
std::string FormatTdsApiError(
    const std::string& operation,
    int error_code,
    const std::string& error_message);

} // namespace report

#endif
