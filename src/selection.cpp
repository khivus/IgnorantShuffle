#include "weights.h"
#include <cmath>
#include <set>
#include <stdexcept>

namespace ignorant {
CooldownPool cooldown_pool(const std::vector<std::string>& tracks, const std::vector<double>& weights,
    const std::vector<std::string>& recent, std::size_t cooldown) {
    if (tracks.size() != weights.size()) throw std::invalid_argument("Track and weight counts must match.");
    CooldownPool result;
    std::set<std::string> positive;
    for (std::size_t i = 0; i < weights.size(); ++i) {
        if (!std::isfinite(weights[i]) || weights[i] < 0) throw std::invalid_argument("Invalid candidate weight.");
        if (weights[i] > 0) positive.insert(tracks[i]);
    }
    result.distinct_positive = positive.size();
    result.fallback = cooldown > 0 && !positive.empty() && positive.size() <= cooldown;
    std::set<std::string> blocked;
    if (cooldown > 0 && !result.fallback) {
        const auto begin = recent.size() > cooldown ? recent.size() - cooldown : 0;
        for (std::size_t i = begin; i < recent.size(); ++i)
            if (positive.count(recent[i])) blocked.insert(recent[i]);
    }
    result.blocked = blocked.size();
    for (std::size_t i = 0; i < tracks.size(); ++i)
        if (weights[i] == 0 || !blocked.count(tracks[i])) result.indices.push_back(i);
    return result;
}
FinitePool finite_pool(const std::vector<std::string>& tracks, const std::vector<double>& weights,
    const std::vector<std::string>& played) {
    if (tracks.size() != weights.size()) throw std::invalid_argument("Track and weight counts differ.");
    const std::set<std::string> used(played.begin(), played.end());
    std::set<std::string> seen;
    FinitePool result;
    for (std::size_t i = 0; i < tracks.size(); ++i) {
        if (!std::isfinite(weights[i]) || weights[i] < 0) throw std::invalid_argument("Invalid candidate weight.");
        if (!seen.insert(tracks[i]).second) continue;
        if (weights[i] > 0) {
            ++result.positive;
            if (used.count(tracks[i])) { ++result.played; continue; }
        }
        result.indices.push_back(i);
    }
    return result;
}
Selection choose_details(const std::vector<double>& weights, double unit) {
    if (!std::isfinite(unit) || unit < 0 || unit >= 1) throw std::invalid_argument("Draw must be in [0, 1).");
    Selection result;
    result.draw = unit;
    double maximum = 0;
    for (double weight : weights) {
        if (!std::isfinite(weight) || weight < 0) throw std::invalid_argument("Invalid candidate weight.");
        if (weight > 0) ++result.eligible;
        else ++result.excluded;
        if (weight > maximum) maximum = weight;
    }
    if (maximum == 0) return result;
    long double total = 0;
    for (double weight : weights) total += static_cast<long double>(weight) / maximum;
    const auto target = static_cast<long double>(unit) * total;
    long double cumulative = 0;
    std::optional<std::size_t> last;
    for (std::size_t i = 0; i < weights.size(); ++i) {
        if (weights[i] == 0) continue;
        last = i;
        result.lower = static_cast<double>(cumulative / total);
        cumulative += static_cast<long double>(weights[i]) / maximum;
        result.upper = static_cast<double>(cumulative / total);
        if (target < cumulative) { result.index = i; break; }
    }
    if (!result.index) result.index = last; // Floating-point rounding at the upper boundary.
    result.weight = weights[*result.index];
    result.probability = static_cast<double>((static_cast<long double>(result.weight) / maximum) / total);
    return result;
}
std::optional<std::size_t> choose(const std::vector<double>& weights, double unit) {
    return choose_details(weights, unit).index;
}

Plan plan_next(const std::vector<std::string>& keys, const std::vector<double>& weights,
    const std::vector<std::string>& recent, const std::vector<std::string>& played,
    std::size_t cooldown, bool finite, const std::string& avoid, double unit) {
    Plan result;
    std::vector<std::size_t> indices;
    if (finite) {
        const auto pool = finite_pool(keys, weights, played);
        indices = pool.indices;
        result.remaining = pool.positive - pool.played;
    } else {
        const auto pool = cooldown_pool(keys, weights, recent, cooldown);
        indices = pool.indices;
        result.blocked = pool.blocked;
        result.cooldown_fallback = pool.fallback;
        result.remaining = pool.distinct_positive;
    }
    bool alternative = false;
    if (!avoid.empty()) for (auto i : indices)
        if (weights[i] > 0 && keys[i] != avoid) alternative = true;
    std::vector<double> candidates;
    std::vector<std::size_t> mapping;
    for (auto i : indices) {
        if (alternative && keys[i] == avoid) continue;
        mapping.push_back(i);
        candidates.push_back(weights[i]);
    }
    result.selection = choose_details(candidates, unit);
    if (result.selection.index) result.index = mapping[*result.selection.index];
    return result;
}
}
