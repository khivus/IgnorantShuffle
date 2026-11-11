#include "shared_settings.h"
#include <sstream>
#include <stdexcept>
#include <iostream>
void check(bool value){if(!value)throw std::runtime_error("shared settings check failed");}
int main(){try{
    ignorant::Weights v;v.load(SETTINGS_FIXTURE);const auto s=ignorant::SharedSettings::decode(v);
    check(s.default_weight==7.5 && s.minimum==.5 && s.cooldown==12 && s.finite);
    check(s.learning.balance && s.learning.balance_strength==.5 && s.learning.smart && s.learning.strength==2 && s.learning.limit==.25);
    check(s.learning.skips && !s.learning.completions && s.learning.replays && !s.learning.volume);
    check(s.encode().entries()==v.entries());
    for(const auto& bad:std::vector<std::pair<std::string,double>>{{"version",2},{"cooldown",1.5},{"cooldown",10001},{"minimum",0},{"finite",2},{"limit",.8},{"balanceStrength",2}}){
        auto broken=v;broken.set(bad.first,bad.second);bool rejected=false;try{ignorant::SharedSettings::decode(broken);}catch(...){rejected=true;}check(rejected);
    }
    auto missing=v;missing.erase("smart");bool rejected=false;try{ignorant::SharedSettings::decode(missing);}catch(...){rejected=true;}check(rejected);
    auto future=v;future.set("futureOption",42);check(ignorant::SharedSettings::decode(future).cooldown==12);
    std::cout<<"Shared settings fixture, roundtrip and validation passed\n";
}catch(const std::exception& e){std::cerr<<e.what();return 1;}}
