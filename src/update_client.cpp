#include "update_client.h"
#include <windows.h>
#include <bcrypt.h>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <stdexcept>

namespace ignorant {
std::string update_sha256(const std::vector<unsigned char>& data) {
    struct Algorithm { BCRYPT_ALG_HANDLE handle = nullptr; ~Algorithm() { if (handle) BCryptCloseAlgorithmProvider(handle, 0); } } algorithm;
    if (BCryptOpenAlgorithmProvider(&algorithm.handle, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) throw std::runtime_error("Cannot initialize SHA256 verification.");
    DWORD length = 0, received = 0;
    if (BCryptGetProperty(algorithm.handle, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&length), sizeof(length), &received, 0) < 0) throw std::runtime_error("Cannot prepare SHA256 verification.");
    std::vector<unsigned char> object(length);
    struct Hash { BCRYPT_HASH_HANDLE handle = nullptr; ~Hash() { if (handle) BCryptDestroyHash(handle); } } hash;
    if (BCryptCreateHash(algorithm.handle, &hash.handle, object.data(), length, nullptr, 0, 0) < 0 ||
        BCryptHashData(hash.handle, const_cast<PUCHAR>(data.data()), static_cast<ULONG>(data.size()), 0) < 0) throw std::runtime_error("Cannot calculate update SHA256.");
    unsigned char digest[32]{};
    if (BCryptFinishHash(hash.handle, digest, sizeof(digest), 0) < 0) throw std::runtime_error("Cannot finish update verification.");
    std::ostringstream result; result << std::hex << std::setfill('0');
    for (const auto value : digest) result << std::setw(2) << static_cast<unsigned>(value);
    return result.str();
}
UpdateClient::UpdateClient(std::string installed, UpdateTransport transport) : installed_(std::move(installed)), transport_(std::move(transport)) {
    if (!transport_) transport_ = [](const auto& url, auto limit, const auto& canceled, const auto& progress) { return update_http_get(url, limit, canceled, progress); };
}
void verify_update_package(const std::filesystem::path& path, const ReleaseUpdate& release) {
    if (!release.installable() || release.size > maximum_update_size || !std::filesystem::is_regular_file(path) || std::filesystem::file_size(path) != release.size)
        throw std::runtime_error("Saved update package is missing or its size changed. Download it again.");
    std::vector<unsigned char> bytes(static_cast<std::size_t>(release.size));
    std::ifstream input(path, std::ios::binary);
    input.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    if (!input || input.peek() != std::char_traits<char>::eof() || update_sha256(bytes) != release.sha256)
        throw std::runtime_error("Saved update package failed SHA256 verification. Installation was blocked; download it again.");
}
UpdateState UpdateClient::state() const { std::lock_guard<std::mutex> lock(mutex_); return state_; }
void UpdateClient::stop() { canceled_.store(true); if (worker_.joinable()) worker_.join(); }
void UpdateClient::failure(const std::string& message) {
    std::lock_guard<std::mutex> lock(mutex_);
    state_.phase = canceled_.load() ? (state_.release ? UpdatePhase::Available : UpdatePhase::Idle) : UpdatePhase::Error;
    state_.message = canceled_.load() ? "Update operation canceled." : message;
}
bool UpdateClient::check(const std::string& value) {
    if (state().busy()) return false;
    if (worker_.joinable()) worker_.join();
    const auto repository = normalize_repository(value);
    {
        std::lock_guard<std::mutex> lock(mutex_); state_ = {};
        if (repository.empty()) return false;
        state_.phase = UpdatePhase::Checking; state_.message = "Checking GitHub releases...";
    }
    canceled_.store(false);
    worker_ = std::thread([this, repository] {
        try {
            std::optional<ReleaseUpdate> release;
            bool missing = false;
            for (unsigned page = 1; page <= 10; ++page) {
                const auto reply = transport_("https://api.github.com/repos/" + repository + "/releases?per_page=100&page=" + std::to_string(page), 2 * 1024 * 1024, canceled_, {});
                if (canceled_.load()) throw std::runtime_error("Canceled.");
                if (reply.status == 404 && page == 1) { missing = true; break; }
                if (reply.status != 200) throw std::runtime_error(reply.status == 403 || reply.status == 429 ? "GitHub rate limit or access restriction. Try again later." : "GitHub update check failed (HTTP " + std::to_string(reply.status) + ").");
                const auto entries = parse_release_page(std::string(reply.body.begin(), reply.body.end()), repository, installed_);
                if (entries.newest && (!release || release_version(entries.newest->tag) > release_version(release->tag))) release = entries.newest;
                if (entries.count < 100) break;
                if (page == 10) throw std::runtime_error("Release scan reached 1000 entries; Windows update check could not finish.");
            }
            std::lock_guard<std::mutex> lock(mutex_);
            state_.release = release;
            state_.phase = missing ? UpdatePhase::Idle : release ? UpdatePhase::Available : UpdatePhase::Current;
            state_.message = missing ? "Repository or release is not published yet." : release ? ("Windows update " + release->tag + " available." + (release->unavailable_reason.empty() ? "" : " " + release->unavailable_reason)) : "No newer stable Windows release is available.";
        } catch (const std::exception& error) { failure(error.what()); }
    });
    return true;
}
bool UpdateClient::download(const std::filesystem::path& cache) {
    const auto previous = state();
    if (previous.busy() || !previous.release || !previous.release->installable()) return false;
    if (worker_.joinable()) worker_.join();
    const auto release = *previous.release;
    canceled_.store(false);
    { std::lock_guard<std::mutex> lock(mutex_); state_.phase = UpdatePhase::Downloading; state_.message = "Downloading " + release.tag + "..."; state_.received = 0; state_.package.clear(); }
    worker_ = std::thread([this, release, cache] {
        std::filesystem::path folder, partial;
        try {
            const auto reply = transport_(release.asset_url, maximum_update_size, canceled_, [this](std::size_t received) { std::lock_guard<std::mutex> lock(mutex_); state_.received = received; });
            if (canceled_.load()) throw std::runtime_error("Canceled.");
            if (reply.status != 200) throw std::runtime_error("Package download failed (HTTP " + std::to_string(reply.status) + ").");
            if (reply.body.size() != release.size) throw std::runtime_error("Downloaded package size does not match the release.");
            if (update_sha256(reply.body) != release.sha256) throw std::runtime_error("Downloaded package SHA256 does not match GitHub. Installation was blocked.");
            if (reply.body.size() < 4 || reply.body[0] != 'P' || reply.body[1] != 'K' || reply.body[2] != 3 || reply.body[3] != 4) throw std::runtime_error("Release asset is not an AIMP ZIP package.");
            static std::atomic<unsigned> serial{0};
            folder = std::filesystem::absolute(cache) / ("update-" + std::to_string(GetCurrentProcessId()) + '-' + std::to_string(GetTickCount64()) + '-' + std::to_string(++serial));
            std::filesystem::create_directories(folder);
            partial = folder / "download.part";
            {
                std::ofstream output(partial, std::ios::binary);
                output.write(reinterpret_cast<const char*>(reply.body.data()), static_cast<std::streamsize>(reply.body.size())); output.close();
                if (!output) throw std::runtime_error("Could not save the downloaded update package.");
            }
            if (canceled_.load()) throw std::runtime_error("Canceled.");
            const auto package = folder / std::filesystem::u8path(release.asset_name);
            std::filesystem::rename(partial, package);
            std::lock_guard<std::mutex> lock(mutex_);
            state_.package = package; state_.received = reply.body.size(); state_.phase = UpdatePhase::Ready;
            state_.message = "Verified package is ready for AIMP's installer.";
        } catch (const std::exception& error) {
            std::error_code ignored;
            if (!partial.empty()) std::filesystem::remove(partial, ignored);
            if (!folder.empty()) std::filesystem::remove(folder, ignored);
            failure(error.what());
        }
    });
    return true;
}
void UpdateClient::handed_off() {
    std::lock_guard<std::mutex> lock(mutex_); state_.phase = UpdatePhase::HandedOff;
    state_.message = "Asked AIMP to open the installer. Finish its prompt and restart if asked. If no prompt appears, use AIMP Preferences > Plugins > Install with the saved package.";
}
void UpdateClient::install_error(const std::string& error) { std::lock_guard<std::mutex> lock(mutex_); state_.phase = UpdatePhase::Error; state_.message = error; }
}
