#include "weight_storage.h"
#include <algorithm>
#include <cstdint>
#include <cwchar>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <stdexcept>

namespace ignorant {
namespace {
bool uri(const std::string& value) { return value.find("://") != std::string::npos; }
bool same_part(const std::filesystem::path& a, const std::filesystem::path& b) {
#ifdef _WIN32
    return _wcsicmp(a.c_str(), b.c_str()) == 0;
#else
    return a == b;
#endif
}
std::optional<std::filesystem::path> within(const std::filesystem::path& path, const std::filesystem::path& folder) {
    if (folder.empty() || !path.is_absolute()) return {};
    const auto normalized = path.lexically_normal(), base = folder.lexically_normal();
    auto item = normalized.begin();
    for (const auto& part : base) {
        if (part.empty()) continue;
        if (item == normalized.end() || !same_part(*item, part)) return {};
        ++item;
    }
    std::filesystem::path relative;
    for (; item != normalized.end(); ++item) relative /= *item;
    return relative;
}
std::string key_for(const std::filesystem::path& file, const std::string& track) {
    if (!uri(track)) {
        const auto path = std::filesystem::u8path(track);
        if (path.is_relative()) return path.lexically_normal().generic_u8string();
        const auto relative = within(path, file.parent_path());
        if (relative && !relative->empty()) return relative->generic_u8string();
    }
    return track;
}
std::string playlist_file(const std::string& name) {
    std::uint64_t hash = 14695981039346656037ull;
    std::string label;
    for (const auto raw : name) {
        const auto value = static_cast<unsigned char>(raw);
        hash = (hash ^ value) * 1099511628211ull;
        if (label.size() < 24) label += (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9') ? static_cast<char>(value) : '_';
    }
    std::ostringstream result;
    result << "IgnorantShuffle.playlist-" << label << '-' << std::hex << hash << ".weights.tsv";
    return result.str();
}
std::optional<double> find(const Weights& weights, const std::string& key) {
    const auto entry = weights.entries().find(key);
    return entry == weights.entries().end() ? std::nullopt : std::optional<double>(entry->second);
}
}
void WeightStorage::configure(const std::filesystem::path& root, bool global, bool folders, bool playlists) {
    WeightStorage next;
    next.global_ = global; next.folders_ = folders; next.playlists_ = playlists;
    if (next.enabled()) {
        if (!root.is_absolute() || !std::filesystem::is_directory(root))
            throw std::runtime_error("Choose an existing absolute music-folder path before enabling shared weights.");
        next.root_ = root.lexically_normal();
        if (global) next.file(next.root_ / "IgnorantShuffle.weights.tsv");
    } else next.root_ = root;
    *this = std::move(next);
}
Weights WeightStorage::read_current(const std::filesystem::path& path) {
    try {
        auto backup = path; backup += ".bak";
        if (!std::filesystem::exists(path) && std::filesystem::exists(backup))
            throw std::runtime_error("Shared file is missing but its .bak exists. Restore the backup before reloading.");
        Weights weights; weights.load(path); return weights;
    }
    catch (const std::exception& error) {
        throw std::runtime_error("Cannot load shared weights (" + path.u8string() + "): " + error.what());
    }
}
Weights& WeightStorage::file(const std::filesystem::path& path) {
    const auto failure = errors_.find(path);
    if (failure != errors_.end()) throw std::runtime_error(failure->second);
    const auto found = files_.find(path);
    if (found != files_.end()) return found->second;
    try {
        auto weights = read_current(path);
        return files_.emplace(path, std::move(weights)).first->second;
    } catch (const std::exception& error) {
        errors_[path] = error.what();
        throw;
    }
}
std::optional<double> WeightStorage::lookup(const std::filesystem::path& path, const std::string& track) {
    const auto& values = file(path);
    auto value = find(values, key_for(path, track));
    if (!value) value = find(values, track); // Accept legacy absolute-key imports.
    return value;
}
WeightContext WeightStorage::context(const std::vector<std::string>& tracks, const std::string& playlist) const {
    WeightContext result; result.playlist = playlist;
    if (!enabled()) return result;
    for (const auto& track : tracks) {
        if (uri(track)) continue;
        const auto path = std::filesystem::u8path(track).lexically_normal();
        if (!path.is_absolute() || !within(path, root_)) { result.folder.clear(); return result; }
        auto parent = path.parent_path();
        if (result.folder.empty()) result.folder = parent;
        else while (!within(parent, result.folder)) {
            const auto previous = result.folder;
            result.folder = previous.parent_path();
            if (result.folder == previous || result.folder.empty()) { result.folder.clear(); return result; }
        }
    }
    return result;
}
bool WeightStorage::available(WeightScope scope, const WeightContext& context) const {
    if (scope == WeightScope::Global) return true;
    return !context.folder.empty() && (scope == WeightScope::Folder ? folders_ : playlists_ && !context.playlist.empty());
}
std::optional<std::filesystem::path> WeightStorage::target(WeightScope scope, const std::string& track, const WeightContext& context) const {
    if (scope == WeightScope::Global) {
        if (global_ && !uri(track) && within(std::filesystem::u8path(track), root_)) return root_ / "IgnorantShuffle.weights.tsv";
        return {};
    }
    if (!available(scope, context)) throw std::runtime_error("This local scope is unavailable. Enable it in Weight storage and open a list inside your music folder.");
    return context.folder / (scope == WeightScope::Folder ? "IgnorantShuffle.folder.weights.tsv" : playlist_file(context.playlist));
}
WeightValue WeightStorage::resolve(const Weights& profile, const std::string& track, const WeightContext& context, WeightScope through) {
    if (through == WeightScope::Playlist && available(WeightScope::Playlist, context))
        if (const auto value = lookup(*target(WeightScope::Playlist, track, context), track)) return {*value, WeightScope::Playlist};
    if (through != WeightScope::Global && available(WeightScope::Folder, context)) {
        auto folder = context.folder;
        while (within(folder, root_)) {
            if (const auto value = lookup(folder / "IgnorantShuffle.folder.weights.tsv", track)) return {*value, WeightScope::Folder};
            if (same_part(folder, root_)) break;
            folder = folder.parent_path();
        }
    }
    if (const auto path = target(WeightScope::Global, track, context))
        if (const auto value = lookup(*path, track)) return {*value, WeightScope::Global};
    return {profile.get(track), WeightScope::Global};
}
void WeightStorage::set(WeightScope scope, const std::string& track, const WeightContext& context, double weight) {
    set_many(scope, {track}, context, weight);
}
void WeightStorage::set_many(WeightScope scope, const std::vector<std::string>& tracks, const WeightContext& context, double weight) {
    if (!std::isfinite(weight) || weight < 0) throw std::runtime_error("Weight must be finite and nonnegative.");
    if (tracks.empty()) return;
    const auto path = target(scope, tracks.front(), context);
    if (!path) throw std::runtime_error("This track uses the AIMP-profile global weights.");
    for (const auto& track : tracks)
        if (target(scope, track, context) != path) throw std::runtime_error("A shared batch must use one target weights file.");
    auto next = read_current(*path);
    for (const auto& track : tracks) next.set(key_for(*path, track), weight);
    next.save(*path); files_[*path] = std::move(next); errors_.erase(*path);
}
bool WeightStorage::has_override(WeightScope scope, const std::string& track, const WeightContext& context) {
    const auto path = target(scope, track, context);
    return path && lookup(*path, track).has_value();
}
Weights WeightStorage::snapshot(WeightScope scope, const Weights& profile, const std::vector<std::string>& tracks, const WeightContext& context) {
    const auto path = target(scope, (root_ / "export-target").u8string(), context);
    auto result = path ? file(*path) : profile;
    for (const auto& track : tracks)
        result.set(path ? key_for(*path, track) : track, resolve(profile, track, context, scope).weight);
    return result;
}
void WeightStorage::clear(WeightScope scope, const std::string& track, const WeightContext& context) {
    clear_many(scope, {track}, context);
}
void WeightStorage::clear_many(WeightScope scope, const std::vector<std::string>& tracks, const WeightContext& context) {
    if (tracks.empty()) return;
    const auto path = target(scope, tracks.front(), context);
    if (!path) return;
    for (const auto& track : tracks)
        if (target(scope, track, context) != path) throw std::runtime_error("A shared batch must use one target weights file.");
    auto next = read_current(*path);
    const auto before = next.entries().size();
    for (const auto& track : tracks) { next.erase(key_for(*path, track)); next.erase(track); }
    if (next.entries().size() != before) next.save(*path);
    files_[*path] = std::move(next); errors_.erase(*path);
}
void WeightStorage::import(WeightScope scope, const std::string& track, const WeightContext& context, std::istream& input) {
    const auto path = target(scope, track, context);
    if (!path) throw std::runtime_error("Select the AIMP-profile import target for this track.");
    Weights imported; imported.import(input);
    auto next = read_current(*path);
    for (const auto& [key, value] : imported.entries()) next.set(key_for(*path, key), value);
    next.save(*path); files_[*path] = std::move(next); errors_.erase(*path);
}
std::size_t WeightStorage::copy_profile(const Weights& profile) {
    if (!global_) throw std::runtime_error("Enable music-folder global weights first.");
    const auto path = root_ / "IgnorantShuffle.weights.tsv";
    auto next = read_current(path);
    std::size_t copied = 0;
    for (const auto& [track, value] : profile.entries()) {
        if (uri(track) || !within(std::filesystem::u8path(track), root_)) continue;
        const auto key = key_for(path, track);
        if (!find(next, key) && !find(next, track)) { next.set(key, value); ++copied; }
    }
    if (copied) next.save(path);
    files_[path] = std::move(next);
    errors_.erase(path);
    return copied;
}
}
