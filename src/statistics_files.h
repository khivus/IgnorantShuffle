#pragma once
#include "statistics.h"
#include <filesystem>

namespace ignorant {
bool statistics_shard_name(const std::string& name);
std::string statistics_shard(const std::string& origin);
// Read all devices; replace only this device's shard. Call on an I/O worker.
StatisticsLedger sync_statistics(const std::filesystem::path& root, const std::string& origin,
    const StatisticsLedger& local, bool write = true);
}
