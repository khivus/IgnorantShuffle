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
            const auto reply = transport_("https://api.github.com/repos/" + repository + "/releases/latest", 2 * 1024 * 1024, canceled_, {});
            if (canceled_.load()) throw std::runtime_error("Canceled.");
            std::optional<ReleaseUpdate> release;
            if (reply.status == 200) release = parse_release(std::string(reply.body.begin(), reply.body.end()), repository, installed_);
            else if (reply.status != 404) throw std::runtime_error(reply.status == 403 || reply.status == 429 ? "GitHub rate limit or access restriction. Try again later." : "GitHub update check failed (HTTP " + std::to_string(reply.status) + ").");
            std::lock_guard<std::mutex> lock(mutex_);
            state_.release = release;
            state_.phase = release ? UpdatePhase::Available : reply.status == 404 ? UpdatePhase::Idle : UpdatePhase::Current;
            state_.message = reply.status == 404 ? "Repository or release is not published yet." : release ? ("Update " + release->tag + " available." + (release->unavailable_reason.empty() ? "" : " " + release->unavailable_reason)) : "You have the latest stable version.";
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
    state_.message = "Package sent to AIMP. Finish installation and restart when AIMP asks.";
}
void UpdateClient::install_error(const std::string& error) { std::lock_guard<std::mutex> lock(mutex_); state_.phase = UpdatePhase::Error; state_.message = error; }
}
