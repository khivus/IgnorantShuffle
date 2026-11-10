#pragma once
#include "statistics.h"
#include <filesystem>
#include <future>
#include <set>

namespace ignorant {
std::string statistics_guid();
std::int64_t statistics_wall();
std::string statistics_day(int days_ago=0);
class LibraryStatistics {
    struct Root {std::filesystem::path path;StatisticsLedger own,merged;bool active=true;};
    struct Result {std::filesystem::path path;StatisticsLedger own,merged;std::string error;};
    std::map<std::filesystem::path,Root> roots_;
    std::map<std::string,std::filesystem::path> sessions_;
    std::filesystem::path cache_;
    std::string origin_,run_=statistics_guid(),session_,current_;
    std::future<std::vector<Result>> worker_;
    std::uint64_t next_sync_=0;
    std::int64_t last_start_=-1,last_start_position_=-1;
    bool learning_dirty_=true;
    std::map<std::string,std::int64_t> counts_;
    std::map<std::string,double> scores_;
    ListeningObserver observer_;
    ListeningSample last_observation_;
    void record(const StatRecord& record);
    void learning();
    std::filesystem::path cache_root(const std::filesystem::path& root) const;
public:
    LearningOptions options;
    bool collecting=true;
    std::string status="Add music roots to save statistics alongside weights";
    LibraryStatistics();
    ~LibraryStatistics();
    void configure(const std::string& origin,const std::filesystem::path& cache,const std::vector<std::filesystem::path>& roots);
    std::pair<std::filesystem::path,std::string> location(const std::string& absolute) const;
    std::string identity(const std::string& absolute) const;
    void observe(ListeningSample sample,const std::string& absolute);
    void poll(bool force=false);
    void finish();
    void pause(bool value);
    void clear();
    void counter(const std::string& label,const std::string& track="");
    std::vector<StatRecord> records() const;
    std::int64_t plays(const std::string& absolute);
    double score(const std::string& absolute);
    double mean(const std::vector<std::string>& absolute);
    void options_changed();
};
}
