#include "app.h"

#include <iostream>
#include <set>

namespace {

void PrintUsage()
{
    std::cout
        << "Usage:\n"
        << "  tds_reporter --env dev [--config path] [--to a@x.com,b@y.com] [--cc c@x.com]\n"
        << "               [--cust-list 1001,1002] [--output-dir path] [--trade-date YYYYMMDD]\n"
        << "               [--dry-run] [--stub-file path]\n\n"
        << "Notes:\n"
        << "  --stub-file lets you debug on Windows without the vendor RHEL8 .so file.\n"
        << "  --dry-run writes an .eml preview instead of calling curl SMTP.\n";
}

std::size_t CountUniqueCustomers(const std::vector<tds_reporter::CustomerFundRecord>& records)
{
    std::set<std::string> cust_numbers;
    for (const auto& record : records)
    {
        cust_numbers.insert(record.cust_no);
    }
    return cust_numbers.size();
}

} // namespace

int main(int argc, char** argv)
{
    try
    {
        const tds_reporter::CliOptions cli = tds_reporter::ParseCli(argc, argv);
        if (cli.help)
        {
            PrintUsage();
            return 0;
        }

        const std::string config_path =
            cli.config_path.empty() ? tds_reporter::DefaultConfigPath(cli.env) : cli.config_path;
        const tds_reporter::AppConfig config = tds_reporter::LoadConfig(config_path, cli);
        std::unique_ptr<tds_reporter::ITdsClient> client = tds_reporter::CreateClient(config, cli);

        const int trade_date = cli.trade_date_override > 0 ? cli.trade_date_override : client->FetchTradeDate();
        const std::vector<tds_reporter::CustomerFundRecord> records =
            client->FetchCustomerFunds(trade_date, cli.cust_filters);

        const std::string report_path = tds_reporter::WriteCsvReport(records, config, trade_date);
        const tds_reporter::MailRequest mail_request =
            tds_reporter::BuildMailRequest(records, config, cli, trade_date, report_path);
        const tds_reporter::SendMailResult mail_result =
            tds_reporter::SendMailWithCurl(mail_request, config, cli.dry_run);

        std::cout << "Environment: " << config.env_name << '\n';
        std::cout << "Trade date: " << trade_date << '\n';
        std::cout << "Snapshot rows: " << records.size() << '\n';
        std::cout << "Unique customers: " << CountUniqueCustomers(records) << '\n';
        std::cout << "CSV report: " << report_path << '\n';
        if (mail_result.sent)
        {
            std::cout << "Email status: sent\n";
        }
        else
        {
            std::cout << "Email status: dry-run preview generated\n";
            std::cout << "Preview file: " << mail_result.preview_path << '\n';
        }

        return 0;
    }
    catch (const std::exception& ex)
    {
        std::cerr << "tds_reporter failed: " << ex.what() << '\n';
        return 1;
    }
}
