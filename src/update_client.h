#pragma once
#include "update_http.h"
#include "update_model.h"
#include <filesystem>
#include <mutex>
#include <thread>

namespace ignorant {
enum class UpdatePhase { Unconfigured, Idle, Checking, Current, Available, Downloading, Ready, HandedOff, Error };
struct UpdateState {
    UpdatePhase phase = UpdatePhase::Unconfigured;
    std::string message = "GitHub repository is not configured yet.";
    std::optional<ReleaseUpdate> release;
    std::filesystem::path package;
    std::size_t received = 0;
    bool busy() const { return phase == UpdatePhase::Checking || phase == UpdatePhase::Downloading; }
};
using UpdateTransport = std::function<HttpReply(const std::string&, std::size_t, const std::atomic<bool>&, const DownloadProgress&)>;
std::string update_sha256(const std::vector<unsigned char>& data);
void verify_update_package(const std::filesystem::path& path, const ReleaseUpdate& release);
class UpdateClient {
    mutable std::mutex mutex_;
    UpdateState state_;
    std::thread worker_;
    std::atomic<bool> canceled_{false};
    std::string installed_;
    UpdateTransport transport_;
    void failure(const std::string& message);
public:
    explicit UpdateClient(std::string installed, UpdateTransport transport = {});
    ~UpdateClient() { stop(); }
    UpdateState state() const;
    bool check(const std::string& repository);
    bool download(const std::filesystem::path& cache);
    void cancel() { canceled_.store(true); }
    void stop();
    void handed_off();
    void install_error(const std::string& error);
};
}
