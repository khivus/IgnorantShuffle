#include "update_install.h"
#include <shellapi.h>
#include <shlobj.h>
#include <cstring>
#include <stdexcept>

namespace ignorant {
void install_update_package(HWND aimp, const std::filesystem::path& package) {
    DWORD process = 0;
    if (!aimp || !IsWindow(aimp) || !GetWindowThreadProcessId(aimp, &process) || process != GetCurrentProcessId())
        throw std::runtime_error("AIMP's main window is unavailable. The downloaded package has been kept; try again.");
    if (!package.is_absolute() || package.extension() != L".aimppack" || !std::filesystem::is_regular_file(package))
        throw std::runtime_error("The verified update package is no longer available.");
    const auto path = package.wstring();
    const auto size = sizeof(DROPFILES) + (path.size() + 2) * sizeof(wchar_t);
    const auto memory = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, size);
    if (!memory) throw std::runtime_error("Cannot prepare the update for AIMP's installer.");
    auto* drop = static_cast<DROPFILES*>(GlobalLock(memory));
    if (!drop) { GlobalFree(memory); throw std::runtime_error("Cannot prepare the update package path."); }
    drop->pFiles = sizeof(DROPFILES); drop->fWide = TRUE;
    std::memcpy(reinterpret_cast<unsigned char*>(drop) + sizeof(DROPFILES), path.c_str(), (path.size() + 1) * sizeof(wchar_t));
    GlobalUnlock(memory);
    if (!PostMessageW(aimp, WM_DROPFILES, reinterpret_cast<WPARAM>(memory), 0)) {
        GlobalFree(memory); throw std::runtime_error("Could not send the update package to AIMP's installer.");
    }
}
}
