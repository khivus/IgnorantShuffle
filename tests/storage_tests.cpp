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
std::string contents(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(input), {});
}
void write(const std::filesystem::path& path, const std::string& text) { std::ofstream(path, std::ios::binary) << text; }
}
int main() {
    namespace fs = std::filesystem;
    const auto fixture = fs::current_path() / ("storage-fixture-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        using namespace ignorant;
        Weights portable; portable.load(fs::u8path(PORTABLE_WEIGHTS_FIXTURE));
        check(portable.entries().size() == 4 && portable.get("Album/Excluded.flac") == 0 &&
            portable.get(u8"Музыка/Любимая песня.flac") == 25.125, "Windows/Android fixture preserves Unicode, zero and fractions.");
        const auto root = fixture / "Music", other = fixture / "Other library";
        fs::create_directories(root / "Artist"); fs::create_directories(other / "Artist");
        const auto first = (root / "Artist" / "Song.flac").u8string(), second = (other / "Artist" / "Song.flac").u8string();
        Weights defaults; defaults.set_default(2.5);
        WeightStorage storage; storage.configure({});
        rejects([&] { storage.set_many({first}, 9); });
        check(!fs::exists(root / "IgnorantShuffle.weights.tsv"), "No weights can be saved before root selection.");
        storage.configure({root, other});
        check(storage.resolve(defaults, first).weight == 2.5, "An unsaved track uses the configured default.");
        storage.set_many({first}, 0); storage.set_many({second}, 25.5);
        check(storage.resolve(defaults, first).weight == 0 && storage.resolve(defaults, second).weight == 25.5, "Identical relative paths in different roots have independent weights.");
        Weights saved; saved.load(root / "IgnorantShuffle.weights.tsv");
        check(saved.entries().count("Artist/Song.flac") == 1 && saved.entries().count(first) == 0, "Only relative keys are saved in the root file.");
        check(!storage.target("https://radio/stream") && !storage.target((fixture / "outside.flac").u8string()), "URLs and unconfigured trees have no private fallback target.");
        const auto before = contents(root / "IgnorantShuffle.weights.tsv");
        rejects([&] { storage.set_many({first, (fixture / "outside.flac").u8string()}, 7); });
        check(contents(root / "IgnorantShuffle.weights.tsv") == before, "An unconfigured track rejects the whole batch before writes.");
        storage.set_many({first, second}, 7);
        check(storage.resolve(defaults, first).weight == 7 && storage.resolve(defaults, second).weight == 7, "A mixed-library batch writes each root separately.");
        saved = Weights{}; saved.load(root / "IgnorantShuffle.weights.tsv"); saved.set("Other.flac", 9); saved.save(root / "IgnorantShuffle.weights.tsv");
        storage.set_many({first}, 8);
        saved = Weights{}; saved.load(root / "IgnorantShuffle.weights.tsv");
        check(saved.get("Other.flac") == 9, "Edits preserve synchronized values that arrived after caching.");
        storage.clear_many({first});
        check(storage.resolve(defaults, first).weight == 2.5, "Use default removes the root entry.");
        const auto imported = fixture / "import.tsv", exported = fixture / "export.tsv";
        const std::string exact = "\xef\xbb\xbf# comment\r\n2.5000\tArtist/Song.flac\r\n";
        write(imported, exact); storage.transfer(root, imported, false); storage.transfer(root, exported, true);
        check(contents(exported) == exact && contents(root / "IgnorantShuffle.weights.tsv") == exact, "Import/export copies the exact file, retaining comments, BOM, formatting and line endings.");
        saved = Weights{}; saved.load(root / "IgnorantShuffle.weights.tsv");
        check(saved.entries().size() == 1 && storage.resolve(defaults, first).weight == 2.5, "Import replaces rather than merges; export adds no playlist defaults.");
        write(imported, "9\tArtist/Song.flac\n-1\tBroken.flac\n");
        rejects([&] { storage.transfer(root, imported, false); });
        check(contents(root / "IgnorantShuffle.weights.tsv") == exact, "Invalid import cannot partially replace a root file.");
        const auto moved = fixture / "Moved library"; fs::create_directories(moved / "Artist");
        fs::copy_file(root / "IgnorantShuffle.weights.tsv", moved / "IgnorantShuffle.weights.tsv");
        WeightStorage copied; copied.configure({moved});
        check(copied.resolve(defaults, (moved / "Artist" / "Song.flac").u8string()).weight == 2.5, "Relative weights survive moving the music root.");
        rejects([&] { storage.configure({root, root / "Artist"}); });
        check(storage.roots().size() == 2, "Overlapping roots are rejected without losing the applied configuration.");
        const auto target = root / "IgnorantShuffle.weights.tsv";
        fs::create_directory(fs::path(target.wstring() + L".tmp"));
        rejects([&] { storage.set_many({first}, 10); });
        check(contents(target) == exact && storage.resolve(defaults, first).weight == 2.5, "Failed write preserves the file and effective cache.");
        fs::remove(fs::path(target.wstring() + L".tmp"));
        write(target, "invalid weights\n"); rejects([&] { storage.set_many({first}, 9); });
        rejects([&] { storage.configure({root, other}); });
        check(contents(target) == "invalid weights\n", "Corrupt weights are reported and never silently overwritten by an edit.");
        fs::rename(moved / "IgnorantShuffle.weights.tsv", moved / "IgnorantShuffle.weights.tsv.bak");
        rejects([&] { copied.configure({moved}); });
        fs::remove_all(fixture);
        std::cout << "Music-root isolation, exact transfers, relocation and transactional failures passed.\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
