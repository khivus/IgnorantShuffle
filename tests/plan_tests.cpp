#include "weights.h"
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
int main() {
    try {
        using namespace ignorant;
        const std::vector<std::string> keys{"zero", "a", "b"};
        const std::vector<double> weights{0, 2, 8};
        auto p = plan_next(keys, weights, {}, {}, 0, false, "", 0);
        check(p.index == 1 && std::abs(p.selection.probability - .2) < 1e-12, "Original indices and weighted probability");
        p = plan_next(keys, weights, {}, {}, 0, false, "", .999);
        check(p.index == 2, "Upper draw boundary");
        p = plan_next(keys, weights, {"b"}, {}, 1, false, "", .999);
        check(p.index == 1 && p.blocked == 1 && !p.cooldown_fallback, "Cooldown excludes recent plays");
        p = plan_next(keys, weights, {"b"}, {}, 2, false, "", .999);
        check(p.index == 2 && p.cooldown_fallback, "Small-list fallback preserves positive candidates");
        p = plan_next(keys, weights, {}, {}, 0, false, "b", .999);
        check(p.index == 1, "Reroll avoids prior choice when alternative exists");
        p = plan_next({"a"}, {1}, {}, {}, 0, false, "a", .999);
        check(p.index == 0, "Reroll retains only candidate");
        p = plan_next(keys, weights, {}, {"b"}, 100, true, "", .999);
        check(p.index == 1 && p.remaining == 1, "Finite pass overrides cooldown");
        p = plan_next(keys, weights, {}, {"a", "b"}, 0, true, "", .5);
        check(!p.index && p.remaining == 0, "Finite completion");
        p = plan_next({"a", "a", "b"}, {1, 1, 1}, {}, {"a"}, 0, true, "", .1);
        check(p.index == 2, "Finite duplicate identities share progress");
        p = plan_next({"a"}, {0}, {}, {}, 0, false, "", 0);
        check(!p.index, "Zero never selected");
        for (int i = 0; i < 1000; ++i) {
            p = plan_next(keys, weights, {}, {}, 0, false, "", i / 1000.0);
            check(p.index && *p.index != 0, "Zero remains excluded for every draw");
        }
        bool threw = false;
        try { plan_next({"a"}, {}, {}, {}, 0, false, "", .5); } catch (...) { threw = true; }
        check(threw, "Reject mismatched arrays");
        threw = false;
        try { plan_next({"a"}, {-1}, {}, {}, 0, false, "", .5); } catch (...) { threw = true; }
        check(threw, "Reject invalid weights");
        std::cout << "Shared plan checks passed\n";
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
