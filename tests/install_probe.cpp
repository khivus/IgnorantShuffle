#include <windows.h>
#include <shellapi.h>
#include <filesystem>
#include <fstream>

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
    int count = 0;
    wchar_t** arguments = CommandLineToArgvW(GetCommandLineW(), &count);
    if (!arguments) return 1;
    int result = 1;
    if (count == 2) {
        const std::filesystem::path package(arguments[1]);
        std::ofstream output(package.wstring() + L".args", std::ios::binary);
        const auto value = package.u8string();
        output.write(value.data(), static_cast<std::streamsize>(value.size()));
        output.close();
        result = output ? 0 : 2;
    }
    LocalFree(arguments);
    return result;
}
