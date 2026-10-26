#pragma once
#include <filesystem>
#include <iosfwd>
#include <map>
#include <optional>
#include <random>
#include <string>
#include <vector>

namespace ignorant {
constexpr double default_weight = 5.0;
class Weights {
public:
    double get(const std::string& track) const;
    void set(const std::string& track, double weight);
    void set_from_editor(const std::string& track, int weight);
    void erase(const std::string& track) { values_.erase(track); }
    // UTF-8 TSV: weight, TAB, track key (absolute in profiles; relative in shared files).
    void import(std::istream& input);
    void export_to(std::ostream& output) const;
    void load(const std::filesystem::path& path);
    void save(const std::filesystem::path& path) const;
    const auto& entries() const { return values_; }
private:
    std::map<std::string, double> values_;
};

// Draw with replacement. No positive candidates means no next track.
// A zero-weight entry can never be selected, including at the zero boundary.
struct Selection {
    std::optional<std::size_t> index;
    std::size_t eligible = 0;
    std::size_t excluded = 0;
    double weight = 0;
    double probability = 0;
    double draw = 0;
    double lower = 0;
    double upper = 0;
};
struct CooldownPool {
    std::vector<std::size_t> indices;
    std::size_t distinct_positive = 0;
    std::size_t blocked = 0;
    bool fallback = false;
};
// N requires N other playback positions between repeats. Duplicate entries
// share their filename's cooldown; zero weights always stay excluded.
CooldownPool cooldown_pool(const std::vector<std::string>& tracks, const std::vector<double>& weights,
    const std::vector<std::string>& recent, std::size_t cooldown);
Selection choose_details(const std::vector<double>& weights, double unit);
std::optional<std::size_t> choose(const std::vector<double>& weights, double unit);
template<class Generator>
std::optional<std::size_t> choose(const std::vector<double>& weights, Generator& generator) {
    return choose(weights, std::generate_canonical<double, 53>(generator));
}
}
