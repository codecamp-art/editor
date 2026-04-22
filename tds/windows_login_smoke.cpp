#include <windows.h>

#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

using TdsHandle = void*;
using GetVersionFn = bool (*)(char*, int);
using InitFn = bool (*)(int, int, bool, int, int*, char*);
using AddNodeFn = bool (*)(char*, int);
using FinalizeFn = void (*)();
using ReqLoginFn = int (*)(char*, char*, int*, char*);
using ReqLogoutFn = int (*)(int*, char*);

struct Options
{
    std::string dll_dir = ".";
    std::string host;
    int port = 0;
    std::string user;
    std::string password;
    int function_no = 20100;
    int req_timeout_ms = 3000000;
    int log_level = 0;
    bool klg_enable = true;
    std::string password_env = "TDS_PASSWORD";
};

std::string ReadValue(int argc, char** argv, int& index, const std::string& name)
{
    const std::string arg = argv[index];
    const std::string prefix = name + "=";
    if (arg.rfind(prefix, 0) == 0)
    {
        return arg.substr(prefix.size());
    }
    if (arg == name)
    {
        if (index + 1 >= argc)
        {
            throw std::runtime_error("missing value for " + name);
        }
        ++index;
        return argv[index];
    }
    return "";
}

int ParseInt(const std::string& value, const std::string& field)
{
    try
    {
        return std::stoi(value);
    }
    catch (const std::exception&)
    {
        throw std::runtime_error("invalid integer for " + field + ": " + value);
    }
}

Options ParseOptions(int argc, char** argv)
{
    Options options;
    for (int index = 1; index < argc; ++index)
    {
        if (const std::string value = ReadValue(argc, argv, index, "--dll-dir"); !value.empty())
        {
            options.dll_dir = value;
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--host"); !value.empty())
        {
            options.host = value;
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--port"); !value.empty())
        {
            options.port = ParseInt(value, "--port");
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--user"); !value.empty())
        {
            options.user = value;
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--password"); !value.empty())
        {
            options.password = value;
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--password-env"); !value.empty())
        {
            options.password_env = value;
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--function-no"); !value.empty())
        {
            options.function_no = ParseInt(value, "--function-no");
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--req-timeout-ms"); !value.empty())
        {
            options.req_timeout_ms = ParseInt(value, "--req-timeout-ms");
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--log-level"); !value.empty())
        {
            options.log_level = ParseInt(value, "--log-level");
            continue;
        }
        if (const std::string value = ReadValue(argc, argv, index, "--klg-enable"); !value.empty())
        {
            options.klg_enable = value != "0" && value != "false" && value != "False";
            continue;
        }

        throw std::runtime_error("unknown argument: " + std::string(argv[index]));
    }

    if (options.host.empty())
    {
        throw std::runtime_error("missing required argument: --host");
    }
    if (options.port <= 0)
    {
        throw std::runtime_error("missing or invalid required argument: --port");
    }
    if (options.user.empty())
    {
        throw std::runtime_error("missing required argument: --user");
    }
    if (options.password.empty())
    {
        const char* env_password = std::getenv(options.password_env.c_str());
        if (env_password != nullptr)
        {
            options.password = env_password;
        }
    }

    return options;
}

template <typename T>
T LoadSymbol(HMODULE module, const char* name)
{
    auto* symbol = reinterpret_cast<T>(GetProcAddress(module, name));
    if (symbol == nullptr)
    {
        throw std::runtime_error(std::string("missing symbol: ") + name);
    }
    return symbol;
}

AddNodeFn LoadAddNode(HMODULE module)
{
    if (auto* symbol = reinterpret_cast<AddNodeFn>(GetProcAddress(module, "TdsApi_addDrtpNode")); symbol != nullptr)
    {
        return symbol;
    }
    if (auto* symbol = reinterpret_cast<AddNodeFn>(GetProcAddress(module, "TdsApi_addTrtpNode")); symbol != nullptr)
    {
        return symbol;
    }
    throw std::runtime_error("missing symbol: TdsApi_addDrtpNode/TdsApi_addTrtpNode");
}

void PrintUsage()
{
    std::cout
        << "Usage:\n"
        << "  tds_login_smoke.exe --host 10.26.56.13 --port 3000 --user tds_report [--password secret]\n"
        << "                      [--dll-dir .] [--function-no 20100] [--req-timeout-ms 3000000]\n"
        << "                      [--log-level 0] [--klg-enable true] [--password-env TDS_PASSWORD]\n";
}

} // namespace

int main(int argc, char** argv)
{
    try
    {
        if (argc == 1)
        {
            PrintUsage();
            return 0;
        }

        const Options options = ParseOptions(argc, argv);
        if (!SetDllDirectoryA(options.dll_dir.c_str()))
        {
            throw std::runtime_error("SetDllDirectoryA failed for --dll-dir=" + options.dll_dir);
        }

        HMODULE dll = LoadLibraryA("tds_api.dll");
        if (dll == nullptr)
        {
            throw std::runtime_error("LoadLibraryA failed for tds_api.dll in " + options.dll_dir);
        }

        const auto get_version = LoadSymbol<GetVersionFn>(dll, "TdsApi_getVersion");
        const auto init = LoadSymbol<InitFn>(dll, "TdsApi_init");
        const auto add_node = LoadAddNode(dll);
        const auto finalize = LoadSymbol<FinalizeFn>(dll, "TdsApi_finalize");
        const auto req_login = LoadSymbol<ReqLoginFn>(dll, "TdsApi_reqLogin");
        const auto req_logout = LoadSymbol<ReqLogoutFn>(dll, "TdsApi_reqLogout");

        int err_code = 0;
        char err_msg[256] = {0};
        char version[128] = {0};

        get_version(version, static_cast<int>(sizeof(version)));
        std::cout << "API version: " << version << "\n";

        if (!init(
                options.req_timeout_ms,
                options.log_level,
                options.klg_enable,
                options.function_no,
                &err_code,
                err_msg))
        {
            std::cerr << "TdsApi_init failed: " << err_code << " " << err_msg << "\n";
            FreeLibrary(dll);
            return 1;
        }

        bool logged_in = false;
        try
        {
            std::string host = options.host;
            if (!add_node(host.data(), options.port))
            {
                throw std::runtime_error("TdsApi_addDrtpNode/TdsApi_addTrtpNode failed");
            }

            std::string user = options.user;
            std::string password = options.password;
            const int result = req_login(user.data(), password.data(), &err_code, err_msg);
            if (result != 0)
            {
                std::cerr << "TdsApi_reqLogin failed: " << err_code << " " << err_msg << "\n";
            }
            else
            {
                logged_in = true;
                std::cout << "TdsApi_reqLogin succeeded\n";
            }
        }
        catch (...)
        {
            finalize();
            FreeLibrary(dll);
            throw;
        }

        if (logged_in)
        {
            err_code = 0;
            err_msg[0] = '\0';
            const int logout_result = req_logout(&err_code, err_msg);
            if (logout_result != 0)
            {
                std::cerr << "TdsApi_reqLogout failed: " << err_code << " " << err_msg << "\n";
            }
        }

        finalize();
        FreeLibrary(dll);
        return logged_in ? 0 : 2;
    }
    catch (const std::exception& ex)
    {
        std::cerr << ex.what() << "\n";
        return 1;
    }
}
