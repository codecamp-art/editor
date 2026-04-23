#include "text_utils.h"

#include <algorithm>
#include <cctype>
#include <sstream>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#else
#include <cerrno>
#include <iconv.h>
#endif

namespace report {
namespace {

constexpr int kTdsApiGetVersionFail = 430000101;
constexpr int kTdsApiInitFail = 430000102;
constexpr int kTdsApiCreateHandleFail = 430000103;
constexpr int kTdsApiLoginFail = 430000104;
constexpr int kTdsApiQueryFuncNoFail = 430000105;
constexpr int kTdsApiIncompatibility = 430000106;
constexpr int kTdsApiLogoutFail = 430000107;
constexpr int kTdsApiUserNotLogin = 430000108;
constexpr int kTdsApiQueryTradeDateFail = 430000109;
constexpr int kTdsApiQuerySerialFail = 430000110;
constexpr int kTdsApiQuerySnapshotFail = 430000111;
constexpr int kTdsApiSubscribeFail = 430000112;
constexpr int kTdsApiUnsubscribeFail = 430000113;
constexpr int kTdsNoMoreData = 1009;

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

bool IsAscii(const std::string& value)
{
    return std::all_of(
        value.begin(),
        value.end(),
        [](unsigned char ch) { return ch <= 0x7F; });
}

bool IsValidUtf8(const std::string& value)
{
    std::size_t index = 0;
    while (index < value.size())
    {
        const unsigned char ch = static_cast<unsigned char>(value[index]);
        std::size_t remaining = 0;

        if ((ch & 0x80U) == 0)
        {
            ++index;
            continue;
        }
        if ((ch & 0xE0U) == 0xC0U)
        {
            if (ch < 0xC2U)
            {
                return false;
            }
            remaining = 1;
        }
        else if ((ch & 0xF0U) == 0xE0U)
        {
            remaining = 2;
        }
        else if ((ch & 0xF8U) == 0xF0U)
        {
            if (ch > 0xF4U)
            {
                return false;
            }
            remaining = 3;
        }
        else
        {
            return false;
        }

        if (index + remaining >= value.size())
        {
            return false;
        }

        for (std::size_t offset = 1; offset <= remaining; ++offset)
        {
            const unsigned char continuation = static_cast<unsigned char>(value[index + offset]);
            if ((continuation & 0xC0U) != 0x80U)
            {
                return false;
            }
        }

        if (remaining == 2)
        {
            const unsigned char second = static_cast<unsigned char>(value[index + 1]);
            if ((ch == 0xE0U && second < 0xA0U) || (ch == 0xEDU && second >= 0xA0U))
            {
                return false;
            }
        }
        else if (remaining == 3)
        {
            const unsigned char second = static_cast<unsigned char>(value[index + 1]);
            if ((ch == 0xF0U && second < 0x90U) || (ch == 0xF4U && second >= 0x90U))
            {
                return false;
            }
        }

        index += remaining + 1;
    }

    return true;
}

#ifdef _WIN32
std::string ConvertCodePageToUtf8(const std::string& input, unsigned int code_page)
{
    if (input.empty())
    {
        return "";
    }

    const int wide_size = MultiByteToWideChar(
        code_page,
        MB_ERR_INVALID_CHARS,
        input.data(),
        static_cast<int>(input.size()),
        nullptr,
        0);
    if (wide_size <= 0)
    {
        return "";
    }

    std::wstring wide(static_cast<std::size_t>(wide_size), L'\0');
    if (MultiByteToWideChar(
            code_page,
            MB_ERR_INVALID_CHARS,
            input.data(),
            static_cast<int>(input.size()),
            wide.data(),
            wide_size) <= 0)
    {
        return "";
    }

    const int utf8_size = WideCharToMultiByte(
        CP_UTF8,
        0,
        wide.data(),
        wide_size,
        nullptr,
        0,
        nullptr,
        nullptr);
    if (utf8_size <= 0)
    {
        return "";
    }

    std::string utf8(static_cast<std::size_t>(utf8_size), '\0');
    if (WideCharToMultiByte(
            CP_UTF8,
            0,
            wide.data(),
            wide_size,
            utf8.data(),
            utf8_size,
            nullptr,
            nullptr) <= 0)
    {
        return "";
    }

    return utf8;
}
#else
std::string ConvertEncodingToUtf8(const std::string& input, const char* from_encoding)
{
    if (input.empty())
    {
        return "";
    }

    iconv_t converter = iconv_open("UTF-8", from_encoding);
    if (converter == reinterpret_cast<iconv_t>(-1))
    {
        return "";
    }

    std::vector<char> output(input.size() * 4U + 16U, '\0');
    char* input_buffer = const_cast<char*>(input.data());
    std::size_t input_remaining = input.size();
    char* output_buffer = output.data();
    std::size_t output_remaining = output.size();

    while (input_remaining > 0)
    {
        const std::size_t result = iconv(
            converter,
            &input_buffer,
            &input_remaining,
            &output_buffer,
            &output_remaining);
        if (result != static_cast<std::size_t>(-1))
        {
            break;
        }

        if (errno != E2BIG)
        {
            iconv_close(converter);
            return "";
        }

        const std::size_t used = static_cast<std::size_t>(output_buffer - output.data());
        output.resize(output.size() * 2U, '\0');
        output_buffer = output.data() + used;
        output_remaining = output.size() - used;
    }

    iconv_close(converter);
    return std::string(output.data(), static_cast<std::size_t>(output_buffer - output.data()));
}
#endif

std::string TryDecodeVendorText(const std::string& value)
{
    if (value.empty() || IsAscii(value) || IsValidUtf8(value))
    {
        return value;
    }

#ifdef _WIN32
    for (const unsigned int code_page : {54936U, 936U})
    {
        const std::string converted = ConvertCodePageToUtf8(value, code_page);
        if (!converted.empty() && IsValidUtf8(converted))
        {
            return converted;
        }
    }
#else
    for (const char* encoding : {"GB18030", "GBK"})
    {
        const std::string converted = ConvertEncodingToUtf8(value, encoding);
        if (!converted.empty() && IsValidUtf8(converted))
        {
            return converted;
        }
    }
#endif

    return value;
}

const char* DescribeTdsErrorCodeInternal(int error_code)
{
    switch (error_code)
    {
    case kTdsApiGetVersionFail:
        return u8"failed to query TDS API version; \u83b7\u53d6TDS API\u7248\u672c\u4fe1\u606f\u5931\u8d25";
    case kTdsApiInitFail:
        return u8"failed to initialize TDS API; \u521d\u59cb\u5316TDS API\u5931\u8d25";
    case kTdsApiCreateHandleFail:
        return u8"failed to create TDS handle; \u521b\u5efATDS\u53e5\u67c4\u5931\u8d25";
    case kTdsApiLoginFail:
        return u8"login rejected by TDS; \u7528\u6237\u767b\u5f55\u5931\u8d25";
    case kTdsApiQueryFuncNoFail:
        return u8"failed to query function number; \u67e5\u8be2\u4e3b\u529f\u80fd\u53f7\u5931\u8d25";
    case kTdsApiIncompatibility:
        return u8"vendor API version is incompatible; \u7248\u672c\u4e0d\u517c\u5bb9";
    case kTdsApiLogoutFail:
        return u8"logout failed; \u7528\u6237\u5f55\u51fa\u5931\u8d25";
    case kTdsApiUserNotLogin:
        return u8"user is not logged in; \u7528\u6237\u5f53\u524d\u672a\u767b\u5f55";
    case kTdsApiQueryTradeDateFail:
        return u8"failed to query trade date; \u67e5\u8be2\u4ea4\u6613\u65e5\u671f\u5931\u8d25";
    case kTdsApiQuerySerialFail:
        return u8"failed to query serial data; \u67e5\u8be2\u6d41\u6c34\u4fe1\u606f\u5931\u8d25";
    case kTdsApiQuerySnapshotFail:
        return u8"failed to query snapshot data; \u67e5\u8be2\u5feb\u7167\u4fe1\u606f\u5931\u8d25";
    case kTdsApiSubscribeFail:
        return u8"failed to subscribe customer data; \u8ba2\u9605\u5931\u8d25";
    case kTdsApiUnsubscribeFail:
        return u8"failed to unsubscribe customer data; \u9000\u8ba2\u5931\u8d25";
    default:
        return nullptr;
    }
}

} // namespace

std::string DecodeVendorText(const std::string& value)
{
    return TryDecodeVendorText(Trim(value));
}

std::string DescribeTdsErrorCode(int error_code)
{
    const char* description = DescribeTdsErrorCodeInternal(error_code);
    return description == nullptr ? "" : description;
}

bool IsTdsNoMoreDataResult(int error_code, const std::string& error_message)
{
    if (error_code == kTdsNoMoreData)
    {
        return true;
    }

    const std::string decoded_message = DecodeVendorText(error_message);
    if (decoded_message.empty())
    {
        return false;
    }

    for (const std::string& marker : {
             "no more data",
             "no more result",
             "end of data",
             u8"\u6ca1\u6709\u66f4\u591a\u6570\u636e",
             u8"\u65e0\u66f4\u591a\u6570\u636e",
             u8"\u65e0\u540e\u7eed\u6570\u636e",
             u8"\u6570\u636e\u83b7\u53d6\u5b8c\u6bd5"})
    {
        if (decoded_message.find(marker) != std::string::npos)
        {
            return true;
        }
    }

    return false;
}

std::string FormatTdsApiError(
    const std::string& operation,
    int error_code,
    const std::string& error_message)
{
    const std::string decoded_message = DecodeVendorText(error_message);
    const std::string description = DescribeTdsErrorCode(error_code);

    std::ostringstream output;
    output << operation << " failed: " << error_code;
    if (!description.empty())
    {
        output << " (" << description << ")";
    }
    if (!decoded_message.empty() && description.find(decoded_message) == std::string::npos)
    {
        output << ": " << decoded_message;
    }
    return output.str();
}

} // namespace report
