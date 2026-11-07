#include "weight_storage.h"
#include <algorithm>
#include <cmath>
#include <chrono>
#include <cwchar>
#include <fstream>
#include <stdexcept>
#ifdef _WIN32
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

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

void replace_copy(const std::filesystem::path& source, const std::filesystem::path& destination) {
    if (std::filesystem::exists(destination) && std::filesystem::equivalent(source, destination)) return;
    auto temporary = destination; temporary += ".transfer-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) + ".tmp";
    try {
        std::filesystem::copy_file(source, temporary, std::filesystem::copy_options::overwrite_existing);
#ifdef _WIN32
        if (!MoveFileExW(temporary.c_str(), destination.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
            throw std::runtime_error("Could not replace weights file.");
#else
        std::filesystem::rename(temporary, destination);
#endif
    } catch (...) { std::error_code ignored; std::filesystem::remove(temporary, ignored); throw; }
}
}
void WeightStorage::configure(const std::vector<std::filesystem::path>& roots) {
    WeightStorage next;
    for (const auto& root : roots) {
        if (!root.is_absolute() || !std::filesystem::is_directory(root))
            throw std::runtime_error("Choose existing absolute music-root folders.");
        const auto normalized = std::filesystem::weakly_canonical(root);
        for (const auto& other : next.roots_)
            if (within(normalized, other) || within(other, normalized))
                throw std::runtime_error("Music roots must be separate folders, without overlapping trees.");
        next.roots_.push_back(normalized);
        next.file(normalized / "IgnorantShuffle.weights.tsv");
    }
    *this = std::move(next);
}
Weights WeightStorage::read_current(const std::filesystem::path& path) const {
    auto backup = path; backup += ".bak";
    if (!std::filesystem::exists(path) && std::filesystem::exists(backup))
        throw std::runtime_error("Weights file is missing but its .bak exists. Restore the backup before reloading.");
    Weights weights;
    try { weights.load(path); }
    catch (const std::exception& error) { throw std::runtime_error("Cannot read " + path.u8string() + ": " + error.what()); }
    return weights;
}
Weights& WeightStorage::file(const std::filesystem::path& path) {
    const auto cached = files_.find(path);
    if (cached != files_.end()) return cached->second;
    return files_.emplace(path, read_current(path)).first->second;
}
std::optional<std::filesystem::path> WeightStorage::target(const std::string& track) const {
    if (uri(track)) return {};
    const auto path = std::filesystem::u8path(track);
    for (const auto& root : roots_) if (const auto relative = within(path, root); relative && !relative->empty())
        return root / "IgnorantShuffle.weights.tsv";
    return {};
}
WeightValue WeightStorage::resolve(const Weights& defaults, const std::string& track) {
    if (const auto path = target(track)) {
        const auto& values = file(*path).entries();
        const auto entry = values.find(key_for(*path, track));
        if (entry != values.end()) return {entry->second, *path};
    }
    return {defaults.get(track), {}};
}
void WeightStorage::set_many(const std::vector<std::string>& tracks, double weight) {
    if (!std::isfinite(weight) || weight < 0) throw std::runtime_error("Weight must be finite and nonnegative.");
    std::map<std::filesystem::path, std::vector<std::string>> grouped;
    for (const auto& track : tracks) {
        const auto path = target(track);
        if (!path) throw std::runtime_error("Add this track's music root in Weight storage before saving a weight: " + track);
        grouped[*path].push_back(track);
    }
    // Validate every destination before writing any of them.
    std::map<std::filesystem::path, Weights> updates;
    for (const auto& [path, entries] : grouped) {
        auto next = read_current(path);
        for (const auto& track : entries) next.set(key_for(path, track), weight);
        updates.emplace(path, std::move(next));
    }
    std::size_t saved = 0;
    try {
        for (auto& [path, next] : updates) { next.save(path); files_[path] = std::move(next); ++saved; }
    } catch (const std::exception& error) {
        throw std::runtime_error(std::to_string(saved) + " music-root files saved before failure: " + error.what());
    }
}
void WeightStorage::clear_many(const std::vector<std::string>& tracks) {
    std::map<std::filesystem::path, std::vector<std::string>> grouped;
    for (const auto& track : tracks) {
        const auto path = target(track);
        if (!path) throw std::runtime_error("Add the music root before editing weights.");
        grouped[*path].push_back(track);
    }
    for (const auto& [path, entries] : grouped) {
        auto next = read_current(path);
        for (const auto& track : entries) next.erase(key_for(path, track));
        next.save(path); files_[path] = std::move(next);
    }
}
void WeightStorage::transfer(const std::filesystem::path& root, const std::filesystem::path& external, bool exporting) {
    if (std::find(roots_.begin(), roots_.end(), root) == roots_.end()) throw std::runtime_error("Select a configured music root.");
    const auto canonical = root / "IgnorantShuffle.weights.tsv";
    if (exporting) {
        auto checked = read_current(canonical);
        if (!std::filesystem::exists(canonical)) checked.save(canonical);
        replace_copy(canonical, external);
    } else {
        Weights checked;
        if (!std::filesystem::is_regular_file(external)) throw std::runtime_error("Cannot open import file.");
        checked.load(external);
        replace_copy(external, canonical);
        files_[canonical] = std::move(checked);
    }
}
}
