#pragma once
#include <windows.h>
#include <functional>
#include <string>
#include <vector>

struct MonitorState {
    bool enabled = true;
    bool pinned = true;
    bool attached = false;
    bool finite = false;
    bool can_set_weight = false;
    int selected_weight = -1;
    bool can_reroll = false;
    bool can_clear_override = false;
    int weight_scope = 0;
    std::vector<int> weight_scopes{0};
    int cooldown = 0;
    std::wstring cooldown_status;
    std::wstring mode, current_track, current_key, current_weight, next_track, decision, notice, update_status;
};

// A modeless, scrollable window on AIMP's UI thread; it owns no playback state.
class MonitorWindow {
    HWND window_ = nullptr;
    HWND host_ = nullptr;
    HFONT weight_font_ = nullptr;
    int displayed_cooldown_ = -1;
    std::wstring displayed_track_;
    std::vector<int> displayed_scopes_;
    bool scope_visible_ = false;
    bool attached_ = false, wanted_visible_ = false, host_hidden_ = false;
    bool was_minimized_ = false, laying_out_ = false, sizing_ = false;
    int scroll_ = 0, content_height_ = 0, wheel_remainder_ = 0;
    RECT floating_bounds_{};
    void layout();
    void scroll_to(int position);
    void ensure_visible(HWND control);
    void remember_geometry();
    static LRESULT CALLBACK control_proc(HWND, UINT, WPARAM, LPARAM, UINT_PTR, DWORD_PTR);
    static INT_PTR CALLBACK procedure(HWND, UINT, WPARAM, LPARAM);
public:
    ~MonitorWindow() { destroy(); }
    std::function<void()> on_tick, on_weights, on_reroll, on_move, on_logs, on_clear_override, on_updates;
    std::function<void(int)> on_scope;
    std::function<void(int)> on_weight;
    std::function<void(int)> on_cooldown;
    std::function<void(const std::string&)> on_error;
    std::function<void(bool)> on_enabled, on_pinned, on_attached, on_finite;
    HWND handle() const { return window_; }
    bool create(HINSTANCE instance);
    void show(bool activate = false);
    void destroy();
    void update(const MonitorState& state);
    void set_attachment(bool attached, HWND host);
    void sync_attachment();
    RECT saved_bounds() const;
    void restore_size(int width, int height);
    void restore_position(int x, int y);
};
