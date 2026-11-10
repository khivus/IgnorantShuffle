#include "statistics_files.h"
#include <fstream>
#include <sstream>
#include <stdexcept>

namespace ignorant {
std::string statistics_shard(const std::string& origin) {
    if(origin.empty() || origin.size()>128 || origin.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-")!=std::string::npos)
        throw std::invalid_argument("Invalid statistics device identity");
    return "IgnorantShuffle.statistics."+origin+".tsv";
}
bool statistics_shard_name(const std::string& name) {
    const std::string prefix="IgnorantShuffle.statistics.",suffix=".tsv";
    return name.size()>prefix.size()+suffix.size() && name.compare(0,prefix.size(),prefix)==0 && name.compare(name.size()-suffix.size(),suffix.size(),suffix)==0;
}
namespace {
std::string read(const std::filesystem::path& file) {
    if(std::filesystem::file_size(file)>32*1024*1024)throw std::runtime_error("Statistics shard is too large");
    std::ifstream in(file,std::ios::binary);if(!in)throw std::runtime_error("Cannot read statistics: "+file.u8string());
    std::ostringstream text;text<<in.rdbuf();if(in.bad())throw std::runtime_error("Statistics read failed");return text.str();
}
}
StatisticsLedger sync_statistics(const std::filesystem::path& root,const std::string& origin,const StatisticsLedger& local,bool write) {
    if(!std::filesystem::is_directory(root))throw std::runtime_error("Statistics music root is unavailable");
    const auto target=root/std::filesystem::u8path(statistics_shard(origin));
    auto backup=target;backup+=L".bak";
    if(!std::filesystem::exists(target) && std::filesystem::exists(backup))throw std::runtime_error("Statistics backup requires recovery; files preserved");
    StatisticsLedger merged,own;
    for(const auto& entry:std::filesystem::directory_iterator(root))if(entry.is_regular_file() && statistics_shard_name(entry.path().filename().u8string())) {
        auto ledger=StatisticsLedger::parse(read(entry.path()));merged.merge(ledger);if(entry.path()==target)own.merge(ledger);
    }
    own.merge(local);merged.merge(own);
    if(!write || own.records().empty())return merged;
    if(std::filesystem::exists(target) && read(target)==own.text())return merged;
    auto temporary=target;temporary+=L".tmp";
    { std::ofstream out(temporary,std::ios::binary|std::ios::trunc);out<<own.text();out.flush();if(!out)throw std::runtime_error("Statistics write failed; original kept"); }
    if(StatisticsLedger::parse(read(temporary)).text()!=own.text())throw std::runtime_error("Statistics verification failed; original kept");
    if(std::filesystem::exists(backup))throw std::runtime_error("Statistics backup exists; restore it before saving");
    const bool existed=std::filesystem::exists(target);
    if(existed)std::filesystem::rename(target,backup);
    try {std::filesystem::rename(temporary,target);}catch(...) {if(existed)std::filesystem::rename(backup,target);throw;}
    if(existed)std::filesystem::remove(backup);
    return merged;
}
}
