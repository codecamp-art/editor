#include "app.h"

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <set>
#include <sstream>
#include <stdexcept>
#include <tuple>
#include <unordered_map>

namespace tds_reporter {
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

std::string ToLower(std::string value)
{
    std::transform(
        value.begin(),
        value.end(),
        value.begin(),
        [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
    return value;
}

bool IsAscii(const std::string& value)
{
    return std::all_of(
        value.begin(),
        value.end(),
        [](unsigned char ch) { return ch <= 0x7F; });
}

std::string ExpandEnvReference(const std::string& value)
{
    if (value.size() < 4 || value[0] != '$' || value[1] != '{' || value.back() != '}')
    {
        return value;
    }

    const std::string body = value.substr(2, value.size() - 3);
    const std::size_t delimiter = body.find(':');
    const std::string key = delimiter == std::string::npos ? body : body.substr(0, delimiter);
    const std::string fallback = delimiter == std::string::npos ? "" : body.substr(delimiter + 1);
    const char* env_value = std::getenv(key.c_str());

    if (env_value == nullptr || env_value[0] == '\0')
    {
        return fallback;
    }

    return env_value;
}

std::unordered_map<std::string, std::string> ReadProperties(const std::string& path)
{
    std::ifstream input(path);
    if (!input.is_open())
    {
        throw std::runtime_error("failed to open config file: " + path);
    }

    std::unordered_map<std::string, std::string> properties;
    std::string line;

    while (std::getline(input, line))
    {
        const std::string trimmed = Trim(line);
        if (trimmed.empty() || trimmed[0] == '#')
        {
            continue;
        }

        const std::size_t pos = trimmed.find('=');
        if (pos == std::string::npos)
        {
            throw std::runtime_error("invalid config line: " + trimmed);
        }

        const std::string key = Trim(trimmed.substr(0, pos));
        const std::string value = ExpandEnvReference(Trim(trimmed.substr(pos + 1)));
        properties[key] = value;
    }

    return properties;
}

std::string GetValue(
    const std::unordered_map<std::string, std::string>& properties,
    const std::string& key,
    const std::string& fallback = "")
{
    const auto it = properties.find(key);
    if (it == properties.end())
    {
        return fallback;
    }
    return it->second;
}

std::string GetRequiredValue(
    const std::unordered_map<std::string, std::string>& properties,
    const std::string& key)
{
    const std::string value = GetValue(properties, key);
    if (value.empty())
    {
        throw std::runtime_error("missing required config key: " + key);
    }
    return value;
}

int ParseIntValue(const std::string& raw, const std::string& field_name)
{
    try
    {
        return std::stoi(raw);
    }
    catch (const std::exception&)
    {
        throw std::runtime_error("invalid integer for " + field_name + ": " + raw);
    }
}

bool ParseBoolValue(const std::string& raw, const std::string& field_name)
{
    const std::string normalized = ToLower(Trim(raw));
    if (normalized == "true" || normalized == "1" || normalized == "yes" || normalized == "y")
    {
        return true;
    }
    if (normalized == "false" || normalized == "0" || normalized == "no" || normalized == "n")
    {
        return false;
    }
    throw std::runtime_error("invalid boolean for " + field_name + ": " + raw);
}

std::string CurrentTimestamp()
{
    const auto now = std::chrono::system_clock::now();
    const std::time_t now_time = std::chrono::system_clock::to_time_t(now);
    std::tm time_info {};
#ifdef _WIN32
    localtime_s(&time_info, &now_time);
#else
    localtime_r(&now_time, &time_info);
#endif

    std::ostringstream output;
    output << std::put_time(&time_info, "%Y%m%d_%H%M%S");
    return output.str();
}

std::string FormatDouble(double value)
{
    std::ostringstream output;
    output << std::fixed << std::setprecision(6) << value;
    std::string text = output.str();

    while (!text.empty() && text.back() == '0')
    {
        text.pop_back();
    }
    if (!text.empty() && text.back() == '.')
    {
        text.pop_back();
    }
    if (text.empty())
    {
        return "0";
    }
    return text;
}

std::string EscapeCsv(const std::string& value)
{
    if (value.find_first_of(",\"\n\r") == std::string::npos)
    {
        return value;
    }

    std::string escaped = "\"";
    for (const char ch : value)
    {
        if (ch == '"')
        {
            escaped += "\"\"";
        }
        else
        {
            escaped += ch;
        }
    }
    escaped += "\"";
    return escaped;
}

std::string EscapeHtml(const std::string& value)
{
    std::string escaped;
    escaped.reserve(value.size());

    for (const char ch : value)
    {
        switch (ch)
        {
        case '&':
            escaped += "&amp;";
            break;
        case '<':
            escaped += "&lt;";
            break;
        case '>':
            escaped += "&gt;";
            break;
        case '"':
            escaped += "&quot;";
            break;
        case '\'':
            escaped += "&#39;";
            break;
        default:
            escaped += ch;
            break;
        }
    }

    return escaped;
}

std::string Base64Encode(const std::string& input)
{
    static const char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        "abcdefghijklmnopqrstuvwxyz"
        "0123456789+/";

    std::string encoded;
    encoded.reserve(((input.size() + 2) / 3) * 4);

    for (std::size_t index = 0; index < input.size(); index += 3)
    {
        const unsigned int octet_a = static_cast<unsigned char>(input[index]);
        const unsigned int octet_b =
            index + 1 < input.size() ? static_cast<unsigned char>(input[index + 1]) : 0;
        const unsigned int octet_c =
            index + 2 < input.size() ? static_cast<unsigned char>(input[index + 2]) : 0;
        const unsigned int triple = (octet_a << 16U) | (octet_b << 8U) | octet_c;

        encoded += kAlphabet[(triple >> 18U) & 0x3FU];
        encoded += kAlphabet[(triple >> 12U) & 0x3FU];
        encoded += index + 1 < input.size() ? kAlphabet[(triple >> 6U) & 0x3FU] : '=';
        encoded += index + 2 < input.size() ? kAlphabet[triple & 0x3FU] : '=';
    }

    return encoded;
}

std::string WrapBase64(const std::string& encoded)
{
    std::ostringstream output;
    for (std::size_t index = 0; index < encoded.size(); index += 76)
    {
        output << encoded.substr(index, 76) << "\r\n";
    }
    return output.str();
}

std::string EncodeMimeHeader(const std::string& value)
{
    if (IsAscii(value))
    {
        return value;
    }

    return "=?UTF-8?B?" + Base64Encode(value) + "?=";
}

std::string ReadBinaryFile(const std::string& path)
{
    std::ifstream input(path, std::ios::binary);
    if (!input.is_open())
    {
        throw std::runtime_error("failed to open attachment: " + path);
    }

    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

std::string QuoteForShell(const std::string& value)
{
#ifdef _WIN32
    std::string quoted = "\"";
    for (const char ch : value)
    {
        if (ch == '"')
        {
            quoted += "\\\"";
        }
        else
        {
            quoted += ch;
        }
    }
    quoted += "\"";
    return quoted;
#else
    std::string quoted = "'";
    for (const char ch : value)
    {
        if (ch == '\'')
        {
            quoted += "'\\''";
        }
        else
        {
            quoted += ch;
        }
    }
    quoted += "'";
    return quoted;
#endif
}

std::string EscapeCurlConfigValue(const std::string& value)
{
    std::string escaped;
    escaped.reserve(value.size());

    for (const char ch : value)
    {
        if (ch == '\\' || ch == '"')
        {
            escaped += '\\';
        }
        escaped += ch;
    }

    return escaped;
}

std::string ResolveConfigRelativePath(
    const std::filesystem::path& config_dir,
    const std::string& raw_path)
{
    if (raw_path.empty())
    {
        return raw_path;
    }

    if (!raw_path.empty() && (raw_path[0] == '/' || raw_path[0] == '\\'))
    {
        return raw_path;
    }

    const std::filesystem::path path_value(raw_path);
    if (path_value.is_absolute())
    {
        return path_value.string();
    }

    return std::filesystem::absolute(config_dir / path_value).string();
}

std::vector<CustomerFundRecord> SortRecords(std::vector<CustomerFundRecord> records)
{
    std::sort(
        records.begin(),
        records.end(),
        [](const CustomerFundRecord& left, const CustomerFundRecord& right) {
            return std::tie(left.cust_no, left.fund_account_no, left.currency_code) <
                   std::tie(right.cust_no, right.fund_account_no, right.currency_code);
        });
    return records;
}

std::vector<std::string> EffectiveRecipients(
    const std::vector<std::string>& cli_recipients,
    const std::vector<std::string>& config_recipients)
{
    return cli_recipients.empty() ? config_recipients : cli_recipients;
}

std::string BuildHtmlBody(const std::vector<CustomerFundRecord>& records, const AppConfig& config, int trade_date)
{
    const std::vector<CustomerFundRecord> sorted = SortRecords(records);
    std::set<std::string> customer_numbers;
    double total_dyn_rights = 0.0;
    double total_hold_profit = 0.0;
    double total_avail_fund = 0.0;

    for (const CustomerFundRecord& record : sorted)
    {
        customer_numbers.insert(record.cust_no);
        total_dyn_rights += record.dyn_rights;
        total_hold_profit += record.hold_profit;
        total_avail_fund += record.avail_fund;
    }

    std::ostringstream output;
    output << "<html><body style=\"font-family:Segoe UI,Arial,sans-serif;\">";
    output << "<h2>TDS customer fund snapshot</h2>";
    output << "<p>Environment: <b>" << EscapeHtml(config.env_name) << "</b><br/>";
    output << "Trade date: <b>" << trade_date << "</b><br/>";
    output << "Generated at: <b>" << EscapeHtml(CurrentTimestamp()) << "</b></p>";
    output << "<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;\">";
    output << "<tr><th>Metric</th><th>Value</th></tr>";
    output << "<tr><td>Snapshot rows</td><td>" << sorted.size() << "</td></tr>";
    output << "<tr><td>Unique customers</td><td>" << customer_numbers.size() << "</td></tr>";
    output << "<tr><td>Total dynamic rights</td><td>" << FormatDouble(total_dyn_rights) << "</td></tr>";
    output << "<tr><td>Total floating PnL</td><td>" << FormatDouble(total_hold_profit) << "</td></tr>";
    output << "<tr><td>Total available fund</td><td>" << FormatDouble(total_avail_fund) << "</td></tr>";
    output << "</table>";

    if (sorted.empty())
    {
        output << "<p>No customer fund rows were returned by the TDS snapshot.</p>";
    }
    else
    {
        output << "<p>Attachment contains the full CSV details. All customer rows are listed below:</p>";
        output << "<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\" style=\"border-collapse:collapse;\">";
        output << "<tr><th>Cust No</th><th>Name</th><th>Fund Account</th><th>Currency</th>"
               << "<th>Dyn Rights</th><th>Hold Profit</th><th>Avail Fund</th>"
               << "<th>Risk 1</th><th>Risk 2</th></tr>";

        for (std::size_t index = 0; index < sorted.size(); ++index)
        {
            const CustomerFundRecord& record = sorted[index];
            output << "<tr>"
                   << "<td>" << EscapeHtml(record.cust_no) << "</td>"
                   << "<td>" << EscapeHtml(record.cust_name) << "</td>"
                   << "<td>" << EscapeHtml(record.fund_account_no) << "</td>"
                   << "<td>" << EscapeHtml(record.currency_code) << "</td>"
                   << "<td>" << FormatDouble(record.dyn_rights) << "</td>"
                   << "<td>" << FormatDouble(record.hold_profit) << "</td>"
                   << "<td>" << FormatDouble(record.avail_fund) << "</td>"
                   << "<td>" << FormatDouble(record.risk_degree1) << "</td>"
                   << "<td>" << FormatDouble(record.risk_degree2) << "</td>"
                   << "</tr>";
        }

        output << "</table>";
    }

    output << "<p>This email was generated automatically by tds_reporter.</p>";
    output << "</body></html>";
    return output.str();
}

} // namespace

std::vector<std::string> SplitList(const std::string& raw)
{
    std::vector<std::string> values;
    std::string current;

    for (const char ch : raw)
    {
        if (ch == ',' || ch == ';')
        {
            const std::string trimmed = Trim(current);
            if (!trimmed.empty())
            {
                values.push_back(trimmed);
            }
            current.clear();
        }
        else
        {
            current += ch;
        }
    }

    const std::string trimmed = Trim(current);
    if (!trimmed.empty())
    {
        values.push_back(trimmed);
    }

    return values;
}

std::string Join(const std::vector<std::string>& values, const std::string& delimiter)
{
    std::ostringstream output;
    for (std::size_t index = 0; index < values.size(); ++index)
    {
        if (index > 0)
        {
            output << delimiter;
        }
        output << values[index];
    }
    return output.str();
}

CliOptions ParseCli(int argc, char** argv)
{
    CliOptions options;

    auto read_value = [&](const std::string& current_arg, const std::string& name, int& index) -> std::string {
        const std::string prefix = name + "=";
        if (current_arg.rfind(prefix, 0) == 0)
        {
            return current_arg.substr(prefix.size());
        }
        if (current_arg == name)
        {
            if (index + 1 >= argc)
            {
                throw std::runtime_error("missing value for " + name);
            }
            ++index;
            return argv[index];
        }
        return "";
    };

    for (int index = 1; index < argc; ++index)
    {
        const std::string arg = argv[index];

        if (arg == "--help" || arg == "-h")
        {
            options.help = true;
            continue;
        }
        if (arg == "--dry-run")
        {
            options.dry_run = true;
            continue;
        }

        if (const std::string value = read_value(arg, "--env", index); !value.empty())
        {
            options.env = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--config", index); !value.empty())
        {
            options.config_path = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--to", index); !value.empty())
        {
            options.to = SplitList(value);
            continue;
        }
        if (const std::string value = read_value(arg, "--cc", index); !value.empty())
        {
            options.cc = SplitList(value);
            continue;
        }
        if (const std::string value = read_value(arg, "--cust-list", index); !value.empty())
        {
            options.cust_filters = SplitList(value);
            continue;
        }
        if (const std::string value = read_value(arg, "--output-dir", index); !value.empty())
        {
            options.output_dir = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--stub-file", index); !value.empty())
        {
            options.stub_file = value;
            continue;
        }
        if (const std::string value = read_value(arg, "--trade-date", index); !value.empty())
        {
            options.trade_date_override = ParseIntValue(value, "--trade-date");
            continue;
        }

        throw std::runtime_error("unknown argument: " + arg);
    }

    return options;
}

std::string DefaultConfigPath(const std::string& env_name)
{
    return "tds_reporter/config/" + env_name + ".properties";
}

AppConfig LoadConfig(const std::string& path, const CliOptions& cli)
{
    const std::filesystem::path config_dir = std::filesystem::absolute(path).parent_path();
    const auto properties = ReadProperties(path);
    AppConfig config;

    config.env_name = cli.env.empty() ? GetValue(properties, "env.name", "dev") : cli.env;
    config.email_subject = GetValue(properties, "email.subject", config.email_subject);
    config.attachment_name = GetValue(properties, "report.attachment_name", config.attachment_name);
    config.output_dir = cli.output_dir.empty()
        ? ResolveConfigRelativePath(config_dir, GetValue(properties, "report.output_dir", config.output_dir))
        : cli.output_dir;
    config.default_to = SplitList(GetValue(properties, "email.default_to"));
    config.default_cc = SplitList(GetValue(properties, "email.default_cc"));

    config.tds.drtp_host = GetRequiredValue(properties, "tds.drtp_host");
    config.tds.drtp_port = ParseIntValue(
        GetValue(properties, "tds.drtp_port", "0"),
        "tds.drtp_port");
    config.tds.user = GetRequiredValue(properties, "tds.user");
    config.tds.password = GetValue(properties, "tds.password");
    config.tds.req_timeout_ms = ParseIntValue(
        GetValue(properties, "tds.req_timeout_ms", std::to_string(config.tds.req_timeout_ms)),
        "tds.req_timeout_ms");
    config.tds.log_level = ParseIntValue(
        GetValue(properties, "tds.log_level", std::to_string(config.tds.log_level)),
        "tds.log_level");
    config.tds.klg_enable = ParseBoolValue(
        GetValue(properties, "tds.klg_enable", config.tds.klg_enable ? "true" : "false"),
        "tds.klg_enable");
    config.tds.function_no = ParseIntValue(
        GetValue(properties, "tds.function_no", std::to_string(config.tds.function_no)),
        "tds.function_no");

    config.smtp.host = GetRequiredValue(properties, "smtp.host");
    config.smtp.port = ParseIntValue(
        GetValue(properties, "smtp.port", std::to_string(config.smtp.port)),
        "smtp.port");
    config.smtp.username = GetValue(properties, "smtp.username");
    config.smtp.password = GetValue(properties, "smtp.password");
    config.smtp.from = GetRequiredValue(properties, "smtp.from");
    config.smtp.security = ToLower(GetValue(properties, "smtp.security", config.smtp.security));
    config.smtp.client_cert_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "smtp.client_cert_path"));
    config.smtp.client_key_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "smtp.client_key_path"));
    config.smtp.client_cert_type = GetValue(properties, "smtp.client_cert_type", config.smtp.client_cert_type);
    config.smtp.client_key_type = GetValue(properties, "smtp.client_key_type", config.smtp.client_key_type);
    config.smtp.client_key_password = GetValue(properties, "smtp.client_key_password");
    config.smtp.insecure = ParseBoolValue(
        GetValue(properties, "smtp.insecure", config.smtp.insecure ? "true" : "false"),
        "smtp.insecure");
    config.smtp.connect_timeout_seconds = ParseIntValue(
        GetValue(
            properties,
            "smtp.connect_timeout_seconds",
            std::to_string(config.smtp.connect_timeout_seconds)),
        "smtp.connect_timeout_seconds");

    return config;
}

std::string WriteCsvReport(const std::vector<CustomerFundRecord>& records, const AppConfig& config, int trade_date)
{
    std::filesystem::create_directories(config.output_dir);

    const std::string filename =
        config.attachment_name + "_" + config.env_name + "_" + std::to_string(trade_date) + ".csv";
    const std::filesystem::path output_path =
        std::filesystem::absolute(std::filesystem::path(config.output_dir) / filename);
    std::ofstream output(output_path);

    if (!output.is_open())
    {
        throw std::runtime_error("failed to create csv report: " + output_path.string());
    }

    output << "trade_date,cust_no,cust_name,fund_account_no,currency_code,"
           << "dyn_rights,hold_profit,avail_fund,risk_degree1,risk_degree2\n";

    for (const CustomerFundRecord& record : SortRecords(records))
    {
        output << record.trade_date << ','
               << EscapeCsv(record.cust_no) << ','
               << EscapeCsv(record.cust_name) << ','
               << EscapeCsv(record.fund_account_no) << ','
               << EscapeCsv(record.currency_code) << ','
               << FormatDouble(record.dyn_rights) << ','
               << FormatDouble(record.hold_profit) << ','
               << FormatDouble(record.avail_fund) << ','
               << FormatDouble(record.risk_degree1) << ','
               << FormatDouble(record.risk_degree2) << '\n';
    }

    return output_path.string();
}

MailRequest BuildMailRequest(
    const std::vector<CustomerFundRecord>& records,
    const AppConfig& config,
    const CliOptions& cli,
    int trade_date,
    const std::string& attachment_path)
{
    MailRequest request;
    request.from = config.smtp.from;
    request.to = EffectiveRecipients(cli.to, config.default_to);
    request.cc = EffectiveRecipients(cli.cc, config.default_cc);

    if (request.to.empty())
    {
        throw std::runtime_error("no email recipients configured; use config email.default_to or --to");
    }

    request.subject =
        "[" + ToLower(config.env_name) + "] " + config.email_subject + " - " + std::to_string(trade_date);
    request.html_body = BuildHtmlBody(records, config, trade_date);
    request.attachment_path = attachment_path;
    request.attachment_name = std::filesystem::path(attachment_path).filename().string();
    return request;
}

std::string BuildMimeMessage(const MailRequest& request)
{
    const std::string boundary = "----TDS_REPORTER_BOUNDARY_" + CurrentTimestamp();
    const std::string attachment_bytes = ReadBinaryFile(request.attachment_path);
    const std::string encoded_attachment = WrapBase64(Base64Encode(attachment_bytes));

    std::ostringstream output;
    output << "From: " << request.from << "\r\n";
    output << "To: " << Join(request.to, ", ") << "\r\n";
    if (!request.cc.empty())
    {
        output << "Cc: " << Join(request.cc, ", ") << "\r\n";
    }
    output << "Subject: " << EncodeMimeHeader(request.subject) << "\r\n";
    output << "MIME-Version: 1.0\r\n";
    output << "Content-Type: multipart/mixed; boundary=\"" << boundary << "\"\r\n";
    output << "\r\n";
    output << "--" << boundary << "\r\n";
    output << "Content-Type: text/html; charset=\"utf-8\"\r\n";
    output << "Content-Transfer-Encoding: 8bit\r\n";
    output << "\r\n";
    output << request.html_body << "\r\n";
    output << "\r\n";
    output << "--" << boundary << "\r\n";
    output << "Content-Type: text/csv; name=\"" << request.attachment_name << "\"\r\n";
    output << "Content-Transfer-Encoding: base64\r\n";
    output << "Content-Disposition: attachment; filename=\"" << request.attachment_name << "\"\r\n";
    output << "\r\n";
    output << encoded_attachment;
    output << "--" << boundary << "--\r\n";
    return output.str();
}

std::string BuildCurlConfig(const MailRequest& request, const AppConfig& config, const std::string& message_path)
{
    const bool has_client_cert = !config.smtp.client_cert_path.empty();
    const bool has_client_key = !config.smtp.client_key_path.empty();
    if (has_client_cert != has_client_key)
    {
        throw std::runtime_error("smtp.client_cert_path and smtp.client_key_path must be configured together");
    }

    std::ostringstream curl_config;
    const std::string scheme = config.smtp.security == "ssl" ? "smtps" : "smtp";
    curl_config << "url = \"" << EscapeCurlConfigValue(
        scheme + "://" + config.smtp.host + ":" + std::to_string(config.smtp.port)) << "\"\n";
    curl_config << "mail-from = \"" << EscapeCurlConfigValue(request.from) << "\"\n";
    for (const std::string& recipient : request.to)
    {
        curl_config << "mail-rcpt = \"" << EscapeCurlConfigValue(recipient) << "\"\n";
    }
    for (const std::string& recipient : request.cc)
    {
        curl_config << "mail-rcpt = \"" << EscapeCurlConfigValue(recipient) << "\"\n";
    }
    if (!config.smtp.username.empty())
    {
        curl_config << "user = \""
                    << EscapeCurlConfigValue(config.smtp.username + ":" + config.smtp.password)
                    << "\"\n";
    }
    if (has_client_cert)
    {
        curl_config << "cert = \"" << EscapeCurlConfigValue(config.smtp.client_cert_path) << "\"\n";
        curl_config << "key = \"" << EscapeCurlConfigValue(config.smtp.client_key_path) << "\"\n";
        curl_config << "cert-type = \"" << EscapeCurlConfigValue(config.smtp.client_cert_type) << "\"\n";
        curl_config << "key-type = \"" << EscapeCurlConfigValue(config.smtp.client_key_type) << "\"\n";
        if (!config.smtp.client_key_password.empty())
        {
            curl_config << "pass = \"" << EscapeCurlConfigValue(config.smtp.client_key_password) << "\"\n";
        }
    }
    curl_config << "upload-file = \"" << EscapeCurlConfigValue(std::filesystem::path(message_path).generic_string()) << "\"\n";
    curl_config << "connect-timeout = \"" << config.smtp.connect_timeout_seconds << "\"\n";
    curl_config << "silent\n";
    curl_config << "show-error\n";
    if (config.smtp.security == "starttls")
    {
        curl_config << "ssl-reqd\n";
    }
    if (config.smtp.insecure)
    {
        curl_config << "insecure\n";
    }

    return curl_config.str();
}

SendMailResult SendMailWithCurl(const MailRequest& request, const AppConfig& config, bool dry_run)
{
    const std::string mime_message = BuildMimeMessage(request);
    const std::filesystem::path output_dir = config.output_dir;
    std::filesystem::create_directories(output_dir);

    if (dry_run)
    {
        const std::filesystem::path preview_path =
            std::filesystem::absolute(output_dir / ("mail_preview_" + CurrentTimestamp() + ".eml"));
        std::ofstream preview(preview_path, std::ios::binary);
        preview << mime_message;
        return SendMailResult {false, preview_path.string()};
    }

    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path();
    const std::filesystem::path message_path =
        temp_dir / ("tds_reporter_message_" + CurrentTimestamp() + ".eml");
    const std::filesystem::path curl_config_path =
        temp_dir / ("tds_reporter_curl_" + CurrentTimestamp() + ".cfg");

    try
    {
        {
            std::ofstream message_file(message_path, std::ios::binary);
            if (!message_file.is_open())
            {
                throw std::runtime_error("failed to create temporary mime file");
            }
            message_file << mime_message;
        }

        {
            std::ofstream curl_file(curl_config_path);
            if (!curl_file.is_open())
            {
                throw std::runtime_error("failed to create temporary curl config");
            }
            curl_file << BuildCurlConfig(request, config, message_path.string());
        }

        const std::string command = "curl --config " + QuoteForShell(curl_config_path.string());
        const int exit_code = std::system(command.c_str());
        std::filesystem::remove(message_path);
        std::filesystem::remove(curl_config_path);

        if (exit_code != 0)
        {
            throw std::runtime_error("curl exited with code " + std::to_string(exit_code));
        }

        return SendMailResult {true, ""};
    }
    catch (...)
    {
        std::error_code ignored;
        std::filesystem::remove(message_path, ignored);
        std::filesystem::remove(curl_config_path, ignored);
        throw;
    }
}

} // namespace tds_reporter
