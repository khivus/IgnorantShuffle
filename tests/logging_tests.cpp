#include "logging.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>

namespace {
void check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
std::string read(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}
}
int main() {
    try {
        const auto directory = std::filesystem::current_path() / "logging-test-output";
        std::filesystem::create_directories(directory);
        const auto file = directory / "plugin.log";
        ignorant::PluginLog log;
        log.start(file);
        log.write("queue.draw", "track=music.flac weight=25 probability=0.8");
        log.write("display.waiting", "reason=missing\ncontext\r\n");
        log.write("unicode", "track=\xd0\x9c\xd1\x83\xd0\xb7\xd1\x8b\xd0\xba\xd0\xb0.flac");
        log.stop();
        const auto text = read(file);
        check(log.error().empty(), "Logging must succeed.");
        check(text.find("queue.draw track=music.flac weight=25") != std::string::npos, "Log records decisions and fields.");
        check(text.find("reason=missing\\ncontext\\r\\n") != std::string::npos, "Newlines must not inject fake log records.");
        check(text.find("\xd0\x9c\xd1\x83") != std::string::npos, "Track names remain UTF-8.");
        log.start(file, 512);
        for (int i = 0; i < 30; ++i) log.write("rotation", "record=" + std::to_string(i) + std::string(80, 'x'));
        log.stop();
        auto backup = file; backup += ".1";
        check(log.error().empty() && std::filesystem::exists(backup), "Logs rotate successfully.");
        check(std::filesystem::file_size(file) <= 512 && std::filesystem::file_size(backup) <= 512, "Both retained log files stay bounded.");
        check(read(file).find("record=29") != std::string::npos, "Stop flushes the most recent records.");
        log.start(directory / "missing-directory" / "plugin.log");
        log.write("ignored", "Logging failure must not escape into playback callbacks.");
        log.stop();
        check(!log.error().empty(), "File errors must be available to the UI.");
        std::filesystem::remove(file);
        std::filesystem::remove(backup);
        std::filesystem::remove(directory);
        std::cout << "Logging persistence, rotation, UTF-8, escaping and error isolation passed.\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
