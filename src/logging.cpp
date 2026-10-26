#include "logging.h"
#include <algorithm>
#include <chrono>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <stdexcept>

namespace ignorant {
namespace {
std::string clean(const std::string& value, std::size_t maximum) {
    std::string result;
    for (unsigned char ch : value) {
        if (result.size() >= maximum) { result += "... [truncated]"; break; }
        if (ch == '\r') result += "\\r";
        else if (ch == '\n') result += "\\n";
        else if (ch == '\t') result += "\\t";
        else if (ch < 32) result += '?';
        else result += static_cast<char>(ch);
    }
    return result;
}
std::string timestamp() {
    const auto now = std::chrono::system_clock::now();
    const auto seconds = std::chrono::system_clock::to_time_t(now);
    std::tm utc{};
#ifdef _WIN32
    gmtime_s(&utc, &seconds);
#else
    gmtime_r(&seconds, &utc);
#endif
    const auto milliseconds = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count() % 1000;
    std::ostringstream result;
    result << std::put_time(&utc, "%Y-%m-%dT%H:%M:%S") << '.' << std::setfill('0') << std::setw(3) << milliseconds << 'Z';
    return result.str();
}
}
void PluginLog::start(const std::filesystem::path& path, std::size_t maximum_bytes) noexcept {
    stop();
    try {
        std::lock_guard<std::mutex> lock(mutex_);
        path_ = path;
        maximum_ = std::max<std::size_t>(maximum_bytes, 256);
        stopping_ = false;
        pending_.clear();
        dropped_ = 0;
        sequence_ = 0;
        error_.clear();
        worker_ = std::thread([this] { run(); });
    } catch (...) {
        std::lock_guard<std::mutex> lock(mutex_);
        error_ = "Could not start plugin logging.";
    }
}
void PluginLog::write(const std::string& event, const std::string& detail) noexcept {
    try {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!worker_.joinable() || stopping_ || !error_.empty()) return;
        if (pending_.size() >= 1024) { ++dropped_; return; }
        std::ostringstream line;
        line << timestamp() << " #" << ++sequence_ << ' ' << clean(event, 100) << ' ' << clean(detail, 4000) << '\n';
        pending_.push_back(line.str());
        ready_.notify_one();
    } catch (...) { /* Logging must never interrupt playback. */ }
}
std::string PluginLog::error() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return error_;
}
void PluginLog::stop() noexcept {
    try {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
            ready_.notify_one();
        }
        if (worker_.joinable()) worker_.join();
    } catch (...) { /* Destructor and plugin finalization may not throw. */ }
}
void PluginLog::run() noexcept {
    try {
        std::ofstream output;
        std::size_t size = 0;
        auto open = [&] {
            output.open(path_, std::ios::binary | std::ios::app);
            if (!output) throw std::runtime_error("Could not open plugin.log.");
            std::error_code error;
            size = static_cast<std::size_t>(std::filesystem::file_size(path_, error));
            if (error) size = 0;
        };
        auto append = [&](std::string line) {
            if (line.size() > maximum_) line = line.substr(0, maximum_ - 20) + "... [truncated]\n";
            if (size && size + line.size() > maximum_) {
                output.close();
                if (!output) throw std::runtime_error("Could not close plugin.log for rotation.");
                auto backup = path_; backup += ".1";
                std::error_code error;
                std::filesystem::remove(backup, error);
                if (error) throw std::runtime_error("Could not remove the old plugin.log.1.");
                std::filesystem::rename(path_, backup, error);
                if (error) throw std::runtime_error("Could not rotate plugin.log.");
                open();
            }
            output << line;
            output.flush();
            if (!output) throw std::runtime_error("Could not write plugin.log.");
            size += line.size();
        };
        open();
        for (;;) {
            std::deque<std::string> batch;
            std::size_t dropped;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                ready_.wait(lock, [this] { return stopping_ || !pending_.empty(); });
                batch.swap(pending_);
                dropped = dropped_; dropped_ = 0;
                if (stopping_ && batch.empty() && !dropped) break;
            }
            if (dropped) append(timestamp() + " log.overflow dropped=" + std::to_string(dropped) + '\n');
            for (auto& line : batch) append(std::move(line));
        }
    } catch (const std::exception& error) {
        std::lock_guard<std::mutex> lock(mutex_);
        error_ = error.what(); pending_.clear();
    } catch (...) {
        std::lock_guard<std::mutex> lock(mutex_);
        error_ = "Unexpected plugin logging failure."; pending_.clear();
    }
}
}
