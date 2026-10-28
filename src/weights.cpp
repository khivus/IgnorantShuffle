#include "weights.h"
#include <cmath>
#include <fstream>
#include <iomanip>
#include <limits>
#include <locale>
#include <sstream>
#include <set>
#include <stdexcept>
#ifdef _WIN32
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

namespace ignorant {
double Weights::get(const std::string& track) const {
    const auto it = values_.find(track);
    return it == values_.end() ? fallback_ : it->second;
}
void Weights::set_default(double weight) {
    if (!std::isfinite(weight) || weight < 0)
        throw std::invalid_argument("Default weight must be a finite nonnegative number.");
    fallback_ = weight;
}
void Weights::set(const std::string& track, double weight) {
    if (track.empty() || track.find_first_of("\t\r\n\0", 0, 4) != std::string::npos)
        throw std::invalid_argument("Track must be a nonempty filename or URI without control separators.");
    if (!std::isfinite(weight) || weight < 0)
        throw std::invalid_argument("Weight must be a finite nonnegative number.");
    values_[track] = weight;
}
void Weights::set_from_editor(const std::string& track, int weight) {
    if (weight < 0 || weight > 10) throw std::invalid_argument("Enter a weight from 0 to 10.");
    set(track, weight);
}
void Weights::import(std::istream& input) {
    auto merged = *this;
    std::string line;
    std::size_t number = 0;
    while (std::getline(input, line)) {
        ++number;
        if (number == 1 && line.compare(0, 3, "\xef\xbb\xbf") == 0) line.erase(0, 3);
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line.front() == '#') continue;
        try {
            const auto tab = line.find('\t');
            if (tab == std::string::npos) throw std::invalid_argument("Expected weight<TAB>track.");
            std::istringstream value(line.substr(0, tab));
            value.imbue(std::locale::classic());
            double weight;
            if (!(value >> weight) || !(value >> std::ws).eof())
                throw std::invalid_argument("Invalid number.");
            merged.set(line.substr(tab + 1), weight);
        } catch (const std::exception& error) {
            throw std::runtime_error("Line " + std::to_string(number) + ": " + error.what());
        }
    }
    if (input.bad()) throw std::runtime_error("Could not read weights.");
    values_.swap(merged.values_);
}
void Weights::export_to(std::ostream& output) const {
    output.imbue(std::locale::classic());
    output << "# IgnorantShuffle weights v1: weight<TAB>track (UTF-8)\n";
    output << std::setprecision(std::numeric_limits<double>::max_digits10);
    for (const auto& [track, weight] : values_) output << weight << '\t' << track << '\n';
    if (!output) throw std::runtime_error("Could not write weights.");
}
void Weights::load(const std::filesystem::path& path) {
    if (!std::filesystem::exists(path)) return;
    std::ifstream input(path, std::ios::binary);
    if (!input) throw std::runtime_error("Could not open weights file.");
    import(input);
}
void Weights::save(const std::filesystem::path& path) const {
    auto temporary = path;
    temporary += ".tmp";
    {
        std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
        if (!output) throw std::runtime_error("Could not create weights file.");
        export_to(output);
        output.close();
        if (!output) throw std::runtime_error("Could not finish writing weights.");
    }
#ifdef _WIN32
    if (!MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
        throw std::runtime_error("Could not replace weights file.");
#else
    std::filesystem::rename(temporary, path);
#endif
}
}
