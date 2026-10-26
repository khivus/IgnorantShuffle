#include "update_http.h"
#include <windows.h>
#include <winhttp.h>
#include <array>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <stdexcept>

namespace ignorant {
namespace {
std::wstring wide(const std::string& text) {
    const auto count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr, 0);
    if (!count) throw std::runtime_error("Invalid update URL encoding.");
    std::wstring result(count, L'\0'); MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), result.data(), count); return result;
}
void require(bool success, const char* operation) {
    if (!success) throw std::runtime_error(std::string(operation) + " (Windows error " + std::to_string(GetLastError()) + ").");
}
struct Internet {
    HINTERNET handle = nullptr;
    ~Internet() { if (handle) WinHttpCloseHandle(handle); }
};
struct Request {
    HINTERNET handle = nullptr;
    std::mutex mutex; std::condition_variable ready;
    DWORD completed = 0, error = 0, bytes = 0;
    bool closed = false;
    static void CALLBACK callback(HINTERNET, DWORD_PTR context, DWORD status, void* info, DWORD length) {
        auto* self = reinterpret_cast<Request*>(context); if (!self) return;
        std::lock_guard<std::mutex> lock(self->mutex);
        if (status == WINHTTP_CALLBACK_STATUS_HANDLE_CLOSING) self->closed = true;
        else if (status == WINHTTP_CALLBACK_STATUS_REQUEST_ERROR) {
            self->error = static_cast<WINHTTP_ASYNC_RESULT*>(info)->dwError; self->completed = status;
        } else if (status == WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE || status == WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE || status == WINHTTP_CALLBACK_STATUS_READ_COMPLETE) {
            self->completed = status; if (status == WINHTTP_CALLBACK_STATUS_READ_COMPLETE) self->bytes = length;
        }
        self->ready.notify_all();
    }
    void reset() { std::lock_guard<std::mutex> lock(mutex); completed = error = bytes = 0; }
    void wait(DWORD wanted, const std::atomic<bool>& canceled, std::chrono::steady_clock::time_point deadline) {
        std::unique_lock<std::mutex> lock(mutex);
        while (!completed) {
            if (canceled.load()) throw std::runtime_error("Update request canceled.");
            if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Update request timed out.");
            ready.wait_for(lock, std::chrono::milliseconds(50));
        }
        if (canceled.load()) throw std::runtime_error("Update request canceled.");
        if (error) throw std::runtime_error("Update connection failed (Windows error " + std::to_string(error) + ").");
        if (completed != wanted) throw std::runtime_error("Unexpected update HTTP completion.");
    }
    ~Request() {
        if (!handle) return;
        // Only this worker closes the asynchronous request, after each WinHTTP
        // call returned. Retain callback context until HANDLE_CLOSING completes.
        WinHttpCloseHandle(handle);
        std::unique_lock<std::mutex> lock(mutex); ready.wait(lock, [this] { return closed; });
    }
};
}
HttpReply update_http_get(const std::string& address, std::size_t limit, const std::atomic<bool>& canceled,
    const DownloadProgress& progress, bool allow_loopback_http) {
    if (canceled.load()) throw std::runtime_error("Update request canceled.");
    const auto url = wide(address);
    URL_COMPONENTS parts{}; parts.dwStructSize = sizeof(parts); parts.dwHostNameLength = parts.dwUrlPathLength = parts.dwExtraInfoLength = static_cast<DWORD>(-1);
    require(WinHttpCrackUrl(url.c_str(), 0, 0, &parts) != FALSE, "Cannot parse update URL");
    const std::wstring host(parts.lpszHostName, parts.dwHostNameLength);
    const bool secure = parts.nScheme == INTERNET_SCHEME_HTTPS;
    if (!secure && !(allow_loopback_http && parts.nScheme == INTERNET_SCHEME_HTTP && (host == L"127.0.0.1" || host == L"localhost")))
        throw std::runtime_error("Update downloads require HTTPS.");
    if (parts.dwUserNameLength || parts.dwPasswordLength) throw std::runtime_error("Credentials are not allowed in update URLs.");
    std::wstring path(parts.lpszUrlPath, parts.dwUrlPathLength);
    if (parts.dwExtraInfoLength) path.append(parts.lpszExtraInfo, parts.dwExtraInfoLength);
    Internet session{WinHttpOpen(L"IgnorantShuffle updater", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, WINHTTP_FLAG_ASYNC)};
    require(session.handle != nullptr, "Cannot start update connection");
    WinHttpSetTimeouts(session.handle, 5000, 5000, 10000, 10000);
    Internet connection{WinHttpConnect(session.handle, host.c_str(), parts.nPort, 0)};
    require(connection.handle != nullptr, "Cannot connect to update host");
    std::array<unsigned char, 64 * 1024> buffer{};
    Request request;
    request.handle = WinHttpOpenRequest(connection.handle, L"GET", path.empty() ? L"/" : path.c_str(), nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, secure ? WINHTTP_FLAG_SECURE : 0);
    require(request.handle != nullptr, "Cannot create update request");
    // A buffer stays alive until the request's final close callback, even when a
    // cancellation interrupts an asynchronous read into it.
    const auto callback = WinHttpSetStatusCallback(request.handle, Request::callback, WINHTTP_CALLBACK_FLAG_ALL_COMPLETIONS | WINHTTP_CALLBACK_FLAG_HANDLES, 0);
    if (callback == WINHTTP_INVALID_STATUS_CALLBACK) { WinHttpCloseHandle(request.handle); request.handle = nullptr; require(false, "Cannot observe update connection"); }
    DWORD_PTR context = reinterpret_cast<DWORD_PTR>(&request);
    if (!WinHttpSetOption(request.handle, WINHTTP_OPTION_CONTEXT_VALUE, &context, sizeof(context))) {
        WinHttpSetStatusCallback(request.handle, nullptr, 0, 0); WinHttpCloseHandle(request.handle); request.handle = nullptr;
        require(false, "Cannot initialize update connection");
    }
    DWORD redirects = WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP;
    require(WinHttpSetOption(request.handle, WINHTTP_OPTION_REDIRECT_POLICY, &redirects, sizeof(redirects)) != FALSE, "Cannot secure update redirects");
    const wchar_t* headers = L"Accept: application/vnd.github+json\r\n";
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(limit > 2 * 1024 * 1024 ? 180 : 30);
    request.reset();
    require(WinHttpSendRequest(request.handle, headers, static_cast<DWORD>(-1), WINHTTP_NO_REQUEST_DATA, 0, 0, context) != FALSE, "Cannot send update request");
    request.wait(WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE, canceled, deadline);
    request.reset(); require(WinHttpReceiveResponse(request.handle, nullptr) != FALSE, "Cannot receive update response");
    request.wait(WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE, canceled, deadline);
    HttpReply reply; DWORD size = sizeof(reply.status);
    require(WinHttpQueryHeaders(request.handle, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &reply.status, &size, WINHTTP_NO_HEADER_INDEX) != FALSE, "Cannot read update response status");
    for (;;) {
        request.reset(); require(WinHttpReadData(request.handle, buffer.data(), static_cast<DWORD>(buffer.size()), nullptr) != FALSE, "Cannot read update response");
        request.wait(WINHTTP_CALLBACK_STATUS_READ_COMPLETE, canceled, deadline);
        if (!request.bytes) break;
        if (reply.body.size() + request.bytes > limit) throw std::runtime_error("Update response exceeds the size limit.");
        reply.body.insert(reply.body.end(), buffer.begin(), buffer.begin() + request.bytes);
        if (progress) progress(reply.body.size());
    }
    return reply;
}
}
