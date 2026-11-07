#pragma once
#include "weights.h"

namespace ignorant {
struct WeightValue { double weight = default_weight; std::filesystem::path file; };

// One relative-path weights file per music root. No profile or override layers.
class WeightStorage {
    std::vector<std::filesystem::path> roots_;
    std::map<std::filesystem::path, Weights> files_;
    Weights read_current(const std::filesystem::path& path) const;
    Weights& file(const std::filesystem::path& path);
public:
    void configure(const std::vector<std::filesystem::path>& roots);
    const auto& roots() const { return roots_; }
    bool enabled() const { return !roots_.empty(); }
    std::optional<std::filesystem::path> target(const std::string& track) const;
    WeightValue resolve(const Weights& defaults, const std::string& track);
    void set_many(const std::vector<std::string>& tracks, double weight);
    void clear_many(const std::vector<std::string>& tracks);
    void transfer(const std::filesystem::path& root, const std::filesystem::path& external, bool exporting);
};
}
