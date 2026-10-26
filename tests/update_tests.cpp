#include <winsock2.h>
#include <windows.h>
#include <shellapi.h>
#include "update_client.h"
#include "update_install.h"
#include <chrono>
#include <fstream>
#include <iostream>
#include <thread>

using namespace ignorant;
namespace {
void check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
template<class F> void rejects(F action, const char* message) {
    bool failed = false; try { action(); } catch (const std::exception&) { failed = true; } check(failed, message);
}
UpdateState completed(UpdateClient& client) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (client.state().busy() && std::chrono::steady_clock::now() < deadline) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    check(!client.state().busy(), "Update worker did not finish."); return client.state();
}
std::string metadata(const std::vector<unsigned char>& bytes, std::string digest = {}) {
    if (digest.empty()) digest = update_sha256(bytes);
    return "{\"tag_name\":\"v1.2.0\",\"draft\":false,\"prerelease\":false,\"body\":\"Unicode \\ud83c\\udfb5\",\"assets\":[{\"name\":\"IgnorantShuffle-v1.2.0.aimppack\",\"state\":\"uploaded\",\"size\":" + std::to_string(bytes.size()) +
        ",\"digest\":\"sha256:" + digest + "\",\"browser_download_url\":\"https://github.com/example/shuffle/releases/download/v1.2.0/IgnorantShuffle-v1.2.0.aimppack\"}]}";
}
HttpReply json_reply(const std::string& value) { return {200, {value.begin(), value.end()}}; }
void model_checks() {
    check(normalize_repository("  https://github.com/example/shuffle/releases/latest/ ") == "example/shuffle", "Repository URL normalization.");
    check(normalize_repository("").empty(), "Placeholder repository.");
    for (const auto value : {"http://github.com/a/b", "a/b/../../x", "a/..", "https://evil.test/a/b", "a/b?x", "a/b\""})
        rejects([&] { normalize_repository(value); }, "Unsafe repository accepted.");
    check(release_version("v0.10.0") > release_version("0.9.9"), "Numeric version ordering.");
    check(release_version("1.2.0") == release_version("1.2.0.0"), "Version normalization.");
    for (const auto value : {"1.2", "1.2.0-beta", "65536.0.0", "1.2.3.4.5", "1.2.3."})
        rejects([&] { release_version(value); }, "Unsupported version accepted.");
    const std::vector<unsigned char> package{'P','K',3,4,1,2,3,4};
    auto json = metadata(package);
    const auto release = parse_release(json, "example/shuffle", "0.9.0");
    check(release && release->installable() && release->size == package.size(), "Valid release asset detection.");
    check(!parse_release(json, "example/shuffle", "1.2.0"), "Equal version must not update.");
    check(!parse_release(json, "example/shuffle", "2.0.0"), "Never downgrade.");
    auto prerelease = json; prerelease.replace(prerelease.find("\"prerelease\":false"), 18, "\"prerelease\":true");
    check(!parse_release(prerelease, "example/shuffle", "0.9.0"), "Do not install prereleases.");
    check(!parse_release("{\"tag_name\":\"v1.2.0\",\"assets\":[]}", "example/shuffle", "0.9.0")->installable(), "Missing package is visible but not installable.");
    check(!parse_release(metadata(package, "missing"), "example/shuffle", "0.9.0")->installable(), "Missing digest blocks installation.");
    rejects([&] { parse_release(json, "different/repo", "0.9.0"); }, "Cross-repository asset accepted.");
    rejects([&] { parse_release("{\"tag_name\":\"v1.2.0\",\"tag_name\":\"v9.0.0\"}", "example/shuffle", "0.9.0"); }, "Ambiguous JSON accepted.");
    rejects([&] { parse_release("{\"tag_name\":\"v1.2.0\",\"body\":\"\\ud800\"}", "example/shuffle", "0.9.0"); }, "Invalid Unicode accepted.");
    check(update_sha256({'a','b','c'}) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "Known SHA256 vector.");
}
void worker_checks() {
    const std::vector<unsigned char> package{'P','K',3,4,1,2,3,4};
    std::atomic<int> calls{0};
    UpdateClient client("0.9.0", [&](const auto& url, auto, const auto&, const auto& progress) {
        ++calls;
        if (url.find("api.github.com") != std::string::npos) return json_reply(metadata(package));
        if (progress) progress(package.size()); return HttpReply{200, package};
    });
    check(!client.check("") && calls == 0, "Blank repository must never make network requests.");
    check(client.check("example/shuffle") && completed(client).phase == UpdatePhase::Available, "Background release check.");
    const auto cache = std::filesystem::current_path() / ("update-test-" + std::to_string(GetCurrentProcessId()));
    check(client.download(cache), "Start explicit package download.");
    const auto ready = completed(client);
    check(ready.phase == UpdatePhase::Ready && std::filesystem::file_size(ready.package) == package.size(), "Verified package saved atomically.");
    std::ifstream input(ready.package, std::ios::binary);
    const std::vector<unsigned char> saved{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()}; input.close();
    check(saved == package, "Saved package matches downloaded bytes.");
    client.handed_off(); check(client.state().phase == UpdatePhase::HandedOff, "Installation handoff state.");
    std::filesystem::remove(ready.package); std::filesystem::remove(ready.package.parent_path()); std::filesystem::remove(cache);
    for (int scenario = 0; scenario < 3; ++scenario) {
        UpdateClient bad("0.9.0", [&](const auto& url, auto, const auto&, const auto&) {
            if (url.find("api.github.com") != std::string::npos) return json_reply(metadata(package));
            auto corrupt = package;
            if (scenario == 0) corrupt.back() = 99;
            if (scenario == 1) corrupt.pop_back();
            return HttpReply{scenario == 2 ? 404u : 200u, corrupt};
        });
        bad.check("example/shuffle"); completed(bad); bad.download(cache);
        check(completed(bad).phase == UpdatePhase::Error && !std::filesystem::exists(cache), "Invalid download must never become installable or reach disk.");
    }
    UpdateClient missing("0.9.0", [](const auto&, auto, const auto&, const auto&) { return HttpReply{404,{}}; });
    missing.check("example/shuffle"); check(completed(missing).phase == UpdatePhase::Idle, "Unpublished release is handled.");
    std::atomic<bool> started{false};
    UpdateClient cancel("0.9.0", [&](const auto&, auto, const auto& canceled, const auto&) {
        started = true; while (!canceled.load()) std::this_thread::sleep_for(std::chrono::milliseconds(5));
        throw std::runtime_error("Canceled"); return HttpReply{};
    });
    cancel.check("example/shuffle");
    while (!started.load()) std::this_thread::yield();
    cancel.stop(); check(!cancel.state().busy(), "Unload cancels and joins update worker.");
}
// Exercise the actual asynchronous WinHTTP implementation against localhost.
class LocalServer {
    SOCKET listener_ = INVALID_SOCKET;
    std::thread thread_;
public:
    unsigned short port = 0;
    LocalServer(std::string body, bool stall = false) {
        listener_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP); check(listener_ != INVALID_SOCKET, "HTTP test socket.");
        sockaddr_in endpoint{}; endpoint.sin_family = AF_INET; endpoint.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        check(bind(listener_, reinterpret_cast<sockaddr*>(&endpoint), sizeof(endpoint)) == 0 && listen(listener_, 1) == 0, "Bind localhost HTTP fixture.");
        int length = sizeof(endpoint); getsockname(listener_, reinterpret_cast<sockaddr*>(&endpoint), &length); port = ntohs(endpoint.sin_port);
        thread_ = std::thread([this, body, stall] {
            const auto peer = accept(listener_, nullptr, nullptr); if (peer == INVALID_SOCKET) return;
            char request[4096]{}; recv(peer, request, sizeof(request), 0);
            if (stall) std::this_thread::sleep_for(std::chrono::milliseconds(700));
            else {
                const auto reply = "HTTP/1.1 200 OK\r\nContent-Length: " + std::to_string(body.size()) + "\r\nConnection: close\r\n\r\n" + body;
                send(peer, reply.data(), static_cast<int>(reply.size()), 0);
            }
            closesocket(peer);
        });
    }
    ~LocalServer() { closesocket(listener_); if (thread_.joinable()) thread_.join(); }
    std::string url() const { return "http://127.0.0.1:" + std::to_string(port) + "/release"; }
};
void http_checks() {
    WSADATA data{}; check(WSAStartup(MAKEWORD(2,2), &data) == 0, "Winsock startup.");
    std::atomic<bool> canceled{false};
    rejects([&] { update_http_get("http://example.com/release", 1024, canceled); }, "Production HTTP must require TLS.");
    { LocalServer server("fixture JSON"); const auto reply = update_http_get(server.url(), 100, canceled, {}, true);
      check(reply.status == 200 && std::string(reply.body.begin(), reply.body.end()) == "fixture JSON", "Real WinHTTP async send/receive/read."); }
    { LocalServer server("too much content"); rejects([&] { update_http_get(server.url(), 3, canceled, {}, true); }, "HTTP response limit."); }
    { LocalServer server("", true);
      std::thread stop([&] { std::this_thread::sleep_for(std::chrono::milliseconds(150)); canceled = true; });
      rejects([&] { update_http_get(server.url(), 100, canceled, {}, true); }, "HTTP cancellation."); stop.join(); }
    WSACleanup();
}
std::wstring dropped;
LRESULT CALLBACK receiver(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_DROPFILES) {
        const auto drop = reinterpret_cast<HDROP>(wparam); wchar_t name[32768]{};
        DragQueryFileW(drop, 0, name, 32768); dropped = name; DragFinish(drop); return 0;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}
void install_checks() {
    const auto package = std::filesystem::current_path() / L"installer-fixture.aimppack";
    { std::ofstream file(package); file << "fixture"; }
    WNDCLASSW type{}; type.lpfnWndProc = receiver; type.hInstance = GetModuleHandleW(nullptr); type.lpszClassName = L"IgnorantUpdateFixture";
    check(RegisterClassW(&type) != 0, "Register installer receiver fixture.");
    const auto window = CreateWindowW(type.lpszClassName, L"", 0, 0, 0, 0, 0, nullptr, nullptr, type.hInstance, nullptr);
    rejects([&] { install_update_package(nullptr, package); }, "Missing host must not install.");
    install_update_package(window, package);
    MSG message{}; while (PeekMessageW(&message, window, 0, 0, PM_REMOVE)) DispatchMessageW(&message);
    check(dropped == package.wstring(), "Native installer receives an owned Unicode file-drop package.");
    DestroyWindow(window); UnregisterClassW(type.lpszClassName, type.hInstance); std::filesystem::remove(package);
}
}
int main() {
    try { model_checks(); worker_checks(); http_checks(); install_checks(); std::cout << "Release parsing, verified downloads, cancellation, real HTTP and installer handoff passed.\n"; return 0; }
    catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
