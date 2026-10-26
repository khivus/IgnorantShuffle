#include "weights.h"
#include <cmath>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>

namespace {
int checks = 0;
void check(bool success, const char* message) {
    ++checks;
    if (!success) throw std::runtime_error(message);
}
template<class Action> void rejects(Action action) {
    bool threw = false;
    try { action(); } catch (const std::exception&) { threw = true; }
    check(threw, "Expected validation failure.");
}
}
int main() {
    try {
        using namespace ignorant;
        Weights weights;
        check(weights.get("unknown") == 5, "Default weight");
        weights.set_from_editor("muted", 0);
        weights.set_from_editor("favorite", 10);
        rejects([&] { weights.set_from_editor("favorite", 11); });
        rejects([&] { weights.set("bad", -1); });
        rejects([&] { weights.set("bad", std::numeric_limits<double>::infinity()); });
        rejects([&] { weights.set("bad", std::numeric_limits<double>::quiet_NaN()); });
        rejects([&] { weights.set("", 2); });
        rejects([&] { weights.set("bad\tpath", 2); });
        std::istringstream imported("\xef\xbb\xbf# comment\r\n25\tD:\\Music\\favorite.flac\r\n0\tmuted\r\n2.5\tfraction\n");
        weights.import(imported);
        check(weights.get("D:\\Music\\favorite.flac") == 25, "Import must preserve >10");
        check(weights.get("fraction") == 2.5, "Fractional import");
        check(weights.get("muted") == 0, "Import zero");
        const auto previous = weights.entries();
        for (const auto* bad : {"9\tnew\n-1\tbad\n", "NaN\tbad\n", "inf\tbad\n", "1e999\tbad\n", "3xyz\tbad\n", "2\t\n", "no-tab\n"}) {
            std::istringstream input(bad);
            rejects([&] { weights.import(input); });
            check(weights.entries() == previous, "Invalid import must be transactional");
        }
        std::ostringstream exported;
        weights.export_to(exported);
        Weights restored;
        std::istringstream roundtrip(exported.str());
        restored.import(roundtrip);
        check(restored.entries() == weights.entries(), "Export/import round trip");
        const auto file = std::filesystem::current_path() / "test-weights.tsv";
        weights.save(file);
        weights.set_from_editor("favorite", 2);
        weights.save(file); // Must replace an existing file on Windows.
        Weights loaded;
        loaded.load(file);
        check(loaded.entries() == weights.entries(), "Save/load and replacement");
        std::filesystem::remove(file);
        check(!choose({}, 0.0), "Empty candidate list");
        check(!choose({0, 0, 0}, 0.5), "All zero must stop");
        check(choose({0, 5, 0}, 0.0) == 1, "Zero boundary excludes zero");
        check(choose({0, 5, 0}, std::nextafter(1.0, 0.0)) == 1, "Upper boundary excludes zero");
        check(choose({1, 3}, 0.249) == 0, "Proportional lower segment");
        check(choose({1, 3}, 0.25) == 1, "Proportional upper segment");
        check(choose({1e308, 1e308}, 0.75) == 1, "Large imported weights must not overflow");
        const auto details = choose_details({0, 1, 3, 0}, 0.5);
        check(details.index == 2 && details.weight == 3 && details.probability == 0.75, "Decision matches selection and relative probability");
        check(details.eligible == 2 && details.excluded == 2, "Decision candidate counts include zero exclusions");
        check(details.draw == 0.5 && details.lower == 0.25 && details.upper == 1.0, "Decision explains the actual weighted roll");
        const auto no_choice = choose_details({0, 0}, 0.5);
        check(!no_choice.index && no_choice.eligible == 0 && no_choice.excluded == 2, "Explain an all-zero stop");
        rejects([] { choose({-1}, 0.0); });
        rejects([] { choose({1}, 1.0); });
        const std::vector<std::string> tracks{"A", "B", "C", "zero"};
        const std::vector<double> values{10, 1, 25, 0};
        check(cooldown_pool(tracks, values, {"A", "B"}, 0).indices == std::vector<std::size_t>({0, 1, 2, 3}), "Cooldown zero preserves the original weighted pool");
        const auto cooling = cooldown_pool(tracks, values, {"A", "B"}, 2);
        check(!cooling.fallback && cooling.blocked == 2 && cooling.indices == std::vector<std::size_t>({2, 3}), "Cooldown blocks the last N playback positions, retaining zero exclusion reporting");
        check(cooldown_pool(tracks, values, {"A", "B", "C"}, 2).indices == std::vector<std::size_t>({0, 3}), "Track returns exactly after N other tracks have started");
        check(cooldown_pool(tracks, values, {"A", "B", "zero"}, 2).indices == std::vector<std::size_t>({0, 2, 3}), "Manually played zero-weight tracks advance time but remain unselectable");
        const auto short_pool = cooldown_pool(tracks, values, {"A", "B", "C"}, 3);
        check(short_pool.fallback && short_pool.blocked == 0 && short_pool.distinct_positive == 3 && short_pool.indices.size() == 4, "N positive tracks cannot satisfy N cooldown; fallback preserves weights and zeros");
        const auto duplicates = cooldown_pool({"A", "A", "B", "zero"}, {10, 10, 1, 0}, {"A"}, 1);
        check(!duplicates.fallback && duplicates.distinct_positive == 2 && duplicates.indices == std::vector<std::size_t>({2, 3}), "Duplicate playlist entries share filename cooldown");
        check(cooldown_pool({"A", "A", "B", "zero"}, {10, 10, 1, 0}, {}, 2).fallback, "Duplicates and zero entries cannot inflate cooldown capacity");
        check(!cooldown_pool({"zero"}, {0}, {"zero"}, 10).fallback, "All-zero playlist stays an explicit no-eligible-tracks stop");
        std::vector<std::string> eleven;
        for (int i = 0; i < 11; ++i) eleven.push_back(std::to_string(i));
        const std::vector<double> eleven_weights(11, 10);
        check(cooldown_pool(eleven, eleven_weights, std::vector<std::string>(eleven.begin(), eleven.begin() + 10), 10).indices == std::vector<std::size_t>({10}), "Ten-track cooldown keeps a favorite out until ten other starts");
        check(cooldown_pool(eleven, eleven_weights, eleven, 10).indices == std::vector<std::size_t>({0}), "Ten-track cooldown returns the first track exactly at its boundary");
        check(cooldown_pool({}, {}, {}, 10).indices.empty(), "Empty cooldown pool");
        rejects([] { cooldown_pool({"A"}, {}, {}, 1); });
        rejects([] { cooldown_pool({"A"}, {-1}, {}, 1); });
        std::mt19937_64 random(123456);
        int first = 0;
        for (int i = 0; i < 100000; ++i) {
            auto selected = choose({0, 1, 3, 0}, random);
            check(selected == 1 || selected == 2, "Zero selected in random sample");
            if (*selected == 1) ++first;
        }
        check(first > 24000 && first < 26000, "Weighted sample must approximate 1:3");
        std::cout << "Passed " << checks << " checks; 1:3 sample: " << first << ":" << 100000 - first << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
