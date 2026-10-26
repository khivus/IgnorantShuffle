#pragma once
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>

namespace ignorant {
// File I/O stays off AIMP's UI/playback callbacks. Memory and disk usage are bounded.
class PluginLog {
    mutable std::mutex mutex_;
    std::condition_variable ready_;
    std::deque<std::string> pending_;
    std::thread worker_;
    bool stopping_ = false;
    std::filesystem::path path_;
    std::size_t maximum_ = 1024 * 1024;
    std::size_t dropped_ = 0;
    unsigned long long sequence_ = 0;
    std::string error_;
    void run() noexcept;
public:
    ~PluginLog() { stop(); }
    void start(const std::filesystem::path& path, std::size_t maximum_bytes = 1024 * 1024) noexcept;
    void write(const std::string& event, const std::string& detail = {}) noexcept;
    void stop() noexcept;
    std::string error() const;
};
}
