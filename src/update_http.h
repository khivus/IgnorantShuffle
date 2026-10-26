#pragma once
#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace ignorant {
struct HttpReply { unsigned status = 0; std::vector<unsigned char> body; };
using DownloadProgress = std::function<void(std::size_t)>;
HttpReply update_http_get(const std::string& url, std::size_t limit, const std::atomic<bool>& canceled,
    const DownloadProgress& progress = {}, bool allow_loopback_http = false);
}
