#include "statistics.h"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <limits>
#include <set>
#include <sstream>
#include <stdexcept>

namespace ignorant {
namespace {
const char* header = "# IgnorantShuffle statistics v1";
std::string hex(const std::string& text) {
    static const char* digits="0123456789abcdef";
    std::string result;
    for (unsigned char c : text) { result+=digits[c>>4]; result+=digits[c&15]; }
    return result;
}
std::string unhex(const std::string& text) {
    if(text.size()%2) throw std::invalid_argument("Invalid statistics text encoding");
    auto digit=[](char c) { if(c>='0' && c<='9')return c-'0'; if(c>='a' && c<='f')return c-'a'+10; throw std::invalid_argument("Invalid statistics hex"); };
    std::string result;
    for(std::size_t i=0;i<text.size();i+=2) result+=static_cast<char>(digit(text[i])*16+digit(text[i+1]));
    if(result.find('\0')!=std::string::npos) throw std::invalid_argument("NUL in statistics field");
    return result;
}
std::string row(const StatRecord& r) {
    std::ostringstream out; out.imbue(std::locale::classic());
    out<<hex(r.id)<<'\t'<<r.revision<<'\t'<<r.kind<<'\t'<<hex(r.key)<<'\t'<<hex(r.title)<<'\t'<<hex(r.artist)
       <<'\t'<<hex(r.session)<<'\t'<<r.day<<'\t'<<r.started<<'\t'<<r.updated<<'\t'<<r.milliseconds<<'\t'<<r.plays
       <<'\t'<<r.exits<<'\t'<<std::setprecision(17)<<r.value<<'\t'<<hex(r.label);
    return out.str();
}
std::int64_t integer(const std::string& s) {
    std::size_t end=0; const auto n=std::stoll(s,&end); if(end!=s.size() || n<0)throw std::invalid_argument("Invalid statistics integer");return n;
}
void validate(const StatRecord& r) {
    auto date=[](const std::string& d) {
        if(d.size()!=10 || d[4]!='-' || d[7]!='-')return false;
        for(std::size_t i=0;i<d.size();++i)if(i!=4 && i!=7 && (d[i]<'0' || d[i]>'9'))return false;
        const int year=std::stoi(d.substr(0,4)),month=std::stoi(d.substr(5,2)),day=std::stoi(d.substr(8,2));
        if(year<1 || month<1 || month>12)return false;
        const int days[]{31,28,31,30,31,30,31,31,30,31,30,31};
        return day>=1 && day<=days[month-1]+(month==2 && year%4==0 && (year%100!=0 || year%400==0)?1:0);
    };
    if(r.id.empty() || r.id.size()>1024 || r.revision<1 || r.key.empty() || r.key.size()>65536 ||
       (r.kind!="L" && r.kind!="F" && r.kind!="C" && r.kind!="X") || !std::isfinite(r.value) ||
       r.started<0 || r.updated<0 || r.milliseconds<0 || r.plays<0 || r.exits<0 || r.plays>1 || r.exits>1 ||
       !date(r.day))throw std::invalid_argument("Invalid statistics record");
    if((r.kind=="C" && (r.value<0 || r.value>9e15 || std::floor(r.value)!=r.value)) || (r.kind=="F" && std::abs(r.value)>1))throw std::invalid_argument("Invalid statistics signal value");
    if(r.kind!="X" && (r.key.front()=='/' || r.key.find('\\')!=std::string::npos || r.key.find(':')!=std::string::npos || r.key.find_first_of("\t\r\n")!=std::string::npos))
        throw std::invalid_argument("Statistics require root-relative paths");
    if(r.kind!="X" && r.key.back()=='/')throw std::invalid_argument("Unsafe statistics path");
    if(r.kind!="X") { std::istringstream path(r.key);std::string part;while(std::getline(path,part,'/'))
        if(part.empty() || part=="." || part=="..")throw std::invalid_argument("Unsafe statistics path"); }
}
std::string quote(const std::string& value, bool json) {
    std::string result="\"";
    for(unsigned char c:value) {
        if(json) {
            if(c=='"' || c=='\\') {result+='\\';result+=c;}
            else if(c<32) {const char* d="0123456789abcdef";result+="\\u00";result+=d[c>>4];result+=d[c&15];}
            else result+=c;
        } else { if(c=='"')result+='"';result+=c; }
    }
    return result+'"';
}
}
void StatisticsLedger::merge(const StatRecord& r) {
    validate(r);
    auto it=records_.find(r.id);
    if(it==records_.end() || it->second.revision<r.revision) records_[r.id]=r;
    else if(it->second.revision==r.revision && row(it->second)!=row(r))throw std::invalid_argument("Conflicting statistics revision; files preserved");
}
void StatisticsLedger::merge(const StatisticsLedger& other) { for(const auto& pair:other.records_)merge(pair.second); }
void StatisticsLedger::put(StatRecord r) {
    const auto found=records_.find(r.id);
    if(found!=records_.end()) {
        r.revision=found->second.revision;
        if(row(r)==row(found->second))return;
        if(r.revision==std::numeric_limits<std::int64_t>::max())throw std::overflow_error("Statistics revision exhausted");
        ++r.revision;
    }
    merge(r);
}
std::vector<StatRecord> StatisticsLedger::active() const {
    std::set<std::string> cleared;
    for(const auto& pair:records_)if(pair.second.kind=="X")cleared.insert(pair.second.key);
    std::vector<StatRecord> result;
    for(const auto& pair:records_)if(pair.second.kind!="X" && !cleared.count(pair.first))result.push_back(pair.second);
    return result;
}
StatisticsLedger StatisticsLedger::parse(const std::string& text) {
    if(text.size()>32*1024*1024)throw std::invalid_argument("Statistics shard is too large");
    std::istringstream input(text);std::string line; StatisticsLedger result;
    if(!std::getline(input,line))throw std::invalid_argument("Missing statistics header");
    if(!line.empty() && line.back()=='\r')line.pop_back();
    if(line!=header)throw std::invalid_argument("Unsupported statistics schema");
    while(std::getline(input,line)) {
        if(!line.empty() && line.back()=='\r')line.pop_back();
        if(line.empty() || line[0]=='#')continue;
        std::vector<std::string> fields;std::size_t begin=0;
        for(;;) {const auto end=line.find('\t',begin);fields.push_back(line.substr(begin,end-begin));if(end==std::string::npos)break;begin=end+1;}
        if(fields.size()!=15)throw std::invalid_argument("Invalid statistics columns");
        StatRecord r;r.id=unhex(fields[0]);r.revision=integer(fields[1]);r.kind=fields[2];r.key=unhex(fields[3]);r.title=unhex(fields[4]);r.artist=unhex(fields[5]);
        r.session=unhex(fields[6]);r.day=fields[7];r.started=integer(fields[8]);r.updated=integer(fields[9]);r.milliseconds=integer(fields[10]);r.plays=integer(fields[11]);r.exits=integer(fields[12]);
        std::istringstream number(fields[13]);number.imbue(std::locale::classic());number>>std::noskipws>>r.value;if(!number || !number.eof())throw std::invalid_argument("Invalid statistics value");r.label=unhex(fields[14]);result.merge(r);
    }
    return result;
}
std::string StatisticsLedger::text() const { std::string result=std::string(header)+'\n';for(const auto& pair:records_)result+=row(pair.second)+'\n';return result; }
void StatisticsLedger::clear(const std::string& origin,std::int64_t wall) {
    for(const auto& r:active()) {StatRecord tomb;tomb.id="clear:"+origin+":"+std::to_string(wall)+":"+r.id;tomb.kind="X";tomb.key=r.id;tomb.day=r.day;tomb.started=tomb.updated=wall;put(tomb);}
}
StatisticsSummary summarize(const std::vector<StatRecord>& records,const std::string& since) {
    StatisticsSummary s;
    for(const auto& r:records) {
        if(r.day<since)continue;
        if(r.kind=="C") {s.counters[r.label]+=static_cast<std::int64_t>(r.value);continue;}
        if(r.kind!="L")continue;
        auto& track=s.tracks[r.key];track.key=r.key;track.title=r.title;track.artist=r.artist;
        track.milliseconds+=r.milliseconds;track.plays+=r.plays;track.exits+=r.exits;
        s.milliseconds+=r.milliseconds;s.plays+=r.plays;s.exits+=r.exits;s.days[r.day]+=r.milliseconds;s.history.push_back(r);
    }
    std::sort(s.history.begin(),s.history.end(),[](const auto& a,const auto& b){return a.updated>b.updated;});return s;
}
std::map<std::string,double> smart_scores(const std::vector<StatRecord>& records,double limit) {
    if(!std::isfinite(limit) || limit<.05 || limit>.25)throw std::invalid_argument("Invalid smart limit");
    auto sorted=records;std::sort(sorted.begin(),sorted.end(),[](const auto& a,const auto& b){return a.updated==b.updated?a.id<b.id:a.updated<b.updated;});
    std::map<std::string,double> result,budgets;
    for(const auto& r:sorted)if(r.kind=="F") {
        double step=r.value;
        if(step>0 && r.label!="migration") {step=std::min(step,std::max(0.0,.015-budgets[r.session]));budgets[r.session]+=step;}
        result[r.key]=std::clamp(result[r.key]+step,-limit,limit);
    }
    return result;
}
double learning_weight(double base,double minimum,std::int64_t plays,double mean,bool balance,double strength,bool smart,double score,double limit) {
    if(!std::isfinite(base) || base<0 || !std::isfinite(minimum) || minimum<=0 || plays<0 || !std::isfinite(mean) || mean<0 ||
       !std::isfinite(strength) || strength<.25 || strength>1 || !std::isfinite(score) || !std::isfinite(limit) || limit<.05 || limit>.25)
        throw std::invalid_argument("Invalid learning inputs");
    if(base<minimum)return 0;
    double factor=balance?std::pow(std::clamp((1+mean)/(1+static_cast<double>(plays)),.25,4.0),strength):1;
    if(smart)factor*=1+std::clamp(score,-limit,limit);
    if(factor>1 && base>std::numeric_limits<double>::max()/factor)return std::numeric_limits<double>::max()/4;
    return base*factor;
}
std::string statistics_csv(const std::vector<StatRecord>& records) {
    std::ostringstream out;out.imbue(std::locale::classic());out<<"type,id,track,title,artist,session,day,started,updated,listening_ms,qualified_plays,early_exits,value,label\r\n";
    for(const auto& r:records)out<<quote(r.kind,false)<<','<<quote(r.id,false)<<','<<quote(r.key,false)<<','<<quote(r.title,false)<<','<<quote(r.artist,false)<<','<<quote(r.session,false)<<','<<quote(r.day,false)<<','<<r.started<<','<<r.updated<<','<<r.milliseconds<<','<<r.plays<<','<<r.exits<<','<<std::setprecision(17)<<r.value<<','<<quote(r.label,false)<<"\r\n";
    return out.str();
}
std::string statistics_json(const std::vector<StatRecord>& records) {
    std::ostringstream out;out.imbue(std::locale::classic());out<<"{\"schema\":1,\"records\":[";bool first=true;
    for(const auto& r:records) {if(!first)out<<',';first=false;out<<"{\"type\":"<<quote(r.kind,true)<<",\"id\":"<<quote(r.id,true)<<",\"track\":"<<quote(r.key,true)<<",\"title\":"<<quote(r.title,true)<<",\"artist\":"<<quote(r.artist,true)<<",\"session\":"<<quote(r.session,true)<<",\"day\":"<<quote(r.day,true)<<",\"started\":"<<r.started<<",\"updated\":"<<r.updated<<",\"listening_ms\":"<<r.milliseconds<<",\"qualified_plays\":"<<r.plays<<",\"early_exits\":"<<r.exits<<",\"value\":"<<std::setprecision(17)<<r.value<<",\"label\":"<<quote(r.label,true)<<'}';}
    return out.str()+"]}";
}
void LearningOptions::validate() const {
    if(!std::isfinite(strength) || strength<.25 || strength>2 || !std::isfinite(balance_strength) || balance_strength<.25 || balance_strength>1 || !std::isfinite(limit) || limit<.05 || limit>.25)throw std::invalid_argument("Invalid learning settings");
}
void ListeningObserver::reset() {have_last_=have_session_=qualified_=completed_=pending_replay_=volume_rewarded_=pending_volume_=false;completion_possible_=true;heard_=media_=0;last_credit_=-1;slices_.clear();previous_volume_=-1;session_gain_=0;}
void ListeningObserver::gap() {have_last_=false;previous_volume_=-1;pending_volume_=false;}
void ListeningObserver::signal(const std::string& kind,const ListeningSample& s,double amount,const LearningOptions& o) {
    if(!o.smart)return;
    amount*=o.strength;
    if(amount>0) {amount=std::min(amount,std::max(0.0,.015-session_gain_));session_gain_+=amount;}
    if(amount==0)return;
    StatRecord r;r.id=start_.session+"/f/"+kind;r.kind="F";r.key=start_.key;r.title=start_.title;r.artist=start_.artist;r.session=start_.session;r.day=s.day;r.started=start_.wall;r.updated=s.wall;r.value=amount;r.label=kind;emit_(r);
}
void ListeningObserver::finish(const ListeningSample& s,bool early,const LearningOptions& o,bool penalty) {
    if(have_session_ && early && !qualified_ && heard_>0 && (last_.duration<=0 || credit_position_<last_.duration-1500)) {
        auto& r=slices_[s.day];if(r.id.empty()) {r.id=start_.session+"/l/"+s.day;r.kind="L";r.key=start_.key;r.title=start_.title;r.artist=start_.artist;r.session=start_.session;r.day=s.day;r.started=start_.wall;}
        r.updated=s.wall;r.exits=1;emit_(r);
        if(penalty && o.skips && heard_>=2000 && last_credit_>=0 && s.elapsed-last_credit_>=0 && s.elapsed-last_credit_<=2000)signal("EARLY_SKIP",s,-.02,o);
    }
}
void ListeningObserver::observe(const ListeningSample& s,const LearningOptions& o,bool collecting) {
    o.validate();
    if(!collecting) {gap();completion_possible_=false;return;}
    if(s.key.empty() || s.stopped) {finish(s,s.stopped,o,false);reset();return;}
    const bool repeat=have_session_ && s.key==start_.key && completed_ && have_last_ && last_.duration>0 && last_.position>=last_.duration-1500 && s.position<=1000 && s.position>=0;
    if(!have_session_ || start_.key!=s.key || start_.session!=s.session || s.restarted || repeat) {
        finish(s,!repeat,o,start_.key!=s.key);reset();
        if(!s.playing)return;
        have_session_=true;start_=s;pending_replay_=s.replay || (repeat && !s.automatic);
    }
    if(!s.playing || s.position<0 || !std::isfinite(s.speed) || s.speed<=0) {gap();return;}
    if(!have_last_) {last_=s;have_last_=true;return;}
    const auto dt=s.elapsed-last_.elapsed,dp=s.position-last_.position;
    if(dt==0 && dp==0)return;
    // AIMP can repeat a rounded position in several closely spaced callbacks.
    // Keep the previous baseline until actual advancement is observed.
    if(dt>0 && dt<250 && dp==0)return;
    if(dt<1 || dt>5000 || dp<=0 || s.speed!=last_.speed || std::abs(dp-dt*s.speed)>250) {completion_possible_=false;last_credit_=-1;last_=s;previous_volume_=-1;return;}
    const auto credit=std::min(dt,static_cast<std::int64_t>(dp/s.speed));heard_+=credit;media_+=credit*s.speed;last_credit_=s.elapsed;credit_position_=s.position;
    const auto threshold=s.duration>0?std::min<std::int64_t>(30000,std::max<std::int64_t>(1,s.duration/2)):30000;
    const bool qualifies=!qualified_ && heard_>=threshold;qualified_=qualified_ || qualifies;
    auto add_slice=[&](const std::string& day,std::int64_t ms,bool play) {
        auto& r=slices_[day];if(r.id.empty()) {r.id=start_.session+"/l/"+day;r.kind="L";r.key=start_.key;r.title=start_.title;r.artist=start_.artist;r.session=start_.session;r.day=day;r.started=start_.wall;}
        r.updated=s.wall;r.milliseconds+=ms;if(play)r.plays=1;emit_(r);
    };
    if(last_.day!=s.day && s.day_start_wall>last_.wall && s.day_start_wall<=s.wall && std::abs((s.wall-last_.wall)-dt)<=250) {
        const auto before=std::min(credit,s.day_start_wall-last_.wall);
        add_slice(last_.day,before,false);add_slice(s.day,credit-before,qualifies);
    } else add_slice(s.day,credit,qualifies);
    if(qualified_ && pending_replay_) {pending_replay_=false;if(o.replays)signal("REPLAYED",s,.01,o);}
    if(s.volume>=0 && s.volume_max>0 && s.volume<=s.volume_max) {
        if(previous_volume_<0 || volume_max_!=s.volume_max)volume_since_=s.elapsed;
        else if(s.volume!=previous_volume_) {if(s.elapsed-volume_since_>=5000 && s.volume>previous_volume_)pending_volume_=true;volume_since_=s.elapsed;}
        previous_volume_=s.volume;volume_max_=s.volume_max;
        if(qualified_ && pending_volume_ && !volume_rewarded_) {volume_rewarded_=true;pending_volume_=false;if(o.volume)signal("VOLUME_UP",s,.0025,o);}
    }
    if(!completed_ && completion_possible_ && s.duration>0 && media_>=s.duration*.9 && s.position>=s.duration-1500) {completed_=true;if(o.completions)signal("COMPLETED",s,.005,o);}
    last_=s;
}
}
