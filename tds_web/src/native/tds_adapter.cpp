#include "tds_api.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <unordered_set>
#include <utility>
#include <vector>

#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#else
#include <cerrno>
#include <iconv.h>
#endif

namespace {

struct Endpoint {
    std::string host;
    int port = 0;
};

struct Options {
    std::vector<Endpoint> endpoints;
    std::string user;
    std::string password;
    int req_timeout_ms = 300000;
    int log_level = 2000;
    bool klg_enable = false;
    int function_no = 20100;
    std::string action;
    std::string query;
    std::string client_id;
    std::optional<int> trade_date;
};

struct FundRecord {
    int trade_date = 0;
    std::string client_id;
    std::string client_name;
    std::string fund_account_no;
    std::string currency;
    double dynamic_rights = 0.0;
    double hold_profit = 0.0;
    double available_fund = 0.0;
    double risk_degree1 = 0.0;
    double risk_degree2 = 0.0;
};

struct HoldRecord {
    int trade_date = 0;
    std::string client_id;
    std::string currency;
    std::string contract_code;
    std::string direction;
    int hold_quantity = 0;
    int today_hold_quantity = 0;
};

std::string trim(const std::string& value) {
    std::size_t begin = 0;
    while (begin < value.size() && std::isspace(static_cast<unsigned char>(value[begin])) != 0) {
        ++begin;
    }
    std::size_t end = value.size();
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1])) != 0) {
        --end;
    }
    return value.substr(begin, end - begin);
}

std::string convert_to_utf8(const std::string& input, const char* encoding) {
    if (input.empty()) {
        return "";
    }

#ifdef _WIN32
    unsigned int code_page = 936;
    if (std::strcmp(encoding, "GB18030") == 0) {
        code_page = 54936;
    }
    const int wide_length = MultiByteToWideChar(
        code_page,
        MB_ERR_INVALID_CHARS,
        input.data(),
        static_cast<int>(input.size()),
        nullptr,
        0);
    if (wide_length <= 0) {
        return "";
    }

    std::wstring wide(static_cast<std::size_t>(wide_length), L'\0');
    if (MultiByteToWideChar(
            code_page,
            MB_ERR_INVALID_CHARS,
            input.data(),
            static_cast<int>(input.size()),
            wide.data(),
            wide_length) <= 0) {
        return "";
    }

    const int utf8_length = WideCharToMultiByte(
        CP_UTF8,
        0,
        wide.data(),
        wide_length,
        nullptr,
        0,
        nullptr,
        nullptr);
    if (utf8_length <= 0) {
        return "";
    }

    std::string output(static_cast<std::size_t>(utf8_length), '\0');
    if (WideCharToMultiByte(
            CP_UTF8,
            0,
            wide.data(),
            wide_length,
            output.data(),
            utf8_length,
            nullptr,
            nullptr) <= 0) {
        return "";
    }
    return output;
#else
    iconv_t converter = iconv_open("UTF-8", encoding);
    if (converter == reinterpret_cast<iconv_t>(-1)) {
        return "";
    }

    std::vector<char> output(input.size() * 4U + 16U, '\0');
    char* input_buffer = const_cast<char*>(input.data());
    std::size_t input_remaining = input.size();
    char* output_buffer = output.data();
    std::size_t output_remaining = output.size();

    while (input_remaining > 0) {
        const std::size_t result = iconv(
            converter,
            &input_buffer,
            &input_remaining,
            &output_buffer,
            &output_remaining);
        if (result != static_cast<std::size_t>(-1)) {
            break;
        }
        if (errno != E2BIG) {
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
#endif
}

bool is_ascii(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char ch) { return ch <= 0x7F; });
}

std::string decode_vendor_text(const std::string& value) {
    const std::string cleaned = trim(value);
    if (cleaned.empty() || is_ascii(cleaned)) {
        return cleaned;
    }
    for (const char* encoding : {"GB18030", "GBK"}) {
        const std::string converted = convert_to_utf8(cleaned, encoding);
        if (!converted.empty()) {
            return converted;
        }
    }
    return cleaned;
}

std::string fixed_string(const char* raw, std::size_t max_length) {
    std::size_t length = 0;
    while (length < max_length && raw[length] != '\0') {
        ++length;
    }
    return decode_vendor_text(std::string(raw, length));
}

std::string format_error(const std::string& operation, int err_code, const char* err_msg) {
    std::ostringstream output;
    output << operation << " failed: " << err_code;
    const std::string decoded = decode_vendor_text(err_msg == nullptr ? "" : std::string(err_msg));
    if (!decoded.empty()) {
        output << ": " << decoded;
    }
    return output.str();
}

bool is_no_more_data(int err_code, const char* err_msg) {
    if (err_code == 1009) {
        return true;
    }
    const std::string decoded = decode_vendor_text(err_msg == nullptr ? "" : std::string(err_msg));
    return decoded.find("no more data") != std::string::npos ||
        decoded.find("no more result") != std::string::npos ||
        decoded.find("end of data") != std::string::npos ||
        decoded.find(u8"\u6ca1\u6709\u66f4\u591a\u6570\u636e") != std::string::npos ||
        decoded.find(u8"\u65e0\u66f4\u591a\u6570\u636e") != std::string::npos;
}

std::vector<std::string> split(const std::string& raw, char delimiter) {
    std::vector<std::string> values;
    std::string current;
    std::istringstream input(raw);
    while (std::getline(input, current, delimiter)) {
        values.push_back(trim(current));
    }
    return values;
}

std::vector<Endpoint> parse_endpoints(const std::string& raw) {
    std::vector<Endpoint> endpoints;
    for (const std::string& value : split(raw, ',')) {
        if (value.empty()) {
            continue;
        }
        const std::size_t delimiter = value.rfind(':');
        if (delimiter == std::string::npos) {
            throw std::runtime_error("invalid DRTP endpoint: " + value);
        }
        endpoints.push_back({value.substr(0, delimiter), std::stoi(value.substr(delimiter + 1))});
    }
    if (endpoints.empty()) {
        throw std::runtime_error("at least one DRTP endpoint is required");
    }
    return endpoints;
}

std::string read_arg(int argc, char** argv, int& index, const std::string& name) {
    if (index + 1 >= argc) {
        throw std::runtime_error("missing value for " + name);
    }
    ++index;
    return argv[index];
}

Options parse_options(int argc, char** argv) {
    Options options;
    const char* password = std::getenv("TDS_WEB_TDS_PASSWORD");
    options.password = password == nullptr ? "" : password;

    for (int index = 1; index < argc; ++index) {
        const std::string arg = argv[index];
        if (arg == "--drtp-endpoints") {
            options.endpoints = parse_endpoints(read_arg(argc, argv, index, arg));
        } else if (arg == "--user") {
            options.user = read_arg(argc, argv, index, arg);
        } else if (arg == "--req-timeout-ms") {
            options.req_timeout_ms = std::stoi(read_arg(argc, argv, index, arg));
        } else if (arg == "--log-level") {
            options.log_level = std::stoi(read_arg(argc, argv, index, arg));
        } else if (arg == "--klg-enable") {
            options.klg_enable = read_arg(argc, argv, index, arg) == "true";
        } else if (arg == "--function-no") {
            options.function_no = std::stoi(read_arg(argc, argv, index, arg));
        } else if (arg == "--query") {
            options.query = read_arg(argc, argv, index, arg);
        } else if (arg == "--client-id") {
            options.client_id = read_arg(argc, argv, index, arg);
        } else if (arg == "--trade-date") {
            options.trade_date = std::stoi(read_arg(argc, argv, index, arg));
        } else if (options.action.empty()) {
            options.action = arg;
        } else {
            throw std::runtime_error("unexpected argument: " + arg);
        }
    }

    if (options.action != "search" && options.action != "detail") {
        throw std::runtime_error("action must be search or detail");
    }
    if (options.user.empty()) {
        throw std::runtime_error("TDS user is required");
    }
    if (options.endpoints.empty()) {
        throw std::runtime_error("DRTP endpoints are required");
    }
    return options;
}

class TdsSession {
public:
    explicit TdsSession(const Options& options) {
        int err_code = 0;
        char err_msg[256] = {0};
        if (!TdsApi_init(
                options.req_timeout_ms,
                options.log_level,
                options.klg_enable,
                options.function_no,
                &err_code,
                err_msg)) {
            throw std::runtime_error(format_error("TdsApi_init", err_code, err_msg));
        }
        initialized_ = true;

        int registered = 0;
        for (const Endpoint& endpoint : options.endpoints) {
            std::string host = endpoint.host;
            if (TdsApi_addDrtpNode(host.data(), endpoint.port)) {
                ++registered;
            }
        }
        if (registered == 0) {
            throw std::runtime_error("TdsApi_addDrtpNode failed for all endpoints");
        }

        std::string user = options.user;
        std::string password = options.password;
        const int result = TdsApi_reqLogin(user.data(), password.data(), &err_code, err_msg);
        if (result != 0) {
            throw std::runtime_error(format_error("TdsApi_reqLogin", err_code, err_msg));
        }
    }

    ~TdsSession() {
        int err_code = 0;
        char err_msg[256] = {0};
        if (initialized_) {
            TdsApi_reqLogout(&err_code, err_msg);
            TdsApi_finalize();
        }
    }

private:
    bool initialized_ = false;
};

int resolve_trade_date(const Options& options) {
    if (options.trade_date.has_value()) {
        return options.trade_date.value();
    }
    int trade_date = 0;
    int err_code = 0;
    char err_msg[256] = {0};
    const int result = TdsApi_reqTradeDate(&trade_date, &err_code, err_msg);
    if (result != 0) {
        throw std::runtime_error(format_error("TdsApi_reqTradeDate", err_code, err_msg));
    }
    return trade_date;
}

void subscribe_client(const std::string& client_id) {
    int err_code = 0;
    char err_msg[256] = {0};
    if (!TdsApi_subscribeDataByCust(client_id.c_str(), &err_code, err_msg)) {
        throw std::runtime_error(format_error("TdsApi_subscribeDataByCust", err_code, err_msg));
    }
}

template <typename Visitor>
void each_snapshot_row(int trade_date, int table_id, const std::string& operation, Visitor visitor) {
    int err_code = 0;
    char err_msg[256] = {0};
    TDS_HANDLE handle = TdsApi_reqSnapshot(trade_date, table_id, &err_code, err_msg);
    if (handle == nullptr) {
        throw std::runtime_error(format_error("TdsApi_reqSnapshot(" + operation + ")", err_code, err_msg));
    }

    std::vector<char> buffer(64 * 1024);
    try {
        for (;;) {
            err_code = 0;
            err_msg[0] = '\0';
            if (!TdsApi_hasNext(handle, &err_code, err_msg)) {
                if (err_code == 0 || is_no_more_data(err_code, err_msg)) {
                    break;
                }
                throw std::runtime_error(format_error("TdsApi_hasNext(" + operation + ")", err_code, err_msg));
            }

            int data_type = -1;
            err_code = 0;
            err_msg[0] = '\0';
            if (!TdsApi_getNext(
                    handle,
                    &data_type,
                    buffer.data(),
                    static_cast<int>(buffer.size()),
                    &err_code,
                    err_msg)) {
                throw std::runtime_error(format_error("TdsApi_getNext(" + operation + ")", err_code, err_msg));
            }
            if (data_type == table_id) {
                visitor(buffer.data());
            }
        }
        TdsApi_closeHandle(handle);
    } catch (...) {
        TdsApi_closeHandle(handle);
        throw;
    }
}

std::vector<FundRecord> fetch_funds(int trade_date, const std::optional<std::string>& client_filter) {
    std::vector<FundRecord> records;
    each_snapshot_row(
        trade_date,
        TDS_TABLE_ID_CUST_REAL_FUND,
        "fund",
        [&](const char* data) {
            const auto* fund = reinterpret_cast<const TTds_Cust_Real_Fund*>(data);
            FundRecord record;
            record.trade_date = fund->trade_date;
            record.client_id = fixed_string(fund->cust_no, sizeof(fund->cust_no));
            record.client_name = fixed_string(fund->cust_name, sizeof(fund->cust_name));
            record.fund_account_no = fixed_string(fund->fund_account_no, sizeof(fund->fund_account_no));
            record.currency = fixed_string(fund->currency_code, sizeof(fund->currency_code));
            record.dynamic_rights = fund->dyn_rights;
            record.hold_profit = fund->hold_profit;
            record.available_fund = fund->avail_fund;
            record.risk_degree1 = fund->risk_degree1;
            record.risk_degree2 = fund->risk_degree2;
            if (!client_filter.has_value() || record.client_id == client_filter.value()) {
                records.push_back(record);
            }
        });
    return records;
}

std::vector<HoldRecord> fetch_holds(int trade_date, const std::string& client_id) {
    std::vector<HoldRecord> records;
    each_snapshot_row(
        trade_date,
        TDS_TABLE_ID_CUST_HOLD,
        "hold",
        [&](const char* data) {
            const auto* hold = reinterpret_cast<const TTds_Cust_Hold*>(data);
            HoldRecord record;
            record.trade_date = hold->tx_date;
            record.client_id = fixed_string(hold->cust_no, sizeof(hold->cust_no));
            record.currency = fixed_string(hold->currency_code, sizeof(hold->currency_code));
            record.contract_code = fixed_string(hold->contract_code, sizeof(hold->contract_code));
            record.direction = hold->bs_flag == TDS_BUY_DIRECTION ? "LONG" : "SHORT";
            record.hold_quantity = hold->hold_qty;
            record.today_hold_quantity = hold->today_hold_qty;
            if (record.client_id == client_id) {
                records.push_back(record);
            }
        });
    return records;
}

std::string json_escape(const std::string& value) {
    std::ostringstream output;
    for (char ch : value) {
        switch (ch) {
        case '\\':
            output << "\\\\";
            break;
        case '"':
            output << "\\\"";
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
            output << ch;
            break;
        }
    }
    return output.str();
}

void write_client_candidates(const std::vector<FundRecord>& records, const std::string& query) {
    std::string lower_query = query;
    std::transform(lower_query.begin(), lower_query.end(), lower_query.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });

    std::unordered_set<std::string> seen;
    std::cout << "{\"clients\":[";
    bool first = true;
    for (const FundRecord& record : records) {
        std::string id = record.client_id;
        std::string name = record.client_name;
        std::string lower_id = id;
        std::string lower_name = name;
        std::transform(lower_id.begin(), lower_id.end(), lower_id.begin(), [](unsigned char ch) {
            return static_cast<char>(std::tolower(ch));
        });
        std::transform(lower_name.begin(), lower_name.end(), lower_name.begin(), [](unsigned char ch) {
            return static_cast<char>(std::tolower(ch));
        });
        if (lower_id.find(lower_query) == std::string::npos &&
            lower_name.find(lower_query) == std::string::npos) {
            continue;
        }
        if (!seen.insert(id).second) {
            continue;
        }
        if (!first) {
            std::cout << ",";
        }
        first = false;
        std::cout << "{\"clientId\":\"" << json_escape(id) << "\",\"clientName\":\""
                  << json_escape(name) << "\"}";
    }
    std::cout << "]}";
}

void write_detail(int trade_date, const std::vector<FundRecord>& funds, const std::vector<HoldRecord>& holds) {
    std::cout << "{\"tradeDate\":" << trade_date << ",\"fundRecords\":[";
    for (std::size_t index = 0; index < funds.size(); ++index) {
        const FundRecord& record = funds[index];
        if (index > 0) {
            std::cout << ",";
        }
        std::cout << "{\"tradeDate\":" << record.trade_date
                  << ",\"clientId\":\"" << json_escape(record.client_id)
                  << "\",\"clientName\":\"" << json_escape(record.client_name)
                  << "\",\"fundAccountNo\":\"" << json_escape(record.fund_account_no)
                  << "\",\"currency\":\"" << json_escape(record.currency)
                  << "\",\"dynamicRights\":" << record.dynamic_rights
                  << ",\"holdProfit\":" << record.hold_profit
                  << ",\"availableFund\":" << record.available_fund
                  << ",\"riskDegree1\":" << record.risk_degree1
                  << ",\"riskDegree2\":" << record.risk_degree2 << "}";
    }
    std::cout << "],\"holdRecords\":[";
    for (std::size_t index = 0; index < holds.size(); ++index) {
        const HoldRecord& record = holds[index];
        if (index > 0) {
            std::cout << ",";
        }
        std::cout << "{\"tradeDate\":" << record.trade_date
                  << ",\"clientId\":\"" << json_escape(record.client_id)
                  << "\",\"currency\":\"" << json_escape(record.currency)
                  << "\",\"contractCode\":\"" << json_escape(record.contract_code)
                  << "\",\"direction\":\"" << record.direction
                  << "\",\"holdQuantity\":" << record.hold_quantity
                  << ",\"todayHoldQuantity\":" << record.today_hold_quantity << "}";
    }
    std::cout << "]}";
}

} // namespace

int main(int argc, char** argv) {
    try {
        const Options options = parse_options(argc, argv);
        TdsSession session(options);
        const int trade_date = resolve_trade_date(options);

        if (options.action == "search") {
            if (trim(options.query).empty()) {
                throw std::runtime_error("query is required");
            }
            write_client_candidates(fetch_funds(trade_date, std::nullopt), options.query);
            return 0;
        }

        if (trim(options.client_id).empty()) {
            throw std::runtime_error("client ID is required");
        }
        subscribe_client(options.client_id);
        write_detail(
            trade_date,
            fetch_funds(trade_date, options.client_id),
            fetch_holds(trade_date, options.client_id));
        return 0;
    } catch (const std::exception& ex) {
        std::cerr << ex.what() << '\n';
        return 1;
    }
}
