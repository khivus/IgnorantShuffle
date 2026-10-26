#pragma once
#include <windows.h>
#include <filesystem>
namespace ignorant {
// AIMP documents installing add-on packages by dropping them onto its main window.
// Ownership of the DROPFILES allocation transfers to AIMP only on successful post.
void install_update_package(HWND aimp, const std::filesystem::path& package);
}
