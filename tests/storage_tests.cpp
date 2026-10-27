#include "weight_storage.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>

namespace {
void check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
template<class Action> void rejects(Action action) {
    bool failed = false; try { action(); } catch (const std::exception&) { failed = true; }
    check(failed, "Expected a storage error.");
}
}
int main() {
    namespace fs = std::filesystem;
    const auto fixture = fs::current_path() / ("storage-fixture-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        using namespace ignorant;
        check(!fs::exists(fixture), "Fixture must be a new directory.");
        const auto root = fixture / "Music", album = root / "Favorites", other = root / "Other";
        fs::create_directories(album / "Nested"); fs::create_directories(other);
        const auto track = (album / "song.mp3").u8string(), nested = (album / "Nested" / "other.mp3").u8string();
        const auto second = (other / "second.mp3").u8string();
        Weights profile; profile.set(track, 7); profile.set(second, 3);
        WeightStorage storage;
        storage.configure({}, false, false, false);
        auto context = storage.context({track, nested}, "Favorites");
        check(storage.resolve(profile, track, context).weight == 7 && !storage.target(WeightScope::Global, track, context), "Default uses the existing profile file.");
        check(!fs::exists(root / "IgnorantShuffle.weights.tsv"), "Default mode never writes into music folders.");
        storage.configure(root, true, true, true);
        context = storage.context({track, nested}, "Favorites");
        check(context.folder == album, "Folder scope uses the list's common music folder.");
        profile.set_default(2.5);
        check(storage.resolve(profile, nested, context).weight == 2.5 && storage.resolve(profile, track, context).weight == 7,
            "Shared layers inherit the configurable default without replacing explicit weights.");
        storage.set(WeightScope::Folder, nested, context, 9);
        profile.set_default(0);
        check(storage.resolve(profile, nested, context).weight == 9, "Local overrides beat even a zero default.");
        storage.clear(WeightScope::Folder, nested, context);
        check(storage.resolve(profile, nested, context).weight == 0, "Inherit restores the configured default.");
        profile.set_default(5);
        check(storage.copy_profile(profile) == 2, "Existing profile weights can be copied into portable global storage.");
        Weights global; global.load(root / "IgnorantShuffle.weights.tsv");
        check(global.get("Favorites/song.mp3") == 7 && global.entries().count(track) == 0, "Shared global file uses relative paths.");
        storage.set(WeightScope::Global, track, context, 25);
        check(storage.copy_profile(profile) == 0 && storage.resolve(profile, track, context).weight == 25, "Migration never replaces an existing shared weight, including imports above ten.");
        Weights elsewhere; elsewhere.load(root / "IgnorantShuffle.weights.tsv");
        elsewhere.set("Other/second.mp3", 9); elsewhere.save(root / "IgnorantShuffle.weights.tsv");
        storage.set(WeightScope::Global, track, context, 25);
        check(storage.resolve(profile, second, context, WeightScope::Global).weight == 9,
            "An edit rereads the file and preserves unrelated changes made elsewhere since it was cached.");
        const auto unicode_folder = root / fs::u8path(u8"\u0422\u0435\u0441\u0442");
        fs::create_directories(unicode_folder);
        const auto unicode_track = (unicode_folder / fs::u8path(u8"\u043f\u0435\u0441\u043d\u044f.mp3")).u8string();
        const auto unicode_context = storage.context({unicode_track}, "Unicode");
        storage.set(WeightScope::Folder, unicode_track, unicode_context, 6);
        check(storage.resolve(profile, unicode_track, unicode_context).weight == 6, "Unicode folders and filenames round-trip through portable UTF-8 keys.");
        storage.set(WeightScope::Folder, track, context, 0);
        check(storage.resolve(profile, track, context).weight == 0 && storage.resolve(profile, track, context).scope == WeightScope::Folder, "Folder override including zero wins over global.");
        auto broad = storage.context({track, second}, "Everything");
        check(broad.folder == root && storage.resolve(profile, track, broad).weight == 25, "Listening outside the original folder's list uses global weight.");
        auto inside = storage.context({nested}, "Nested");
        storage.set(WeightScope::Folder, nested, context, 8);
        check(storage.resolve(profile, nested, inside).weight == 8, "Nested folders inherit their parent folder's local weights.");
        storage.set(WeightScope::Playlist, track, context, 2.5);
        check(storage.resolve(profile, track, context).weight == 2.5, "AIMP-playlist overrides are an independent optional highest layer.");
        auto another = context; another.playlist = "Another playlist";
        check(storage.resolve(profile, track, another).weight == 0, "Other AIMP playlists retain their own local/global inheritance.");
        storage.clear(WeightScope::Playlist, track, context);
        check(storage.resolve(profile, track, context).weight == 0, "Clearing a playlist override reveals the folder weight.");
        storage.clear(WeightScope::Folder, track, context);
        check(storage.resolve(profile, track, context).weight == 25, "Clearing a folder override reveals global weight.");
        storage.set_many(WeightScope::Folder, {track, nested}, context, 2.5);
        check(storage.resolve(profile, track, context).weight == 2.5 && storage.resolve(profile, nested, context).weight == 2.5,
            "One shared batch assigns a fractional weight to every selected track.");
        rejects([&] { storage.set_many(WeightScope::Folder, {track, nested}, context, -1); });
        check(storage.resolve(profile, track, context).weight == 2.5 && storage.resolve(profile, nested, context).weight == 2.5,
            "Invalid batch weights cannot change any selected entry.");
        storage.clear_many(WeightScope::Folder, {track, nested}, context);
        check(storage.resolve(profile, track, context).weight == 25 && storage.resolve(profile, nested, context).weight == 5,
            "Bulk Inherit removes each explicit override without changing global values.");
        std::istringstream imported("0\tsong.mp3\n100\tNested/other.mp3\n");
        storage.import(WeightScope::Folder, track, context, imported);
        check(storage.resolve(profile, track, context).weight == 0 && storage.resolve(profile, nested, context).weight == 100, "Relative local import preserves zeros and large weights.");
        std::istringstream bad("9\tsong.mp3\n-1\tbad.mp3\n");
        rejects([&] { storage.import(WeightScope::Folder, track, context, bad); });
        check(storage.resolve(profile, track, context).weight == 0, "Invalid import leaves the previous local weights intact.");
        // Simulate copying the music tree to another device/drive.
        const auto moved = fixture / "Copied music", moved_album = moved / "Favorites";
        fs::create_directories(moved_album / "Nested"); fs::create_directories(moved / "Other");
        fs::copy_file(root / "IgnorantShuffle.weights.tsv", moved / "IgnorantShuffle.weights.tsv");
        fs::copy_file(album / "IgnorantShuffle.folder.weights.tsv", moved_album / "IgnorantShuffle.folder.weights.tsv");
        WeightStorage copied; copied.configure(moved, true, true, false);
        const auto moved_track = (moved_album / "song.mp3").u8string();
        const auto moved_context = copied.context({moved_track}, "Favorites");
        check(copied.resolve({}, moved_track, moved_context).weight == 0 && copied.resolve({}, moved_track, moved_context, WeightScope::Global).weight == 25, "Relative global and local weights survive relocation without old profile data.");
        const auto unsafe_name = storage.context({track}, "a/:*?playlist");
        check(storage.target(WeightScope::Playlist, track, unsafe_name)->parent_path() == album, "Playlist names cannot turn into paths.");
        check(storage.context({track, (fixture / "outside.mp3").u8string()}, "Mixed").folder.empty(), "Mixed lists outside the configured tree cannot write a local file in an unintended folder.");
        const auto target = *storage.target(WeightScope::Folder, track, context);
        fs::create_directory(fs::path(target.wstring() + L".tmp"));
        rejects([&] { storage.set_many(WeightScope::Folder, {track, nested}, context, 9); });
        check(storage.resolve(profile, track, context).weight == 0, "Failed save keeps both effective weight and original file intact.");
        check(storage.resolve(profile, nested, context).weight == 100, "Failed batch save keeps the other selected weight intact too.");
        fs::remove(fs::path(target.wstring() + L".tmp"));
        { std::ofstream corrupt(target); corrupt << "invalid weights\n"; }
        rejects([&] { storage.set(WeightScope::Folder, track, context, 9); });
        storage.configure(root, true, true, true);
        rejects([&] { storage.resolve(profile, track, context); });
        rejects([&] { storage.set(WeightScope::Folder, track, context, 9); });
        std::ifstream preserved(target); std::string line; std::getline(preserved, line);
        check(line == "invalid weights", "Corrupt shared files are reported and never silently replaced.");
        preserved.close();
        fs::remove_all(fixture);
        std::cout << "Portable layers, folder context, relocation, migration and transactional failures passed.\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
