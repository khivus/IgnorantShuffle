#include "update_model.h"
#include <algorithm>
#include <cctype>
#include <map>
#include <stdexcept>
#include <vector>

namespace ignorant {
namespace {
std::string lower(std::string value) {
    for (auto& c : value) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return value;
}
struct Json {
    enum Kind { Null, Object, Array, String, Number, Boolean } kind = Null;
    std::string value;
    std::map<std::string, Json> object;
    std::vector<Json> array;
    const Json& at(const std::string& key) const {
        static const Json empty;
        const auto found = object.find(key); return found == object.end() ? empty : found->second;
    }
    std::string text() const { return kind == String ? value : std::string(); }
    bool yes() const { return kind == Boolean && value == "true"; }
};
class Parser {
    const std::string& source_; std::size_t position_ = 0;
    [[noreturn]] void invalid() const { throw std::runtime_error("GitHub returned invalid release JSON."); }
    void space() { while (position_ < source_.size() && std::isspace(static_cast<unsigned char>(source_[position_]))) ++position_; }
    bool take(char c) { space(); if (position_ < source_.size() && source_[position_] == c) { ++position_; return true; } return false; }
    unsigned hex4() {
        unsigned value = 0;
        for (int i = 0; i < 4; ++i) {
            if (position_ == source_.size()) invalid();
            const auto c = source_[position_++];
            const int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) invalid(); value = value * 16 + static_cast<unsigned>(digit);
        }
        return value;
    }
    void unicode(std::string& output) {
        unsigned value = hex4();
        if (value >= 0xd800 && value <= 0xdbff) {
            if (position_ + 2 > source_.size() || source_.compare(position_, 2, "\\u")) invalid();
            position_ += 2; const auto tail = hex4();
            if (tail < 0xdc00 || tail > 0xdfff) invalid();
            value = 0x10000 + (value - 0xd800) * 1024 + tail - 0xdc00;
        } else if (value >= 0xdc00 && value <= 0xdfff) invalid();
        if (value < 0x80) output += static_cast<char>(value);
        else if (value < 0x800) { output += static_cast<char>(0xc0 | value >> 6); output += static_cast<char>(0x80 | (value & 63)); }
        else if (value < 0x10000) { output += static_cast<char>(0xe0 | value >> 12); output += static_cast<char>(0x80 | (value >> 6 & 63)); output += static_cast<char>(0x80 | (value & 63)); }
        else { output += static_cast<char>(0xf0 | value >> 18); output += static_cast<char>(0x80 | (value >> 12 & 63)); output += static_cast<char>(0x80 | (value >> 6 & 63)); output += static_cast<char>(0x80 | (value & 63)); }
    }
    std::string string() {
        if (!take('"')) invalid(); std::string output;
        while (position_ < source_.size()) {
            const auto c = source_[position_++];
            if (c == '"') return output;
            if (static_cast<unsigned char>(c) < 32) invalid();
            if (c != '\\') { output += c; continue; }
            if (position_ == source_.size()) invalid();
            switch (source_[position_++]) {
            case '"': output += '"'; break; case '\\': output += '\\'; break; case '/': output += '/'; break;
            case 'n': output += '\n'; break; case 'r': output += '\r'; break; case 't': output += '\t'; break;
            case 'b': output += '\b'; break; case 'f': output += '\f'; break; case 'u': unicode(output); break;
            default: invalid();
            }
        }
        invalid();
    }
    Json value(unsigned depth) {
        if (depth > 64) invalid(); space(); Json result;
        if (take('{')) {
            result.kind = Json::Object;
            if (take('}')) return result;
            do { const auto key = string(); if (!take(':')) invalid(); if (!result.object.emplace(key, value(depth + 1)).second) invalid(); } while (take(','));
            if (!take('}')) invalid(); return result;
        }
        if (take('[')) {
            result.kind = Json::Array;
            if (take(']')) return result;
            do { result.array.push_back(value(depth + 1)); } while (take(','));
            if (!take(']')) invalid(); return result;
        }
        if (position_ == source_.size()) invalid();
        if (source_[position_] == '"') { result.kind = Json::String; result.value = string(); return result; }
        for (const auto* literal : {"true", "false", "null"}) {
            const std::string token(literal);
            if (source_.compare(position_, token.size(), token) == 0) {
                position_ += token.size(); result.kind = token == "null" ? Json::Null : Json::Boolean; result.value = token; return result;
            }
        }
        const auto start = position_;
        if (source_[position_] == '-') ++position_;
        if (position_ == source_.size()) invalid();
        if (source_[position_] == '0') ++position_;
        else { if (source_[position_] < '1' || source_[position_] > '9') invalid(); while (position_ < source_.size() && std::isdigit(static_cast<unsigned char>(source_[position_]))) ++position_; }
        if (position_ < source_.size() && source_[position_] == '.') {
            const auto begin = ++position_;
            while (position_ < source_.size() && std::isdigit(static_cast<unsigned char>(source_[position_]))) ++position_;
            if (begin == position_) invalid();
        }
        if (position_ < source_.size() && (source_[position_] == 'e' || source_[position_] == 'E')) {
            ++position_; if (position_ < source_.size() && (source_[position_] == '+' || source_[position_] == '-')) ++position_;
            const auto begin = position_;
            while (position_ < source_.size() && std::isdigit(static_cast<unsigned char>(source_[position_]))) ++position_;
            if (begin == position_) invalid();
        }
        result.kind = Json::Number; result.value = source_.substr(start, position_ - start); return result;
    }
public:
    explicit Parser(const std::string& source) : source_(source) {}
    Json parse() { if (source_.size() > 2 * 1024 * 1024) invalid(); auto result = value(0); space(); if (position_ != source_.size()) invalid(); return result; }
};
}
std::string normalize_repository(std::string value) {
    const auto begin = value.find_first_not_of(" \t\r\n"), end = value.find_last_not_of(" \t\r\n");
    value = begin == std::string::npos ? "" : value.substr(begin, end - begin + 1);
    if (value.empty()) return {};
    if (lower(value).compare(0, 19, "https://github.com/") == 0) value.erase(0, 19);
    while (!value.empty() && value.back() == '/') value.pop_back();
    for (const std::string suffix : {"/releases/latest", "/releases", ".git"})
        if (value.size() >= suffix.size() && value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0) value.resize(value.size() - suffix.size());
    const auto slash = value.find('/');
    if (slash == std::string::npos || slash == 0 || slash + 1 == value.size() || value.find('/', slash + 1) != std::string::npos)
        throw std::runtime_error("Enter owner/repository or its GitHub release-page URL.");
    for (const auto part : {value.substr(0, slash), value.substr(slash + 1)}) {
        if (part == "." || part == ".." || part.size() > 100) throw std::runtime_error("Invalid GitHub repository name.");
        for (const auto c : part) if (!std::isalnum(static_cast<unsigned char>(c)) && c != '_' && c != '-' && c != '.') throw std::runtime_error("Invalid GitHub repository name.");
    }
    return value;
}
std::array<unsigned, 4> release_version(std::string value) {
    if (value.compare(0, 8, "windows/") == 0) value.erase(0, 8);
    if (!value.empty() && (value[0] == 'v' || value[0] == 'V')) value.erase(0, 1);
    std::array<unsigned, 4> result{}; unsigned count = 0; std::size_t position = 0;
    while (position < value.size() && count < result.size()) {
        if (!std::isdigit(static_cast<unsigned char>(value[position]))) throw std::runtime_error("Release tag must be vMAJOR.MINOR.PATCH (stable versions only).");
        unsigned number = 0;
        while (position < value.size() && std::isdigit(static_cast<unsigned char>(value[position]))) {
            number = number * 10 + static_cast<unsigned>(value[position++] - '0');
            if (number > 65535) throw std::runtime_error("Release version component is too large.");
        }
        result[count++] = number;
        if (position == value.size()) break;
        if (value[position++] != '.' || position == value.size()) throw std::runtime_error("Unsupported release tag.");
    }
    if (count < 3 || position != value.size()) throw std::runtime_error("Unsupported release tag.");
    return result;
}
namespace {
std::optional<ReleaseUpdate> release_object(const Json& root, const std::string& repository, const std::string& installed) {
    if (root.kind != Json::Object) throw std::runtime_error("GitHub returned no release object.");
    if (root.at("draft").yes() || root.at("prerelease").yes()) return {};
    ReleaseUpdate release; release.tag = root.at("tag_name").text();
    // Historical vX.Y.Z tags belong to Windows. Other components have namespaces.
    if (release.tag.compare(0, 9, "windows/v") != 0 &&
        (release.tag.empty() || (release.tag[0] != 'v' && release.tag[0] != 'V'))) return {};
    try { release_version(release.tag); } catch (const std::exception&) { return {}; }
    if (release_version(release.tag) <= release_version(installed)) return {};
    const auto repo = normalize_repository(repository);
    if (repo.empty()) throw std::runtime_error("Update repository is not configured.");
    release.page_url = "https://github.com/" + repo + "/releases/tag/" + release.tag;
    release.unavailable_reason = "The release has no IgnorantShuffle.aimppack asset yet.";
    const auto& assets = root.at("assets");
    if (assets.kind != Json::Array) return release;
    bool found = false;
    for (const auto& asset : assets.array) {
        const auto name = asset.at("name").text();
        const auto assetTag = release.tag.compare(0, 8, "windows/") == 0 ? release.tag.substr(8) : release.tag;
        if (name != "IgnorantShuffle.aimppack" && name != "IgnorantShuffle-" + assetTag + ".aimppack") continue;
        if (found) throw std::runtime_error("Release has ambiguous update packages."); found = true;
        if (asset.at("state").text() != "uploaded") { release.unavailable_reason = "Update package is still uploading."; continue; }
        const auto size = asset.at("size");
        if (size.kind != Json::Number || size.value.empty() || size.value.find_first_not_of("0123456789") != std::string::npos) throw std::runtime_error("Invalid update package size.");
        try { release.size = std::stoull(size.value); } catch (...) { throw std::runtime_error("Invalid update package size."); }
        if (!release.size || release.size > maximum_update_size) throw std::runtime_error("Update package exceeds the supported size limit.");
        release.asset_url = asset.at("browser_download_url").text(); release.asset_name = name;
        const auto expected = "https://github.com/" + repo + "/releases/download/" + release.tag + "/" + name;
        auto encodedTag = release.tag;
        if (encodedTag.compare(0, 8, "windows/") == 0) encodedTag.replace(7, 1, "%2F");
        const auto encoded = "https://github.com/" + repo + "/releases/download/" + encodedTag + "/" + name;
        if (lower(release.asset_url) != lower(expected) && lower(release.asset_url) != lower(encoded)) throw std::runtime_error("Update asset URL does not match the configured repository and release.");
        auto digest = lower(asset.at("digest").text());
        if (digest.size() != 71 || digest.compare(0, 7, "sha256:") || digest.find_first_not_of("0123456789abcdef", 7) != std::string::npos) {
            release.unavailable_reason = "GitHub has not supplied the package SHA256 digest yet."; continue;
        }
        release.sha256 = digest.substr(7); release.unavailable_reason.clear();
    }
    return release;
}
}
std::optional<ReleaseUpdate> parse_release(const std::string& json, const std::string& repository, const std::string& installed) {
    return release_object(Parser(json).parse(), repository, installed);
}
ReleasePage parse_release_page(const std::string& json, const std::string& repository, const std::string& installed) {
    const auto root = Parser(json).parse();
    if (root.kind != Json::Array || root.array.size() > 100) throw std::runtime_error("GitHub returned an invalid release page.");
    ReleasePage page; page.count = root.array.size();
    for (const auto& item : root.array) {
        const auto candidate = release_object(item, repository, installed);
        if (candidate && (!page.newest || release_version(candidate->tag) > release_version(page.newest->tag))) page.newest = candidate;
    }
    return page;
}
}
