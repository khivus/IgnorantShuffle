#pragma once
#include <windows.h>
#include <filesystem>
#include <functional>
#include <string>
namespace ignorant {
struct UpdateInstallCommand {
    std::filesystem::path executable;
    std::wstring arguments;
    std::filesystem::path directory;
};
using UpdateLauncher = std::function<void(HWND, const UpdateInstallCommand&)>;
UpdateInstallCommand update_install_command(const std::filesystem::path& executable, const std::filesystem::path& package);
void launch_update_installer(HWND owner, const UpdateInstallCommand& command);
// Use the running host executable, as AIMP's registered .aimppack open command
// does. Launch success means requested, not confirmation of installation.
UpdateInstallCommand install_update_package(HWND aimp, const std::filesystem::path& package, const UpdateLauncher& launcher = {});
}
