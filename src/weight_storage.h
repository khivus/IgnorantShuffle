#pragma once
#include "weights.h"

namespace ignorant {
enum class WeightScope { Global, Folder, Playlist };
struct WeightContext {
    std::filesystem::path folder;
    std::string playlist;
};
struct WeightValue {
    double weight = default_weight;
    WeightScope scope = WeightScope::Global;
};

// Optional portable layers over the unchanged, absolute-key profile weights.
// Files are cached for the session; configuring/reloading clears the cache.
class WeightStorage {
    std::filesystem::path root_;
    bool global_ = false, folders_ = false, playlists_ = false;
    std::map<std::filesystem::path, Weights> files_;
    std::map<std::filesystem::path, std::string> errors_;
    Weights& file(const std::filesystem::path& path);
    Weights read_current(const std::filesystem::path& path);
    std::optional<double> lookup(const std::filesystem::path& path, const std::string& track);
public:
    void configure(const std::filesystem::path& root, bool global, bool folders, bool playlists);
    bool enabled() const { return global_ || folders_ || playlists_; }
    bool folders_enabled() const { return folders_; }
    bool playlists_enabled() const { return playlists_; }
    const auto& root() const { return root_; }
    WeightContext context(const std::vector<std::string>& tracks, const std::string& playlist) const;
    WeightValue resolve(const Weights& profile, const std::string& track, const WeightContext& context,
        WeightScope through = WeightScope::Playlist);
    // nullopt means the existing AIMP-profile file remains the global target.
    std::optional<std::filesystem::path> target(WeightScope scope, const std::string& track, const WeightContext& context) const;
    bool available(WeightScope scope, const WeightContext& context) const;
    bool has_override(WeightScope scope, const std::string& track, const WeightContext& context);
    Weights snapshot(WeightScope scope, const Weights& profile, const std::vector<std::string>& tracks, const WeightContext& context);
    void set(WeightScope scope, const std::string& track, const WeightContext& context, double weight);
    void set_many(WeightScope scope, const std::vector<std::string>& tracks, const WeightContext& context, double weight);
    void clear(WeightScope scope, const std::string& track, const WeightContext& context);
    void clear_many(WeightScope scope, const std::vector<std::string>& tracks, const WeightContext& context);
    void import(WeightScope scope, const std::string& track, const WeightContext& context, std::istream& input);
    std::size_t copy_profile(const Weights& profile);
};
}
