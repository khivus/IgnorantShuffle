#pragma once
#include "weights.h"
#include "statistics.h"
#include <cmath>
#include <stdexcept>

namespace ignorant {
inline constexpr auto shared_settings_filename="IgnorantShuffle.settings.tsv";
struct SharedSettings {
    double default_weight=5,minimum=1;
    int cooldown=0;
    bool finite=false;
    LearningOptions learning;
    void validate() const {
        if(!std::isfinite(default_weight) || default_weight<0 || !std::isfinite(minimum) || minimum<=0 || cooldown<0 || cooldown>10000)
            throw std::invalid_argument("Invalid shared shuffle rules");
        learning.validate();
    }
    Weights encode() const {
        validate();Weights v;
        for(const auto& p:std::map<std::string,double>{{"version",1},{"default",default_weight},{"minimum",minimum},{"cooldown",static_cast<double>(cooldown)},
            {"finite",finite?1.:0.},{"balance",learning.balance?1.:0.},{"balanceStrength",learning.balance_strength},{"smart",learning.smart?1.:0.},
            {"strength",learning.strength},{"limit",learning.limit},{"skips",learning.skips?1.:0.},{"completions",learning.completions?1.:0.},{"replays",learning.replays?1.:0.},{"volume",learning.volume?1.:0.}})v.set(p.first,p.second);
        return v;
    }
    static SharedSettings decode(const Weights& v) {
        auto n=[&](const std::string& key){const auto it=v.entries().find(key);if(it==v.entries().end())throw std::invalid_argument("Missing shared setting: "+key);return it->second;};
        auto b=[&](const std::string& key){const double x=n(key);if(x!=0 && x!=1)throw std::invalid_argument("Invalid shared flag: "+key);return x==1;};
        if(n("version")!=1)throw std::invalid_argument("Unsupported shared settings version");
        const double cooldown=n("cooldown");if(cooldown<0 || cooldown>10000 || std::floor(cooldown)!=cooldown)throw std::invalid_argument("Invalid shared cooldown");
        SharedSettings s;s.default_weight=n("default");s.minimum=n("minimum");s.cooldown=static_cast<int>(cooldown);s.finite=b("finite");
        auto& o=s.learning;o.balance=b("balance");o.balance_strength=n("balanceStrength");o.smart=b("smart");o.strength=n("strength");o.limit=n("limit");
        o.skips=b("skips");o.completions=b("completions");o.replays=b("replays");o.volume=b("volume");s.validate();return s;
    }
};
}
