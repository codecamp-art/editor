#ifndef REPORT_APP_H
#define REPORT_APP_H

#include <memory>
#include <string>
#include <vector>

namespace report {

struct CustomerFundRecord
{
    int trade_date = 0;
    std::string cust_no;
    std::string cust_name;
    std::string fund_account_no;
    double dyn_rights = 0.0;
    double hold_profit = 0.0;
    double avail_fund = 0.0;
    double risk_degree1 = 0.0;
    double risk_degree2 = 0.0;
};

struct TdsEndpoint
{
    std::string host;
    int port = 0;
};

struct TdsConnectionConfig
{
    std::vector<TdsEndpoint> drtp_endpoints;
    std::string user;
    std::string password;
    int req_timeout_ms = 300000;
    int log_level = 2000;
    bool klg_enable = false;
    int function_no = 20100;
};

struct SmtpConfig
{
    std::string host;
    int port = 25;
    std::string from;
    std::string security = "plain";
    std::string client_cert_path;
    std::string client_key_path;
    std::string client_cert_type = "PEM";
    std::string client_key_type = "PEM";
    std::string client_key_password;
    bool insecure = false;
    int connect_timeout_seconds = 15;
};

struct LogConfig
{
    std::string directory = "./logs";
    std::string level = "info";
};

struct VaultConfig
{
    std::string curl_executable;
    std::string address;
    std::string namespace_name;
    std::string auth_path;
};

struct AppConfig
{
    std::string env_name = "dev";
    std::string email_subject = "TDS Client Funding and Risk Ratio Report";
    std::string attachment_name = "tds_customer_funds";
    std::string output_dir = "./output";
    std::vector<std::string> default_to;
    std::vector<std::string> default_cc;
    TdsConnectionConfig tds;
    SmtpConfig smtp;
    LogConfig log;
    VaultConfig vault;
};

struct CliOptions
{
    bool help = false;
    bool dry_run = false;
    std::string env;
    std::string config_path;
    std::string drtp_endpoints;
    std::vector<std::string> to;
    std::vector<std::string> cc;
    std::vector<std::string> cust_filters;
    std::string output_dir;
    std::string stub_file;
    int trade_date_override = 0;
};

struct MailRequest
{
    std::string from;
    std::vector<std::string> to;
    std::vector<std::string> cc;
    std::string subject;
    std::string html_body;
    std::string attachment_path;
    std::string attachment_name;
};

struct SendMailResult
{
    bool sent = false;
    std::string preview_path;
};

class ITdsClient
{
public:
    virtual ~ITdsClient() = default;
    virtual int FetchTradeDate() = 0;
    virtual std::vector<CustomerFundRecord> FetchCustomerFunds(
        int trade_date,
        const std::vector<std::string>& cust_filters) = 0;
};

CliOptions ParseCli(int argc, char** argv);
std::string DefaultConfigPath(const std::string& env_name);
AppConfig LoadConfig(const std::string& path, const CliOptions& cli);
std::vector<std::string> SplitList(const std::string& raw);
std::string Join(const std::vector<std::string>& values, const std::string& delimiter);
std::unique_ptr<ITdsClient> CreateClient(const AppConfig& config, const CliOptions& cli);
std::string WriteCsvReport(const std::vector<CustomerFundRecord>& records, const AppConfig& config, int trade_date);
MailRequest BuildMailRequest(
    const std::vector<CustomerFundRecord>& records,
    const AppConfig& config,
    const CliOptions& cli,
    int trade_date,
    const std::string& attachment_path);
std::string BuildMimeMessage(const MailRequest& request);
std::string BuildCurlConfig(const MailRequest& request, const AppConfig& config, const std::string& message_path);
SendMailResult SendMailWithCurl(const MailRequest& request, const AppConfig& config, bool dry_run);

} // namespace report

#endif
