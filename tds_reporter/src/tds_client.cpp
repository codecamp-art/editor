#include "app.h"
#include "logging.h"
#include "text_utils.h"

#include <cctype>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>

#ifdef REPORT_HAS_VENDOR_API
#include "tds_api.h"
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

#ifdef REPORT_HAS_VENDOR_API
std::string ExtractFixedString(const char* raw, std::size_t max_length)
{
    std::size_t length = 0;
    while (length < max_length && raw[length] != '\0')
    {
        ++length;
    }
    return DecodeVendorText(std::string(raw, length));
}

std::string QueryApiVersion()
{
    char version[100] = {0};
    if (!TdsApi_getVersion(version, static_cast<int>(sizeof(version))))
    {
        return "";
    }
    return DecodeVendorText(version);
}

class VendorTdsClient final : public ITdsClient
{
public:
    explicit VendorTdsClient(const AppConfig& config) : config_(config)
    {
        InitializeSession();
    }

    ~VendorTdsClient() override
    {
        Cleanup();
    }

    int FetchTradeDate() override
    {
        LogDebug("tds_trade_date_request", "Requesting trade date from TDS");
        int trade_date = 0;
        int err_code = 0;
        char err_msg[256] = {0};
        const int result = TdsApi_reqTradeDate(&trade_date, &err_code, err_msg);
        if (result != 0)
        {
            throw std::runtime_error(FormatTdsApiError("TdsApi_reqTradeDate", err_code, err_msg));
        }
        LogInfo("tds_trade_date_response", "Received trade date from TDS", {{"trade_date", std::to_string(trade_date)}});
        return trade_date;
    }

    std::vector<CustomerFundRecord> FetchCustomerFunds(
        int trade_date,
        const std::vector<std::string>& cust_filters) override
    {
        LogInfo(
            "tds_snapshot_start",
            "Requesting customer fund snapshot",
            {
                {"trade_date", std::to_string(trade_date)},
                {"cust_filter_count", std::to_string(cust_filters.size())}
            });
        if (!cust_filters.empty())
        {
            const std::string joined = Join(cust_filters, "|");
            int err_code = 0;
            char err_msg[256] = {0};
            if (!TdsApi_subscribeDataByCust(joined.c_str(), &err_code, err_msg))
            {
                throw std::runtime_error(
                    FormatTdsApiError("TdsApi_subscribeDataByCust", err_code, err_msg));
            }
        }

        int err_code = 0;
        char err_msg[256] = {0};
        TDS_HANDLE handle = TdsApi_reqSnapshot(trade_date, TDS_TABLE_ID_CUST_REAL_FUND, &err_code, err_msg);
        if (handle == nullptr)
        {
            throw std::runtime_error(FormatTdsApiError("TdsApi_reqSnapshot", err_code, err_msg));
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
                if (!TdsApi_hasNext(handle, &err_code, err_msg))
                {
                    if (err_code == 0 || IsTdsNoMoreDataResult(err_code, err_msg))
                    {
                        break;
                    }
                    if (err_code != 0)
                    {
                        throw std::runtime_error(FormatTdsApiError("TdsApi_hasNext", err_code, err_msg));
                    }
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
                        err_msg))
                {
                    throw std::runtime_error(FormatTdsApiError("TdsApi_getNext", err_code, err_msg));
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

            TdsApi_closeHandle(handle);
            LogInfo(
                "tds_snapshot_complete",
                "Customer fund snapshot loaded",
                {
                    {"trade_date", std::to_string(trade_date)},
                    {"record_count", std::to_string(records.size())}
                });
            return records;
        }
        catch (...)
        {
            TdsApi_closeHandle(handle);
            throw;
        }
    }

private:
    std::string BuildConnectionContext(bool password_is_set) const
    {
        std::ostringstream output;
        output << "host=" << config_.tds.drtp_host
               << ", port=" << config_.tds.drtp_port
               << ", user=" << config_.tds.user
               << ", function_no=" << config_.tds.function_no
               << ", password_set=" << (password_is_set ? "yes" : "no")
               << ", req_timeout_ms=" << config_.tds.req_timeout_ms
               << ", log_level=" << config_.tds.log_level
               << ", klg_enable=" << (config_.tds.klg_enable ? "true" : "false");
        if (!api_version_.empty())
        {
            output << ", api_version=" << api_version_;
        }
        return output.str();
    }

    void InitializeSession()
    {
        LogInfo(
            "tds_session_init",
            "Initializing vendor TDS session",
            {
                {"drtp_host", config_.tds.drtp_host},
                {"drtp_port", std::to_string(config_.tds.drtp_port)},
                {"function_no", std::to_string(config_.tds.function_no)}
            });
        int err_code = 0;
        char err_msg[256] = {0};
        if (!TdsApi_init(
                config_.tds.req_timeout_ms,
                config_.tds.log_level,
                config_.tds.klg_enable,
                config_.tds.function_no,
                &err_code,
                err_msg))
        {
            throw std::runtime_error(FormatTdsApiError("TdsApi_init", err_code, err_msg));
        }
        initialized_ = true;
        api_version_ = QueryApiVersion();

        std::string drtp_host = config_.tds.drtp_host;
        if (!TdsApi_addDrtpNode(drtp_host.data(), config_.tds.drtp_port))
        {
            throw std::runtime_error(
                "TdsApi_addDrtpNode failed (" + BuildConnectionContext(false) + ")");
        }

        std::string user = config_.tds.user;
        std::string password = config_.tds.password;
        const int result = TdsApi_reqLogin(user.data(), password.data(), &err_code, err_msg);
        if (result != 0)
        {
            throw std::runtime_error(
                FormatTdsApiError("TdsApi_reqLogin", err_code, err_msg) +
                " (" + BuildConnectionContext(!password.empty()) + ")");
        }
        logged_in_ = true;
        LogInfo(
            "tds_session_ready",
            "Vendor TDS session is ready",
            {
                {"drtp_host", config_.tds.drtp_host},
                {"user", config_.tds.user},
                {"api_version", api_version_}
            });
    }

    void Cleanup() noexcept
    {
        int err_code = 0;
        char err_msg[256] = {0};

        if (logged_in_)
        {
            TdsApi_reqLogout(&err_code, err_msg);
        }
        if (initialized_)
        {
            TdsApi_finalize();
        }
        LogDebug("tds_session_cleanup", "Vendor TDS session cleaned up");
    }

    AppConfig config_;
    bool initialized_ = false;
    bool logged_in_ = false;
    std::string api_version_;
};
#endif

class StubTdsClient final : public ITdsClient
{
public:
    explicit StubTdsClient(const std::string& csv_path)
    {
        Load(csv_path);
        LogInfo("stub_client_ready", "Stub CSV client loaded", {{"stub_file", csv_path}});
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
        LogInfo("client_create", "Using stub TDS client", {{"stub_file", cli.stub_file}});
        return std::make_unique<StubTdsClient>(cli.stub_file);
    }

#ifdef REPORT_HAS_VENDOR_API
    LogInfo(
        "client_create",
        "Using live vendor TDS client",
        {
            {"drtp_host", config.tds.drtp_host},
            {"drtp_port", std::to_string(config.tds.drtp_port)},
            {"function_no", std::to_string(config.tds.function_no)}
        });
    return std::make_unique<VendorTdsClient>(config);
#else
    throw std::runtime_error(
        "live TDS client is unavailable in this build. Use --stub-file, "
        "or place the supplier files under the fixed tds directory for your platform.");
#endif
}

} // namespace report
