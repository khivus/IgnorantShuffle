#include "windows_statistics.h"
#include "statistics_files.h"
#include <windows.h>
#include <objbase.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <sstream>

namespace ignorant {
std::string statistics_guid() {
    GUID id{};if(FAILED(CoCreateGuid(&id)))throw std::runtime_error("Cannot create statistics identity");
    wchar_t value[64]{};StringFromGUID2(id,value,64);std::string result;
    for(const wchar_t c:std::wstring(value))if(c!='{' && c!='}')result+=static_cast<char>(c);
    return result;
}
std::int64_t statistics_wall() {return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();}
std::string statistics_day(int days_ago) {
    const auto now=std::time(nullptr);std::tm local{};localtime_s(&local,&now);local.tm_hour=12;local.tm_mday-=days_ago;std::mktime(&local);
    std::ostringstream out;out<<std::put_time(&local,"%Y-%m-%d");return out.str();
}
namespace {
StatisticsLedger read_ledger(const std::filesystem::path& file) {
    if(!std::filesystem::exists(file))return {};
    if(std::filesystem::file_size(file)>32*1024*1024)throw std::runtime_error("Statistics shard is too large");
    std::ifstream input(file,std::ios::binary);if(!input)throw std::runtime_error("Statistics cache unavailable");
    std::ostringstream text;text<<input.rdbuf();return StatisticsLedger::parse(text.str());
}
}
LibraryStatistics::LibraryStatistics():observer_([this](const auto& r){record(r);}) {}
LibraryStatistics::~LibraryStatistics() {try {finish();}catch(...) {}}
std::filesystem::path LibraryStatistics::cache_root(const std::filesystem::path& root) const {
    std::uint64_t hash=1469598103934665603ull;for(unsigned char c:root.generic_u8string()){hash^=c;hash*=1099511628211ull;}
    return cache_/std::to_string(hash);
}
void LibraryStatistics::configure(const std::string& origin,const std::filesystem::path& cache,const std::vector<std::filesystem::path>& roots) {
    statistics_shard(origin);origin_=origin;cache_=cache;
    for(auto& pair:roots_)pair.second.active=false;
    for(const auto& path:roots) {
        auto found=roots_.find(path);
        if(found==roots_.end()) {
            Root root;root.path=path;
            try {root.own=read_ledger(cache_root(path)/statistics_shard("own"));root.merged=read_ledger(cache_root(path)/statistics_shard("merged"));root.merged.merge(root.own);}
            catch(const std::exception& e){status=e.what();}
            roots_[path]=std::move(root);
        } else found->second.active=true;
    }
    learning_dirty_=true;next_sync_=0;
}
std::pair<std::filesystem::path,std::string> LibraryStatistics::location(const std::string& absolute) const {
    const auto file=std::filesystem::u8path(absolute).lexically_normal();
    if(!file.is_absolute())return {};
    for(const auto& pair:roots_) {
        if(!pair.second.active)continue;
        auto f=file.begin();bool matches=true;
        for(const auto& part:pair.first) {if(part.empty())continue;
            if(f==file.end() || CompareStringOrdinal(f->c_str(),-1,part.c_str(),-1,TRUE)!=CSTR_EQUAL){matches=false;break;}++f;
        }
        if(matches) {std::filesystem::path relative;for(;f!=file.end();++f)relative/=*f;if(!relative.empty())return {pair.first,relative.generic_u8string()};}
    }
    return {};
}
std::string LibraryStatistics::identity(const std::string& absolute) const {const auto found=location(absolute);return found.first.empty()?absolute:(found.first/std::filesystem::u8path(found.second)).generic_u8string();}
void LibraryStatistics::record(const StatRecord& r) {
    const auto where=sessions_.find(r.session);if(where==sessions_.end())return;
    auto& root=roots_.at(where->second);
    const auto before=root.own.records().find(r.id);
    if(r.kind=="F" || (r.plays>0 && (before==root.own.records().end() || before->second.plays==0)))learning_dirty_=true;
    root.own.put(r);root.merged.merge(root.own.records().at(r.id));
}
void LibraryStatistics::observe(ListeningSample s,const std::string& absolute) {
    const auto found=location(absolute);
    if(found.first.empty()) {observer_.reset();current_.clear();return;}
    const auto canonical=identity(absolute);
    if(s.restarted && current_==canonical && last_start_>=0 && s.elapsed>=last_start_ && s.elapsed-last_start_<=500 && std::abs(s.position-last_start_position_)<=500)s.restarted=false;
    if(s.restarted){last_start_=s.elapsed;last_start_position_=s.position;}
    const bool wrapped=current_==canonical && s.playing && last_observation_.playing && s.elapsed>last_observation_.elapsed && last_observation_.duration>0 && last_observation_.position>=last_observation_.duration-1500 && s.position>=0 && s.position<=1000;
    if(current_!=canonical || s.restarted || wrapped || session_.empty()) {current_=canonical;session_=statistics_guid();}
    s.session=session_;s.key=found.second;sessions_[session_]=found.first;
    observer_.observe(s,options,collecting);last_observation_=s;
    if(s.stopped){current_.clear();session_.clear();}
}
void LibraryStatistics::poll(bool force) {
    if(origin_.empty() || cache_.empty())return;
    if(worker_.valid() && worker_.wait_for(std::chrono::milliseconds(0))==std::future_status::ready) {
        auto results=worker_.get();std::string errors;
        for(const auto& result:results) {
            auto found=roots_.find(result.path);if(found==roots_.end())continue;
            found->second.own.merge(result.own);found->second.merged.merge(result.merged);found->second.merged.merge(found->second.own);
            if(!result.error.empty())errors+=result.error+"; ";
        }
        status=errors.empty()?"Statistics saved alongside weights. Sync IgnorantShuffle.statistics.*.tsv with the music roots.":"Statistics sync pending; cache retained: "+errors;
        learning_dirty_=true;
    }
    if(worker_.valid() || (!force && GetTickCount64()<next_sync_))return;
    next_sync_=GetTickCount64()+10000;
    if(roots_.empty())return;
    std::vector<Root> snapshot;for(const auto& pair:roots_)snapshot.push_back(pair.second);
    const auto origin=origin_;const auto cache=cache_;
    worker_=std::async(std::launch::async,[snapshot,origin,cache]() mutable {
        std::vector<Result> result;
        for(auto& root:snapshot) {
            std::uint64_t hash=1469598103934665603ull;for(unsigned char c:root.path.generic_u8string()){hash^=c;hash*=1099511628211ull;}
            const auto directory=cache/std::to_string(hash);Result item;item.path=root.path;item.own=root.own;item.merged=root.merged;
            try {
                if(root.active) {
                    item.own.merge(read_ledger(root.path/statistics_shard(origin)));
                    item.merged.merge(sync_statistics(root.path,origin,item.own));
                }
            }catch(const std::exception& e){item.error=e.what();}
            try {std::filesystem::create_directories(directory);sync_statistics(directory,"own",item.own);sync_statistics(directory,"merged",item.merged);}
            catch(const std::exception& e){item.error+=" Cache: ";item.error+=e.what();}
            result.push_back(std::move(item));
        }
        return result;
    });
}
void LibraryStatistics::finish() {
    if(worker_.valid()){worker_.wait();next_sync_=GetTickCount64()+10000;poll();}
    poll(true);
    if(worker_.valid()) {worker_.wait();next_sync_=GetTickCount64()+10000;poll();}
    // poll may start the next periodic task only after ten seconds, so no task remains.
    observer_.reset();current_.clear();session_.clear();
}
void LibraryStatistics::pause(bool value) {collecting=!value;observer_.gap();}
void LibraryStatistics::clear() {
    if(worker_.valid()){worker_.wait();next_sync_=GetTickCount64()+10000;poll();}
    for(auto& pair:roots_) {
        auto& root=pair.second;auto cleared=root.merged;cleared.clear(origin_,statistics_wall());
        for(const auto& r:cleared.records())if(r.second.kind=="X" && !root.merged.records().count(r.first))root.own.merge(r.second);
        root.merged.merge(root.own);
        const auto directory=cache_root(pair.first);std::filesystem::create_directories(directory);
        sync_statistics(directory,"own",root.own);sync_statistics(directory,"merged",root.merged);
    }
    observer_.reset();current_.clear();session_.clear();run_=statistics_guid();learning_dirty_=true;poll(true);
}
void LibraryStatistics::counter(const std::string& label,const std::string& track) {
    if(!collecting)return;
    auto found=location(track);if(found.first.empty())for(const auto& pair:roots_)if(pair.second.active){found={pair.first,"@shuffle"};break;}
    if(found.first.empty())return;
    auto& root=roots_.at(found.first);StatRecord r;r.id=run_+"/c/"+statistics_day()+"/"+label;r.kind="C";r.key="@shuffle";r.session="counter:"+run_;r.day=statistics_day();r.label=label;
    if(std::any_of(root.merged.records().begin(),root.merged.records().end(),[&](const auto& item){return item.second.kind=="X" && item.second.key==r.id;})) {
        run_=statistics_guid();r.id=run_+"/c/"+statistics_day()+"/"+label;r.session="counter:"+run_;
    }
    const auto old=root.own.records().find(r.id);r.value=old==root.own.records().end()?1:old->second.value+1;
    root.own.put(r);root.merged.merge(root.own.records().at(r.id));
}
std::vector<StatRecord> LibraryStatistics::records() const {
    StatisticsLedger combined;std::map<std::string,std::filesystem::path> owner;
    for(const auto& pair:roots_)for(const auto& item:pair.second.merged.records()) {
        const auto old=combined.records().find(item.first);
        if(old==combined.records().end() || old->second.revision<item.second.revision)owner[item.first]=pair.first;
        combined.merge(item.second);
    }
    auto records=combined.active();for(auto& r:records)r.key=(owner.at(r.id)/std::filesystem::u8path(r.key)).generic_u8string();return records;
}
void LibraryStatistics::learning() {
    if(!learning_dirty_)return;
    const auto all=records();const auto summary=summarize(all);counts_.clear();
    for(const auto& pair:summary.tracks)counts_[pair.first]=pair.second.plays;
    scores_=smart_scores(all,options.limit);learning_dirty_=false;
}
std::int64_t LibraryStatistics::plays(const std::string& absolute) {learning();const auto found=counts_.find(identity(absolute));return found==counts_.end()?0:found->second;}
double LibraryStatistics::score(const std::string& absolute) {learning();const auto found=scores_.find(identity(absolute));return found==scores_.end()?0:found->second;}
double LibraryStatistics::mean(const std::vector<std::string>& keys) {std::set<std::string> unique(keys.begin(),keys.end());double sum=0;for(const auto& key:unique)sum+=static_cast<double>(plays(key));return unique.empty()?0:sum/unique.size();}
void LibraryStatistics::options_changed(){options.validate();learning_dirty_=true;observer_.gap();}
}
