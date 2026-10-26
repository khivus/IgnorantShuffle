#include "update_install.h"
#include <shellapi.h>
#include <algorithm>
#include <stdexcept>
#include <vector>

namespace ignorant {
UpdateInstallCommand update_install_command(const std::filesystem::path& executable, const std::filesystem::path& package) {
    if (!executable.is_absolute() || !std::filesystem::is_regular_file(executable))
        throw std::runtime_error("AIMP's executable is unavailable. The verified package has been kept.");
    if (!package.is_absolute() || package.extension() != L".aimppack" || !std::filesystem::is_regular_file(package))
        throw std::runtime_error("The verified update package is no longer available. Download it again.");
    // Windows filenames cannot contain quotes, and a package filename cannot
    // end in a backslash. Quote the entire path, including spaces and Unicode.
    return {executable, L"\"" + package.wstring() + L"\"", executable.parent_path()};
}
void launch_update_installer(HWND owner, const UpdateInstallCommand& command) {
    SHELLEXECUTEINFOW launch{}; launch.cbSize = sizeof(launch);
    launch.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI;
    launch.hwnd = owner; launch.lpVerb = L"open";
    launch.lpFile = command.executable.c_str(); launch.lpParameters = command.arguments.c_str();
    launch.lpDirectory = command.directory.c_str(); launch.nShow = SW_SHOWNORMAL;
    if (!ShellExecuteExW(&launch))
        throw std::runtime_error("Could not ask AIMP to open the verified package (Windows error " + std::to_string(GetLastError()) + "). Use Open package folder, then AIMP Preferences > Plugins > Install.");
    if (launch.hProcess) CloseHandle(launch.hProcess);
}
UpdateInstallCommand install_update_package(HWND aimp, const std::filesystem::path& package, const UpdateLauncher& launcher) {
    DWORD process = 0;
    if (!aimp || !IsWindow(aimp) || !GetWindowThreadProcessId(aimp, &process) || process != GetCurrentProcessId())
        throw std::runtime_error("AIMP's main window is unavailable. The downloaded package has been kept; try again.");
    std::vector<wchar_t> name(512);
    for (;;) {
        const auto length = GetModuleFileNameW(nullptr, name.data(), static_cast<DWORD>(name.size()));
        if (!length) throw std::runtime_error("Cannot locate the running AIMP executable.");
        if (length < name.size()) break;
        if (name.size() >= 32768) throw std::runtime_error("AIMP's executable path is too long.");
        name.resize(std::min<std::size_t>(32768, name.size() * 2));
    }
    const auto command = update_install_command(name.data(), package);
    if (launcher) launcher(aimp, command); else launch_update_installer(aimp, command);
    return command;
}
}
