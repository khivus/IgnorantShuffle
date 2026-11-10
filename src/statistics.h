#pragma once
#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <vector>

namespace ignorant {
// Versioned UTF-8 ledger. Each device writes only its own shard alongside weights.
struct StatRecord {
    std::string id, kind, key, title, artist, session, day, label;
    std::int64_t revision = 1, started = 0, updated = 0, milliseconds = 0, plays = 0, exits = 0;
    double value = 0;
};
class StatisticsLedger {
    std::map<std::string, StatRecord> records_;
public:
    const auto& records() const { return records_; }
    void merge(const StatRecord& record);
    void merge(const StatisticsLedger& other);
    // Update a locally owned record only when its payload changes.
    void put(StatRecord record);
    std::vector<StatRecord> active() const;
    static StatisticsLedger parse(const std::string& text);
    std::string text() const;
    void clear(const std::string& origin, std::int64_t wall);
};
struct TrackStatistics {
    std::string key, title, artist;
    std::int64_t milliseconds = 0, plays = 0, exits = 0;
};
struct StatisticsSummary {
    std::map<std::string, TrackStatistics> tracks;
    std::map<std::string, std::int64_t> days, counters;
    std::vector<StatRecord> history;
    std::int64_t milliseconds = 0, plays = 0, exits = 0;
};
StatisticsSummary summarize(const std::vector<StatRecord>& records, const std::string& since = "");
std::map<std::string,double> smart_scores(const std::vector<StatRecord>& records, double limit);
double learning_weight(double base, double minimum, std::int64_t plays, double mean,
    bool balance, double balance_strength, bool smart, double score, double limit);
std::string statistics_csv(const std::vector<StatRecord>& records);
std::string statistics_json(const std::vector<StatRecord>& records);

struct LearningOptions {
    bool balance = false, smart = false, skips = true, completions = true, replays = true, volume = true;
    double balance_strength = 1, strength = 1, limit = .15;
    void validate() const;
};
struct ListeningSample {
    std::string key, title, artist, session, day;
    std::int64_t elapsed = 0, wall = 0, position = -1, duration = -1, day_start_wall = 0;
    bool playing = false, stopped = false, restarted = false, replay = false, automatic = false;
    int volume = -1, volume_max = 100;
    double speed = 1;
};
// No AIMP/OS dependencies: both platforms can verify the same accounting contract.
class ListeningObserver {
    std::function<void(const StatRecord&)> emit_;
    ListeningSample last_, start_;
    std::map<std::string, StatRecord> slices_;
    bool have_last_ = false, have_session_ = false, qualified_ = false, completed_ = false,
        completion_possible_ = true, pending_replay_ = false, volume_rewarded_ = false, pending_volume_ = false;
    std::int64_t heard_ = 0, volume_since_ = 0, last_credit_ = -1, credit_position_ = 0;
    int previous_volume_ = -1, volume_max_ = -1;
    double media_ = 0;
    double session_gain_ = 0;
    void signal(const std::string& kind, const ListeningSample& sample, double amount, const LearningOptions& options);
    void finish(const ListeningSample& sample, bool early, const LearningOptions& options, bool penalty = true);
public:
    explicit ListeningObserver(std::function<void(const StatRecord&)> emit) : emit_(std::move(emit)) {}
    void reset();
    void gap();
    void observe(const ListeningSample& sample, const LearningOptions& options, bool collecting);
};
}
