#pragma once
#include <array>
#include <cstdint>
#include <optional>
#include <string>

namespace ignorant {
struct ReleaseUpdate {
    std::string tag, page_url, asset_url, asset_name, sha256, unavailable_reason;
    std::uint64_t size = 0;
    bool installable() const { return !asset_url.empty() && !sha256.empty() && unavailable_reason.empty(); }
};
constexpr std::size_t maximum_update_size = 32 * 1024 * 1024;
std::string normalize_repository(std::string value);
std::array<unsigned, 4> release_version(std::string value);
std::optional<ReleaseUpdate> parse_release(const std::string& json, const std::string& repository, const std::string& installed);
}
