#include "app.h"
#include "logging.h"

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <set>
#include <sstream>
#include <stdexcept>
#include <tuple>
#include <unordered_map>
#ifndef _WIN32
#include <sys/wait.h>
#endif

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

std::string ParseLogLevelValue(const std::string& raw)
{
    const std::string normalized = ToLower(Trim(raw));
    if (normalized == "debug" || normalized == "info" || normalized == "warn" || normalized == "error")
    {
        return normalized;
    }
    throw std::runtime_error("invalid log.level: " + raw);
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
            quoted += "\"\"";
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

std::string GetEnvironmentValue(const std::string& key)
{
    const char* value = std::getenv(key.c_str());
    if (value == nullptr)
    {
        return "";
    }
    return value;
}

void SetEnvironmentValue(const std::string& key, const std::string& value)
{
#ifdef _WIN32
    _putenv_s(key.c_str(), value.c_str());
#else
    setenv(key.c_str(), value.c_str(), 1);
#endif
}

void ClearEnvironmentValue(const std::string& key)
{
#ifdef _WIN32
    _putenv_s(key.c_str(), "");
#else
    unsetenv(key.c_str());
#endif
}

std::string EffectiveVaultValue(
    const std::string& configured_value,
    const std::string& env_name,
    const std::string& fallback = "")
{
    if (!configured_value.empty())
    {
        return configured_value;
    }

    const std::string env_value = GetEnvironmentValue(env_name);
    if (!env_value.empty())
    {
        return env_value;
    }

    return fallback;
}

VaultConfig BuildEffectiveVaultConfig(const VaultConfig& configured)
{
    VaultConfig effective = configured;
    effective.executable = EffectiveVaultValue(configured.executable, "VAULT_BIN", "vault");
    effective.address = EffectiveVaultValue(configured.address, "VAULT_ADDR");
    effective.namespace_name = EffectiveVaultValue(configured.namespace_name, "VAULT_NAMESPACE");
    effective.auth_method = ToLower(EffectiveVaultValue(configured.auth_method, "VAULT_AUTH_METHOD", "token"));
    effective.auth_path = EffectiveVaultValue(configured.auth_path, "VAULT_AUTH_PATH");
    effective.token = EffectiveVaultValue(configured.token, "VAULT_TOKEN");
    effective.cert_role = EffectiveVaultValue(configured.cert_role, "VAULT_CERT_ROLE");
    effective.ca_cert_path = EffectiveVaultValue(configured.ca_cert_path, "VAULT_CA_CERT");
    effective.client_cert_path = EffectiveVaultValue(configured.client_cert_path, "VAULT_CLIENT_CERT");
    effective.client_key_path = EffectiveVaultValue(configured.client_key_path, "VAULT_CLIENT_KEY");
    effective.kerberos_username = EffectiveVaultValue(configured.kerberos_username, "VAULT_KERBEROS_USERNAME");
    effective.kerberos_service = EffectiveVaultValue(configured.kerberos_service, "VAULT_KERBEROS_SERVICE");
    effective.kerberos_realm = EffectiveVaultValue(configured.kerberos_realm, "VAULT_KERBEROS_REALM");
    effective.kerberos_keytab_path = EffectiveVaultValue(configured.kerberos_keytab_path, "VAULT_KERBEROS_KEYTAB");
    effective.kerberos_krb5conf_path = EffectiveVaultValue(configured.kerberos_krb5conf_path, "VAULT_KRB5CONF");

    if (configured.kerberos_disable_fast_negotiation)
    {
        effective.kerberos_disable_fast_negotiation = true;
    }
    else
    {
        const std::string disable_fast =
            EffectiveVaultValue("", "VAULT_DISABLE_FAST_NEGOTIATION", "false");
        effective.kerberos_disable_fast_negotiation =
            ParseBoolValue(disable_fast, "vault.disable_fast_negotiation");
    }

    return effective;
}

class ScopedEnvOverride
{
public:
    ScopedEnvOverride(const std::string& key, const std::string& value)
        : key_(key),
          previous_value_(GetEnvironmentValue(key)),
          had_previous_(std::getenv(key.c_str()) != nullptr)
    {
        SetEnvironmentValue(key_, value);
    }

    ~ScopedEnvOverride()
    {
        if (had_previous_)
        {
            SetEnvironmentValue(key_, previous_value_);
        }
        else
        {
            ClearEnvironmentValue(key_);
        }
    }

private:
    std::string key_;
    std::string previous_value_;
    bool had_previous_ = false;
};

std::string TrimTrailingWhitespace(std::string value)
{
    while (!value.empty() &&
           (value.back() == '\n' || value.back() == '\r' || std::isspace(static_cast<unsigned char>(value.back()))))
    {
        value.pop_back();
    }
    return value;
}

struct CommandResult
{
    int exit_code = 0;
    std::string output;
};

CommandResult RunCommandCapture(const std::string& command)
{
#ifdef _WIN32
    const std::string redirected_command = "cmd /s /c \"" + command + " 2>&1\"";
    FILE* pipe = _popen(redirected_command.c_str(), "r");
#else
    const std::string redirected_command = command + " 2>&1";
    FILE* pipe = popen(redirected_command.c_str(), "r");
#endif
    if (pipe == nullptr)
    {
        throw std::runtime_error("failed to execute command: " + command);
    }

    std::string output;
    char buffer[512];
    while (std::fgets(buffer, static_cast<int>(sizeof(buffer)), pipe) != nullptr)
    {
        output += buffer;
    }

#ifdef _WIN32
    const int raw_exit_code = _pclose(pipe);
    const int exit_code = raw_exit_code;
#else
    const int raw_exit_code = pclose(pipe);
    const int exit_code = WIFEXITED(raw_exit_code) ? WEXITSTATUS(raw_exit_code) : raw_exit_code;
#endif

    return {exit_code, TrimTrailingWhitespace(output)};
}

struct VaultSecretReference
{
    std::string path;
    std::string field;
};

bool IsVaultSecretReference(const std::string& value)
{
    return value.rfind("vault://", 0) == 0;
}

VaultSecretReference ParseVaultSecretReference(const std::string& value)
{
    const std::string body = value.substr(std::string("vault://").size());
    const std::size_t delimiter = body.rfind('#');
    if (body.empty() || delimiter == std::string::npos || delimiter == 0 || delimiter + 1 >= body.size())
    {
        throw std::runtime_error(
            "invalid vault secret reference, expected vault://<secret-path>#<field>: " + value);
    }

    return {body.substr(0, delimiter), body.substr(delimiter + 1)};
}

std::vector<std::unique_ptr<ScopedEnvOverride>> ApplyVaultEnvironment(
    const VaultConfig& vault,
    const std::string& token = "")
{
    std::vector<std::unique_ptr<ScopedEnvOverride>> overrides;

    if (!vault.address.empty())
    {
        overrides.push_back(std::make_unique<ScopedEnvOverride>("VAULT_ADDR", vault.address));
    }
    if (!vault.namespace_name.empty())
    {
        overrides.push_back(std::make_unique<ScopedEnvOverride>("VAULT_NAMESPACE", vault.namespace_name));
    }
    if (!token.empty())
    {
        overrides.push_back(std::make_unique<ScopedEnvOverride>("VAULT_TOKEN", token));
    }

    return overrides;
}

std::string LoginToVault(const VaultConfig& configured_vault)
{
    const VaultConfig vault = BuildEffectiveVaultConfig(configured_vault);
    if (!vault.token.empty())
    {
        return vault.token;
    }

    if (vault.auth_method.empty() || vault.auth_method == "token")
    {
        throw std::runtime_error(
            "vault token is required for vault-backed secrets when vault.auth_method=token");
    }

    auto env_overrides = ApplyVaultEnvironment(vault);
    std::string command = QuoteForShell(vault.executable) + " login -token-only -no-store";

    if (!vault.auth_path.empty())
    {
        command += " -path=" + QuoteForShell(vault.auth_path);
    }

    if (vault.auth_method == "cert")
    {
        if (vault.client_cert_path.empty() || vault.client_key_path.empty())
        {
            throw std::runtime_error(
                "vault.client_cert_path and vault.client_key_path are required for vault.auth_method=cert");
        }

        command += " -method=cert";
        if (!vault.ca_cert_path.empty())
        {
            command += " -ca-cert=" + QuoteForShell(vault.ca_cert_path);
        }
        command += " -client-cert=" + QuoteForShell(vault.client_cert_path);
        command += " -client-key=" + QuoteForShell(vault.client_key_path);
        if (!vault.cert_role.empty())
        {
            command += " name=" + QuoteForShell(vault.cert_role);
        }
    }
    else if (vault.auth_method == "kerberos")
    {
        if (vault.kerberos_username.empty() ||
            vault.kerberos_service.empty() ||
            vault.kerberos_realm.empty() ||
            vault.kerberos_keytab_path.empty() ||
            vault.kerberos_krb5conf_path.empty())
        {
            throw std::runtime_error(
                "vault kerberos auth requires username, service, realm, keytab path, and krb5.conf path");
        }

        command += " -method=kerberos";
        command += " username=" + QuoteForShell(vault.kerberos_username);
        command += " service=" + QuoteForShell(vault.kerberos_service);
        command += " realm=" + QuoteForShell(vault.kerberos_realm);
        command += " keytab_path=" + QuoteForShell(vault.kerberos_keytab_path);
        command += " krb5conf_path=" + QuoteForShell(vault.kerberos_krb5conf_path);
        command += " disable_fast_negotiation=" +
                   QuoteForShell(vault.kerberos_disable_fast_negotiation ? "true" : "false");
    }
    else
    {
        throw std::runtime_error("unsupported vault.auth_method: " + vault.auth_method);
    }

    const CommandResult result = RunCommandCapture(command);
    if (result.exit_code != 0 || result.output.empty())
    {
        throw std::runtime_error(
            "vault login failed: " + (result.output.empty() ? std::string("no output") : result.output));
    }

    return result.output;
}

std::string ReadVaultSecret(const std::string& reference, const VaultConfig& configured_vault)
{
    const VaultSecretReference secret = ParseVaultSecretReference(reference);
    const VaultConfig vault = BuildEffectiveVaultConfig(configured_vault);
    const std::string token = LoginToVault(vault);
    auto env_overrides = ApplyVaultEnvironment(vault, token);

    const std::string command =
        QuoteForShell(vault.executable) + " kv get -field=" + QuoteForShell(secret.field) +
        " " + QuoteForShell(secret.path);
    const CommandResult result = RunCommandCapture(command);

    if (result.exit_code != 0)
    {
        throw std::runtime_error(
            "vault kv get failed for " + secret.path + "#" + secret.field + ": " + result.output);
    }
    if (result.output.empty())
    {
        throw std::runtime_error(
            "vault secret resolved to an empty value: " + secret.path + "#" + secret.field);
    }

    return result.output;
}

std::string ResolveSecretReference(const std::string& value, const VaultConfig& vault)
{
    if (!IsVaultSecretReference(value))
    {
        return value;
    }

    return ReadVaultSecret(value, vault);
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

struct MailReportRow
{
    std::string cust_no;
    std::string cust_name;
    double total_equity = 0.0;
    double mtm_pnl = 0.0;
    double available_funds = 0.0;
    double risk_ratio1 = 0.0;
    double risk_ratio2 = 0.0;
};

std::string InsertThousandsSeparators(std::string text)
{
    const std::size_t decimal_pos = text.find('.');
    const std::size_t insert_end = decimal_pos == std::string::npos ? text.size() : decimal_pos;
    const std::size_t begin = !text.empty() && text.front() == '-' ? 1U : 0U;

    for (std::size_t pos = insert_end; pos > begin + 3; pos -= 3)
    {
        text.insert(pos - 3, ",");
    }

    return text;
}

std::string FormatAmount(double value)
{
    std::ostringstream output;
    output << std::fixed << std::setprecision(2) << value;
    return InsertThousandsSeparators(output.str());
}

std::string FormatPercentage(double value)
{
    std::ostringstream output;
    output << std::fixed << std::setprecision(2) << (value * 100.0);
    return InsertThousandsSeparators(output.str()) + "%";
}

std::string BuildReportTitle(const AppConfig& config, int trade_date)
{
    return config.email_subject + " - Market Close " + std::to_string(trade_date);
}

std::vector<MailReportRow> BuildMailReportRows(const std::vector<CustomerFundRecord>& records)
{
    const std::vector<CustomerFundRecord> sorted = SortRecords(records);
    std::vector<MailReportRow> rows;

    for (const CustomerFundRecord& record : sorted)
    {
        auto existing = std::find_if(
            rows.begin(),
            rows.end(),
            [&](const MailReportRow& row) { return row.cust_no == record.cust_no; });

        if (existing == rows.end())
        {
            MailReportRow row;
            row.cust_no = record.cust_no;
            row.cust_name = record.cust_name;
            row.total_equity = record.dyn_rights;
            row.mtm_pnl = record.hold_profit;
            row.available_funds = record.avail_fund;
            row.risk_ratio1 = record.risk_degree1;
            row.risk_ratio2 = record.risk_degree2;
            rows.push_back(row);
            continue;
        }

        existing->total_equity += record.dyn_rights;
        existing->mtm_pnl += record.hold_profit;
        existing->available_funds += record.avail_fund;
        existing->risk_ratio1 = std::max(existing->risk_ratio1, record.risk_degree1);
        existing->risk_ratio2 = std::max(existing->risk_ratio2, record.risk_degree2);
        if (existing->cust_name.empty() && !record.cust_name.empty())
        {
            existing->cust_name = record.cust_name;
        }
    }

    return rows;
}

std::string BuildHtmlBody(const std::vector<CustomerFundRecord>& records, const AppConfig& config, int trade_date)
{
    const std::vector<MailReportRow> rows = BuildMailReportRows(records);
    double total_dyn_rights = 0.0;
    double total_hold_profit = 0.0;
    double total_avail_fund = 0.0;
    int issue_count = 0;

    for (const MailReportRow& row : rows)
    {
        total_dyn_rights += row.total_equity;
        total_hold_profit += row.mtm_pnl;
        total_avail_fund += row.available_funds;
        if (row.risk_ratio1 > 0.70 || row.risk_ratio2 > 1.00)
        {
            ++issue_count;
        }
    }

    const std::string report_title = BuildReportTitle(config, trade_date);

    std::ostringstream output;
    output << "<html><body style=\"margin:0;padding:18px 28px;font-family:Calibri,Arial,sans-serif;"
              "font-size:14px;line-height:1.35;color:#111;\">";
    output << "<div style=\"max-width:768px;\">";
    output << "<p style=\"margin:0 0 22px 0;font-size:16px;font-weight:700;\">"
           << EscapeHtml(report_title) << "</p>";

    if (issue_count == 0)
    {
        output << "<p style=\"margin:0 0 22px 0;font-size:15px;\">"
               << "As of 15:00 of T Day, no client funding and risk ratio issues identified from Risk Management."
               << "</p>";
    }
    else
    {
        output << "<p style=\"margin:0 0 22px 0;font-size:15px;\">"
               << "As of 15:00 of T Day, "
               << issue_count
               << " client funding and risk ratio issue(s) identified from Risk Management."
               << "</p>";
    }

    output << "<p style=\"margin:0 0 8px 0;font-size:16px;font-weight:700;\">"
           << u8"\u5BA2\u6237\u8D44\u91D1\u53CA\u98CE\u9669\u5EA6 Funding &amp; Risk ratio"
           << "</p>";
    output << "<table cellpadding=\"0\" cellspacing=\"0\" style=\"width:768px;max-width:100%;border-collapse:collapse;"
              "border:1px solid #29445d;table-layout:fixed;\">";
    output << "<tr style=\"background:#0f4f8a;color:#fff;font-weight:700;text-align:center;\">"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:12%;font-size:13px;\">"
           << u8"\u5BA2\u6237\u53F7"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:16%;font-size:13px;\">"
           << u8"\u5BA2\u6237\u540D\u79F0"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:14%;font-size:13px;\">"
           << u8"\u5BA2\u6237\u6743\u76CA"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:14%;font-size:13px;\">"
           << u8"\u6D6E\u52A8\u76C8\u4E8F"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:18%;font-size:13px;\">"
           << u8"\u53EF\u7528\u8D44\u91D1"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:13%;font-size:13px;\">"
           << u8"*\u98CE\u9669\u5EA6 1"
           << "</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;width:13%;font-size:13px;\">"
           << u8"*\u98CE\u9669\u5EA6 2"
           << "</th>"
           << "</tr>";
    output << "<tr style=\"background:#0f4f8a;color:#fff;font-weight:700;text-align:center;\">"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">Account</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">Client</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">Total Equity</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">MTM PnL</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">Available Funds</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">*Risk Ratio 1</th>"
           << "<th style=\"border:1px solid #29445d;padding:8px 6px;font-size:13px;\">*Risk Ratio 2</th>"
           << "</tr>";
    output << "<tr style=\"background:#ffffff;\">"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;font-weight:700;\">"
           << u8"Total \u5408\u8BA1:"
           << "</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;\">&nbsp;</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
           << FormatAmount(total_dyn_rights) << "</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
           << FormatAmount(total_hold_profit) << "</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
           << FormatAmount(total_avail_fund) << "</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;background:#dfe9d9;\">&nbsp;</td>"
           << "<td style=\"border:1px solid #29445d;padding:7px 8px;background:#dfe9d9;\">&nbsp;</td>"
           << "</tr>";

    for (const MailReportRow& row : rows)
    {
        output << "<tr style=\"background:#ffffff;\">"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;\">"
               << EscapeHtml(row.cust_no) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;\">"
               << EscapeHtml(row.cust_name) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
               << FormatAmount(row.total_equity) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
               << FormatAmount(row.mtm_pnl) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;\">"
               << FormatAmount(row.available_funds) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;background:#dfe9d9;\">"
               << FormatPercentage(row.risk_ratio1) << "</td>"
               << "<td style=\"border:1px solid #29445d;padding:7px 8px;font-size:13px;text-align:right;background:#dfe9d9;\">"
               << FormatPercentage(row.risk_ratio2) << "</td>"
               << "</tr>";
    }

    output << "</table>";
    output << "<div style=\"margin-top:32px;max-width:360px;\">";
    output << "<p style=\"margin:0 0 8px 0;font-size:14px;font-weight:700;\">"
           << "*Note: Risk ratio escalation threshold"
           << "</p>";
    output << "<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;min-width:360px;\">";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#dcead2;font-size:12px;font-weight:700;\">No Action Required</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 1</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#dcead2;font-size:12px;font-weight:700;\">&lt;70%</td>"
           << "</tr>";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#f1d54d;font-size:12px;font-weight:700;\">Alert</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 1</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#f1d54d;font-size:12px;font-weight:700;\">&gt;70%</td>"
           << "</tr>";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#d8a06c;font-size:12px;font-weight:700;\">Official Margin Call</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 1</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#d8a06c;font-size:12px;font-weight:700;\">&gt;80%</td>"
           << "</tr>";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#c41212;color:#111;font-size:12px;font-weight:700;\">Final Official Margin</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 1</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#c41212;color:#111;font-size:12px;font-weight:700;\">&gt;100%</td>"
           << "</tr>";
    output << "<tr>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#3b0202;color:#fff;font-size:12px;font-weight:700;\">Forced Liquidation</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#ffffff;font-size:12px;\">Risk ratio 2</td>"
           << "<td style=\"padding:5px 10px;border:1px solid #666;background:#3b0202;color:#fff;font-size:12px;font-weight:700;\">&gt;100%</td>"
           << "</tr>";
    output << "</table>";
    output << "</div>";
    output << "</div>";
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
    const std::filesystem::path single_config("config/tds_reporter.properties");
    if (std::filesystem::exists(single_config))
    {
        return single_config.string();
    }
    return "config/" + env_name + ".properties";
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
    config.log.directory = ResolveConfigRelativePath(config_dir, GetValue(properties, "log.dir", "../logs"));
    config.log.level = ParseLogLevelValue(GetValue(properties, "log.level", config.log.level));

    config.vault.executable = GetValue(properties, "vault.executable", config.vault.executable);
    config.vault.address = GetValue(properties, "vault.address");
    config.vault.namespace_name = GetValue(properties, "vault.namespace");
    config.vault.auth_method = ToLower(GetValue(properties, "vault.auth_method", config.vault.auth_method));
    config.vault.auth_path = GetValue(properties, "vault.auth_path");
    config.vault.token = GetValue(properties, "vault.token");
    config.vault.cert_role = GetValue(properties, "vault.cert_role");
    config.vault.ca_cert_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "vault.ca_cert_path"));
    config.vault.client_cert_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "vault.client_cert_path"));
    config.vault.client_key_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "vault.client_key_path"));
    config.vault.kerberos_username = GetValue(properties, "vault.kerberos_username");
    config.vault.kerberos_service = GetValue(properties, "vault.kerberos_service");
    config.vault.kerberos_realm = GetValue(properties, "vault.kerberos_realm");
    config.vault.kerberos_keytab_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "vault.kerberos_keytab_path"));
    config.vault.kerberos_krb5conf_path =
        ResolveConfigRelativePath(config_dir, GetValue(properties, "vault.kerberos_krb5conf_path"));
    config.vault.kerberos_disable_fast_negotiation = ParseBoolValue(
        GetValue(
            properties,
            "vault.disable_fast_negotiation",
            config.vault.kerberos_disable_fast_negotiation ? "true" : "false"),
        "vault.disable_fast_negotiation");

    config.tds.drtp_host = GetRequiredValue(properties, "tds.drtp_host");
    config.tds.drtp_port = ParseIntValue(
        GetValue(properties, "tds.drtp_port", "0"),
        "tds.drtp_port");
    config.tds.user = GetRequiredValue(properties, "tds.user");
    config.tds.password = ResolveSecretReference(GetValue(properties, "tds.password"), config.vault);
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

    LogInfo(
        "csv_report_written",
        "CSV report written",
        {
            {"trade_date", std::to_string(trade_date)},
            {"row_count", std::to_string(records.size())},
            {"path", output_path.string()}
        });
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

    request.subject = BuildReportTitle(config, trade_date);
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
        LogInfo(
            "mail_preview_written",
            "Dry-run preview file written",
            {
                {"path", preview_path.string()},
                {"recipient_count", std::to_string(request.to.size() + request.cc.size())}
            });
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
        LogInfo(
            "smtp_send_start",
            "Invoking curl SMTP delivery",
            {
                {"smtp_host", config.smtp.host},
                {"smtp_port", std::to_string(config.smtp.port)},
                {"recipient_count", std::to_string(request.to.size() + request.cc.size())}
            });
        const int exit_code = std::system(command.c_str());
        std::filesystem::remove(message_path);
        std::filesystem::remove(curl_config_path);

        if (exit_code != 0)
        {
            throw std::runtime_error("curl exited with code " + std::to_string(exit_code));
        }

        LogInfo("smtp_send_complete", "curl SMTP delivery completed successfully");
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
