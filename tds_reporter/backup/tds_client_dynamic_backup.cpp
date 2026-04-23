// Historical backup only. This file is not part of the build.
// It preserves the old dynamic-loading approach via dlopen / dlsym (or LoadLibrary on Windows).
// If you ever want to revive it, you must also restore the old config field tds.library_path.
#include "app.h"

#include <cctype>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>

#include "tds_api_define.h"
#include "tds_api_struct_type.h"

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace report {
namespace {

using TdsHandle = void*;
using InitFn = bool (*)(int, int, bool, int, int*, char*);
using AddNodeFn = bool (*)(char*, int);
using FinalizeFn = void (*)();
using ReqLoginFn = int (*)(char*, char*, int*, char*);
using ReqLogoutFn = int (*)(int*, char*);
using ReqTradeDateFn = int (*)(int*, int*, char*);
using ReqSnapshotFn = TdsHandle (*)(int, int, int*, char*);
using HasNextFn = bool (*)(TdsHandle, int*, char*);
using GetNextFn = bool (*)(TdsHandle, int*, char*, int, int*, char*);
using CloseHandleFn = void (*)(TdsHandle);
using SubscribeFn = bool (*)(const char*, int*, char*);

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

std::string ExtractFixedString(const char* raw, std::size_t max_length)
{
    std::size_t length = 0;
    while (length < max_length && raw[length] != '\0')
    {
        ++length;
    }
    return Trim(std::string(raw, length));
}

double ParseDouble(const std::string& raw)
{
    return raw.empty() ? 0.0 : std::stod(raw);
}

int ParseInt(const std::string& raw)
{
    return raw.empty() ? 0 : std::stoi(raw);
}

std::vector<std::string> ParseCsvLine(const std::string& line)
{
    std::vector<std::string> values;
    std::string current;
    bool in_quotes = false;

    for (std::size_t index = 0; index < line.size(); ++index)
    {
        const char ch = line[index];
        if (ch == '"')
        {
            if (in_quotes && index + 1 < line.size() && line[index + 1] == '"')
            {
                current += '"';
                ++index;
            }
            else
            {
                in_quotes = !in_quotes;
            }
        }
        else if (ch == ',' && !in_quotes)
        {
            values.push_back(current);
            current.clear();
        }
        else
        {
            current += ch;
        }
    }

    values.push_back(current);
    return values;
}

class VendorTdsClient final : public ITdsClient
{
public:
    explicit VendorTdsClient(const AppConfig& config) : config_(config)
    {
        LoadVendorLibrary();
        InitializeSession();
    }

    ~VendorTdsClient() override
    {
        Cleanup();
    }

    int FetchTradeDate() override
    {
        int trade_date = 0;
        int err_code = 0;
        char err_msg[256] = {0};
        const int result = req_trade_date_(&trade_date, &err_code, err_msg);
        if (result != 0)
        {
            throw std::runtime_error(
                "TdsApi_reqTradeDate failed: " + std::to_string(err_code) + " " + err_msg);
        }
        return trade_date;
    }

    std::vector<CustomerFundRecord> FetchCustomerFunds(
        int trade_date,
        const std::vector<std::string>& cust_filters) override
    {
        if (!cust_filters.empty() && subscribe_ != nullptr)
        {
            const std::string joined = Join(cust_filters, "|");
            int err_code = 0;
            char err_msg[256] = {0};
            if (!subscribe_(joined.c_str(), &err_code, err_msg))
            {
                throw std::runtime_error(
                    "TdsApi_subscribeDataByCust failed: " + std::to_string(err_code) + " " + err_msg);
            }
        }
        else if (!cust_filters.empty() && subscribe_ == nullptr)
        {
            std::cerr << "subscribe function not found in vendor library; applying local filter only\n";
        }

        int err_code = 0;
        char err_msg[256] = {0};
        TdsHandle handle = req_snapshot_(trade_date, TDS_TABLE_ID_CUST_REAL_FUND, &err_code, err_msg);
        if (handle == nullptr)
        {
            throw std::runtime_error(
                "TdsApi_reqSnapshot failed: " + std::to_string(err_code) + " " + err_msg);
        }

        std::unordered_set<std::string> filter_set(cust_filters.begin(), cust_filters.end());
        std::vector<CustomerFundRecord> records;
        std::vector<char> buffer(64 * 1024);

        try
        {
            for (;;)
            {
                err_code = 0;
                err_msg[0] = '\0';
                if (!has_next_(handle, &err_code, err_msg))
                {
                    if (err_code != 0)
                    {
                        throw std::runtime_error(
                            "TdsApi_hasNext failed: " + std::to_string(err_code) + " " + err_msg);
                    }
                    break;
                }

                int data_type = -1;
                err_code = 0;
                err_msg[0] = '\0';
                if (!get_next_(handle, &data_type, buffer.data(), static_cast<int>(buffer.size()), &err_code, err_msg))
                {
                    throw std::runtime_error(
                        "TdsApi_getNext failed: " + std::to_string(err_code) + " " + err_msg);
                }

                if (data_type != TDS_TABLE_ID_CUST_REAL_FUND)
                {
                    continue;
                }

                const auto* fund = reinterpret_cast<const TTds_Cust_Real_Fund*>(buffer.data());
                CustomerFundRecord record;
                record.trade_date = fund->trade_date;
                record.cust_no = ExtractFixedString(fund->cust_no, sizeof(fund->cust_no));
                record.cust_name = ExtractFixedString(fund->cust_name, sizeof(fund->cust_name));
                record.fund_account_no =
                    ExtractFixedString(fund->fund_account_no, sizeof(fund->fund_account_no));
                record.currency_code =
                    ExtractFixedString(fund->currency_code, sizeof(fund->currency_code));
                record.dyn_rights = fund->dyn_rights;
                record.hold_profit = fund->hold_profit;
                record.avail_fund = fund->avail_fund;
                record.risk_degree1 = fund->risk_degree1;
                record.risk_degree2 = fund->risk_degree2;

                if (filter_set.empty() || filter_set.count(record.cust_no) > 0)
                {
                    records.push_back(record);
                }
            }

            close_handle_(handle);
            return records;
        }
        catch (...)
        {
            close_handle_(handle);
            throw;
        }
    }

private:
    template <typename T>
    T TryLoadSymbol(const char* name)
    {
#ifdef _WIN32
        return reinterpret_cast<T>(GetProcAddress(static_cast<HMODULE>(library_handle_), name));
#else
        return reinterpret_cast<T>(dlsym(library_handle_, name));
#endif
    }

    template <typename T>
    T LoadSymbol(const char* primary_name, const char* fallback_name = nullptr)
    {
        T symbol = TryLoadSymbol<T>(primary_name);
        if (symbol == nullptr && fallback_name != nullptr)
        {
            symbol = TryLoadSymbol<T>(fallback_name);
        }
        if (symbol == nullptr)
        {
            throw std::runtime_error(std::string("failed to resolve symbol: ") + primary_name);
        }
        return symbol;
    }

    void LoadVendorLibrary()
    {
        const std::string library_path = config_.tds.library_path.empty()
#ifdef _WIN32
            ? "tds_api.dll"
#else
            ? "tds_api.so"
#endif
            : config_.tds.library_path;

#ifdef _WIN32
        library_handle_ = ::LoadLibraryA(library_path.c_str());
        if (library_handle_ == nullptr)
        {
            throw std::runtime_error(
                "failed to load vendor library: " + library_path + ", error=" +
                std::to_string(GetLastError()));
        }
#else
        library_handle_ = dlopen(library_path.c_str(), RTLD_LAZY);
        if (library_handle_ == nullptr)
        {
            throw std::runtime_error("failed to load vendor library: " + library_path + ", error=" + dlerror());
        }
#endif

        init_ = LoadSymbol<InitFn>("TdsApi_init");
        add_node_ = LoadSymbol<AddNodeFn>("TdsApi_addTrtpNode", "TdsApi_addDrtpNode");
        finalize_ = LoadSymbol<FinalizeFn>("TdsApi_finalize");
        req_login_ = LoadSymbol<ReqLoginFn>("TdsApi_reqLogin");
        req_logout_ = LoadSymbol<ReqLogoutFn>("TdsApi_reqLogout");
        req_trade_date_ = LoadSymbol<ReqTradeDateFn>("TdsApi_reqTradeDate");
        req_snapshot_ = LoadSymbol<ReqSnapshotFn>("TdsApi_reqSnapshot");
        has_next_ = LoadSymbol<HasNextFn>("TdsApi_hasNext");
        get_next_ = LoadSymbol<GetNextFn>("TdsApi_getNext");
        close_handle_ = LoadSymbol<CloseHandleFn>("TdsApi_closeHandle");
        subscribe_ = TryLoadSymbol<SubscribeFn>("TdsApi_subscribeDataByCust");
    }

    void InitializeSession()
    {
        int err_code = 0;
        char err_msg[256] = {0};
        if (!init_(
                config_.tds.req_timeout_ms,
                config_.tds.log_level,
                config_.tds.klg_enable,
                config_.tds.function_no,
                &err_code,
                err_msg))
        {
            throw std::runtime_error(
                "TdsApi_init failed: " + std::to_string(err_code) + " " + err_msg);
        }
        initialized_ = true;

        std::string drtp_host = config_.tds.drtp_host;
        if (!add_node_(drtp_host.data(), config_.tds.drtp_port))
        {
            throw std::runtime_error("TdsApi_addTrtpNode failed");
        }

        std::string user = config_.tds.user;
        std::string password = config_.tds.password;
        const int result = req_login_(user.data(), password.data(), &err_code, err_msg);
        if (result != 0)
        {
            throw std::runtime_error(
                "TdsApi_reqLogin failed: " + std::to_string(err_code) + " " + err_msg);
        }
        logged_in_ = true;
    }

    void Cleanup() noexcept
    {
        int err_code = 0;
        char err_msg[256] = {0};

        if (logged_in_ && req_logout_ != nullptr)
        {
            req_logout_(&err_code, err_msg);
        }
        if (initialized_ && finalize_ != nullptr)
        {
            finalize_();
        }

#ifdef _WIN32
        if (library_handle_ != nullptr)
        {
            FreeLibrary(static_cast<HMODULE>(library_handle_));
        }
#else
        if (library_handle_ != nullptr)
        {
            dlclose(library_handle_);
        }
#endif
    }

    AppConfig config_;
    void* library_handle_ = nullptr;
    bool initialized_ = false;
    bool logged_in_ = false;
    InitFn init_ = nullptr;
    AddNodeFn add_node_ = nullptr;
    FinalizeFn finalize_ = nullptr;
    ReqLoginFn req_login_ = nullptr;
    ReqLogoutFn req_logout_ = nullptr;
    ReqTradeDateFn req_trade_date_ = nullptr;
    ReqSnapshotFn req_snapshot_ = nullptr;
    HasNextFn has_next_ = nullptr;
    GetNextFn get_next_ = nullptr;
    CloseHandleFn close_handle_ = nullptr;
    SubscribeFn subscribe_ = nullptr;
};

class StubTdsClient final : public ITdsClient
{
public:
    explicit StubTdsClient(const std::string& csv_path)
    {
        Load(csv_path);
    }

    int FetchTradeDate() override
    {
        return records_.empty() ? 0 : records_.front().trade_date;
    }

    std::vector<CustomerFundRecord> FetchCustomerFunds(
        int,
        const std::vector<std::string>& cust_filters) override
    {
        if (cust_filters.empty())
        {
            return records_;
        }

        std::unordered_set<std::string> filters(cust_filters.begin(), cust_filters.end());
        std::vector<CustomerFundRecord> filtered;
        for (const CustomerFundRecord& record : records_)
        {
            if (filters.count(record.cust_no) > 0)
            {
                filtered.push_back(record);
            }
        }
        return filtered;
    }

private:
    void Load(const std::string& csv_path)
    {
        std::ifstream input(csv_path);
        if (!input.is_open())
        {
            throw std::runtime_error("failed to open stub csv: " + csv_path);
        }

        std::string header_line;
        if (!std::getline(input, header_line))
        {
            throw std::runtime_error("stub csv is empty: " + csv_path);
        }

        const std::vector<std::string> headers = ParseCsvLine(header_line);
        std::unordered_map<std::string, std::size_t> index_by_name;
        for (std::size_t index = 0; index < headers.size(); ++index)
        {
            index_by_name[Trim(headers[index])] = index;
        }

        auto required_index = [&](const std::string& column) -> std::size_t {
            const auto it = index_by_name.find(column);
            if (it == index_by_name.end())
            {
                throw std::runtime_error("missing stub csv column: " + column);
            }
            return it->second;
        };

        const std::size_t trade_date_index = required_index("trade_date");
        const std::size_t cust_no_index = required_index("cust_no");
        const std::size_t cust_name_index = required_index("cust_name");
        const std::size_t fund_account_index = required_index("fund_account_no");
        const std::size_t currency_index = required_index("currency_code");
        const std::size_t dyn_rights_index = required_index("dyn_rights");
        const std::size_t hold_profit_index = required_index("hold_profit");
        const std::size_t avail_fund_index = required_index("avail_fund");
        const std::size_t risk1_index = required_index("risk_degree1");
        const std::size_t risk2_index = required_index("risk_degree2");

        std::string line;
        while (std::getline(input, line))
        {
            if (Trim(line).empty())
            {
                continue;
            }

            const std::vector<std::string> values = ParseCsvLine(line);
            CustomerFundRecord record;
            record.trade_date = ParseInt(values.at(trade_date_index));
            record.cust_no = values.at(cust_no_index);
            record.cust_name = values.at(cust_name_index);
            record.fund_account_no = values.at(fund_account_index);
            record.currency_code = values.at(currency_index);
            record.dyn_rights = ParseDouble(values.at(dyn_rights_index));
            record.hold_profit = ParseDouble(values.at(hold_profit_index));
            record.avail_fund = ParseDouble(values.at(avail_fund_index));
            record.risk_degree1 = ParseDouble(values.at(risk1_index));
            record.risk_degree2 = ParseDouble(values.at(risk2_index));
            records_.push_back(record);
        }
    }

    std::vector<CustomerFundRecord> records_;
};

} // namespace

std::unique_ptr<ITdsClient> CreateClient(const AppConfig& config, const CliOptions& cli)
{
    if (!cli.stub_file.empty())
    {
        return std::make_unique<StubTdsClient>(cli.stub_file);
    }
    return std::make_unique<VendorTdsClient>(config);
}

} // namespace report
