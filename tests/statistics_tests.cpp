#include "statistics.h"
#include "statistics_files.h"
#include <cmath>
#include <fstream>
#include <iostream>
#include <random>
#include <sstream>
#include <stdexcept>

using namespace ignorant;
void check(bool value,const char* message) {if(!value)throw std::runtime_error(message);}
StatRecord play(const std::string& id="android-session/l/2026-11-10") {
    StatRecord r;r.id=id;r.kind="L";r.key="Artist/Track.flac";r.title="Track, \"quoted\"";r.artist="Artist";r.session="android-session";r.day="2026-11-10";r.started=1000;r.updated=31000;r.milliseconds=30000;r.plays=1;return r;
}
int main() {try {
    StatisticsLedger a,b;a.put(play());auto newer=play();newer.milliseconds=40000;newer.updated=41000;a.put(newer);
    b.put(play("windows-session/l/2026-11-10"));a.merge(b);a.merge(b);
    check(summarize(a.active()).plays==2,"Merging duplicate files must not double-count");
    check(summarize(a.active()).milliseconds==70000,"Keep cumulative latest session revision");
    check(StatisticsLedger::parse(a.text()).text()==a.text(),"Portable codec round trip");
    auto reverse=b;reverse.merge(a);check(reverse.text()==a.text(),"Commutative merge");
    a.clear("windows",50000);auto old=reverse;auto changed=play();changed.milliseconds=45000;changed.updated=46000;old.put(changed);a.merge(old);
    check(a.active().empty(),"Cleared known sessions must not resurrect after later revisions");
    a.put(play("offline-new/l/2026-11-10"));check(a.active().size()==1,"Previously unseen offline plays remain distinct");
    auto bad=play();bad.key="../outside.mp3";bool rejected=false;try {a.put(bad);}catch(const std::exception&){rejected=true;}check(rejected,"Reject unsafe relative paths");
    check(learning_weight(0,1,0,10,true,1,true,.15,.15)==0,"Learning never resurrects exclusions");
    check(learning_weight(5,1,0,10,true,.5,true,.1,.15)>5,"Balance and smart combine in shared core");
    std::vector<StatRecord> feedback;
    for(int i=0;i<100;++i) {auto f=play(std::to_string(i));f.kind="F";f.session=std::to_string(i);f.updated=i;f.value=.01;f.label="COMPLETED";feedback.push_back(f);}
    auto negative=feedback.back();negative.id="negative";negative.updated=1000;negative.value=-.02;feedback.push_back(negative);
    check(std::abs(smart_scores(feedback,.15).at("Artist/Track.flac")-.13)<1e-10,"Negative feedback immediately reduces saturated synchronized score");
    std::vector<StatRecord> observations;ListeningObserver observer([&](const auto& r){observations.push_back(r);});LearningOptions options;options.smart=true;
    ListeningSample s;s.key="Artist/Track.flac";s.title="Track";s.session="observed";s.day="2026-11-10";s.playing=true;s.duration=100000;
    for(int i=0;i<=30;++i) {s.elapsed=1000+i*1000;s.wall=100000+i*1000;s.position=i*1000;observer.observe(s,options,true);observer.observe(s,options,true);}
    StatisticsLedger observed;for(const auto& r:observations)observed.put(r);
    check(summarize(observed.active()).milliseconds==30000 && summarize(observed.active()).plays==1,"Observed qualification and duplicate callbacks");
    s.position=90000;s.elapsed+=1000;s.wall+=1000;observer.observe(s,options,true);
    for(const auto& r:observations)check(r.kind!="F" || r.label!="COMPLETED","Seek cannot earn completion");
    // Midnight, pauses, disconnects, early stops, and repeat sessions preserve observed totals.
    StatisticsLedger boundary;ListeningObserver midnight([&](const auto& r){boundary.put(r);});
    ListeningSample m;m.key="song.flac";m.session="midnight";m.day="2026-11-09";m.elapsed=1000;m.wall=100000;m.position=0;m.duration=100000;m.playing=true;
    midnight.observe(m,options,true);m.elapsed=2000;m.wall=101000;m.position=1000;m.day="2026-11-10";m.day_start_wall=100400;midnight.observe(m,options,true);
    check(summarize(boundary.active()).days.at("2026-11-09")==400 && summarize(boundary.active()).days.at("2026-11-10")==600,"Split observed interval at local midnight");
    m.playing=false;m.elapsed+=1000;m.wall+=1000;midnight.observe(m,options,true);m.elapsed+=60000;m.wall+=60000;midnight.observe(m,options,true);m.playing=true;midnight.observe(m,options,true);m.elapsed+=1000;m.wall+=1000;m.position+=1000;midnight.observe(m,options,true);
    check(summarize(boundary.active()).milliseconds==2000,"Paused time is excluded");
    m.elapsed+=500;m.wall+=500;m.stopped=true;midnight.observe(m,options,true);
    check(summarize(boundary.active()).exits==1,"Observed early stop is counted");
    check(smart_scores(boundary.active(),.15).empty(),"Stop does not assume user dislike");
    midnight.reset();m.stopped=false;m.session="restart";m.elapsed+=60000;m.wall+=60000;midnight.observe(m,options,true);
    check(summarize(boundary.active()).milliseconds==2000,"Restart does not invent disconnected time");
    for(const auto* day:{"2026-02-30","0000-01-01","2026-aa-10"}) {auto invalid=play();invalid.day=day;bool caught=false;try{StatisticsLedger l;l.put(invalid);}catch(...){caught=true;}check(caught,"Reject invalid calendar dates consistently");}
    StatisticsLedger accelerated;ListeningObserver fast([&](const auto& r){accelerated.put(r);});
    ListeningSample rate;rate.key="fast.flac";rate.session="fast";rate.day="2026-11-10";rate.playing=true;rate.speed=1.5;rate.duration=100000;
    for(int i=0;i<=30;++i){rate.elapsed=1000+i*1000;rate.wall=100000+i*1000;rate.position=i*1500;fast.observe(rate,options,true);}
    check(summarize(accelerated.active()).milliseconds==30000 && summarize(accelerated.active()).plays==1,"Playback rate validates media progress but credits wall listening time");
    StatisticsLedger rounded;ListeningObserver callbacks([&](const auto& r){rounded.put(r);});
    ListeningSample full;full.key="full.flac";full.session="full";full.day="2026-11-10";full.playing=true;full.duration=10000;
    for(int i=0;i<=10;++i){full.elapsed=1000+i*1000;full.wall=100000+i*1000;full.position=i*1000;callbacks.observe(full,options,true);full.elapsed+=5;full.wall+=5;callbacks.observe(full,options,true);}
    check(summarize(rounded.active()).milliseconds==10000 && std::abs(smart_scores(rounded.active(),.15).at("full.flac")-.005)<1e-10,"Rounded duplicate callbacks neither count time nor reject a full listen");
    const auto root=std::filesystem::temp_directory_path()/std::filesystem::path("ignorant-statistics-test-"+std::to_string(std::random_device{}()));std::filesystem::create_directories(root);
    StatisticsLedger android,windows;android.put(play());windows.put(play("windows-session/l/2026-11-10"));
    sync_statistics(root,"android",android);const auto merged=sync_statistics(root,"windows",windows);
    check(summarize(merged.active()).plays==2,"Read both device shards beside weights");
    check(sync_statistics(root,"android",android).text()==merged.text(),"Repeated sync is idempotent");
    {std::ifstream fixture(STATISTICS_FIXTURE,std::ios::binary);std::ostringstream text;text<<fixture.rdbuf();check(StatisticsLedger::parse(text.str()).active().size()==2,"Cross-platform golden fixture");}
    std::filesystem::remove_all(root);
    std::cout<<"Portable statistics merge, clear, learning, observation and files passed\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
