#include "monitor.h"
#include "resource.h"
#include <commctrl.h>
#include <uxtheme.h>
#include <vssym32.h>
#include <algorithm>
#include <exception>
#include <stdexcept>
#include <vector>

namespace {
void set_text(HWND window, int control, const std::wstring& text) {
    const auto child = GetDlgItem(window, control);
    std::vector<wchar_t> previous(GetWindowTextLengthW(child) + 1);
    GetWindowTextW(child, previous.data(), static_cast<int>(previous.size()));
    if (text != previous.data()) SetWindowTextW(child, text.c_str());
}
int x_units(HWND window, int value) {
    RECT units{0, 0, value, 0}; MapDialogRect(window, &units); return units.right;
}
int y_units(HWND window, int value) {
    RECT units{0, 0, 0, value}; MapDialogRect(window, &units); return units.bottom;
}
bool is_weight_button(HWND control) {
    const auto id = GetDlgCtrlID(control);
    return id >= IDC_QUICK_WEIGHT_0 && id <= IDC_QUICK_WEIGHT_10;
}
// Keep native button input and accessibility, but render its current state
// directly instead of replaying a theme animation from another track.
void paint_weight(HWND control, HDC dc) {
    BufferedPaintStopAllAnimations(control);
    RECT bounds{}; GetClientRect(control, &bounds);
    const auto flags = SendMessageW(control, BM_GETSTATE, 0, 0);
    const bool enabled = IsWindowEnabled(control) != FALSE;
    const bool selected = SendMessageW(control, BM_GETCHECK, 0, 0) == BST_CHECKED;
    const bool pressed = selected || (flags & BST_PUSHED) != 0;
    const auto state = !enabled ? PBS_DISABLED : pressed ? PBS_PRESSED : (flags & BST_HOT) ? PBS_HOT : PBS_NORMAL;
    auto color = GetSysColor(enabled ? COLOR_BTNTEXT : COLOR_GRAYTEXT);
    if (const auto theme = OpenThemeData(control, L"Button")) {
        if (IsThemeBackgroundPartiallyTransparent(theme, BP_PUSHBUTTON, state)) DrawThemeParentBackground(control, dc, &bounds);
        DrawThemeBackground(theme, dc, BP_PUSHBUTTON, state, &bounds, nullptr);
        GetThemeColor(theme, BP_PUSHBUTTON, state, TMT_TEXTCOLOR, &color);
        CloseThemeData(theme);
    } else {
        FillRect(dc, &bounds, GetSysColorBrush(COLOR_BTNFACE));
        DrawFrameControl(dc, &bounds, DFC_BUTTON, DFCS_BUTTONPUSH | (pressed ? DFCS_PUSHED : 0) | (!enabled ? DFCS_INACTIVE : 0));
    }
    wchar_t label[32]{}; GetWindowTextW(control, label, 32);
    const auto font = SelectObject(dc, reinterpret_cast<HFONT>(SendMessageW(control, WM_GETFONT, 0, 0)));
    SetBkMode(dc, TRANSPARENT); SetTextColor(dc, color);
    DrawTextW(dc, label, -1, &bounds, DT_SINGLELINE | DT_CENTER | DT_VCENTER | DT_NOPREFIX);
    if ((flags & BST_FOCUS) && !(SendMessageW(control, WM_QUERYUISTATE, 0, 0) & UISF_HIDEFOCUS)) {
        InflateRect(&bounds, -3, -3); DrawFocusRect(dc, &bounds);
    }
    SelectObject(dc, font);
}
}
bool MonitorWindow::create(HINSTANCE instance) {
    if (window_) return true;
    return CreateDialogParamW(instance, MAKEINTRESOURCEW(IDD_MONITOR), nullptr, procedure, reinterpret_cast<LPARAM>(this)) != nullptr;
}
void MonitorWindow::show(bool activate) {
    if (!window_) return;
    wanted_visible_ = true;
    if (attached_ && host_ && (!IsWindowVisible(host_) || IsIconic(host_))) {
        sync_attachment(); return;
    }
    ShowWindow(window_, IsIconic(window_) ? SW_RESTORE : activate ? SW_SHOWNORMAL : SW_SHOWNOACTIVATE);
    sync_attachment();
    if (activate && IsWindowVisible(window_)) SetForegroundWindow(window_);
}
void MonitorWindow::destroy() {
    if (window_) DestroyWindow(window_);
}
void MonitorWindow::update(const MonitorState& state) {
    if (!window_) return;
    const UINT enabled = state.enabled ? BST_CHECKED : BST_UNCHECKED;
    const UINT pinned = state.pinned ? BST_CHECKED : BST_UNCHECKED;
    if (IsDlgButtonChecked(window_, IDC_ENABLED) != enabled) CheckDlgButton(window_, IDC_ENABLED, enabled);
    if (IsDlgButtonChecked(window_, IDC_PINNED) != pinned) CheckDlgButton(window_, IDC_PINNED, pinned);
    const UINT attached = state.attached ? BST_CHECKED : BST_UNCHECKED;
    if (IsDlgButtonChecked(window_, IDC_ATTACHED) != attached) CheckDlgButton(window_, IDC_ATTACHED, attached);
    const bool topmost = (GetWindowLongPtrW(window_, GWL_EXSTYLE) & WS_EX_TOPMOST) != 0;
    if (topmost != state.pinned)
        SetWindowPos(window_, state.pinned ? HWND_TOPMOST : HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    if ((IsWindowEnabled(GetDlgItem(window_, IDC_REROLL_NEXT)) != FALSE) != state.can_reroll)
        EnableWindow(GetDlgItem(window_, IDC_REROLL_NEXT), state.can_reroll);
    if (displayed_cooldown_ != state.cooldown) {
        SetDlgItemInt(window_, IDC_COOLDOWN, state.cooldown, FALSE);
        displayed_cooldown_ = state.cooldown;
    }
    if (displayed_scopes_ != state.weight_scopes) {
        displayed_scopes_ = state.weight_scopes;
        SendDlgItemMessageW(window_, IDC_WEIGHT_SCOPE, CB_RESETCONTENT, 0, 0);
        for (const auto scope : state.weight_scopes) {
            const auto label = scope == 1 ? L"Folder local" : scope == 2 ? L"AIMP playlist" : L"Global";
            const auto index = SendDlgItemMessageW(window_, IDC_WEIGHT_SCOPE, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label));
            SendDlgItemMessageW(window_, IDC_WEIGHT_SCOPE, CB_SETITEMDATA, index, scope);
        }
    }
    const auto selected_scope = std::find(state.weight_scopes.begin(), state.weight_scopes.end(), state.weight_scope);
    const auto scope_index = selected_scope == state.weight_scopes.end() ? 0 : std::distance(state.weight_scopes.begin(), selected_scope);
    if (SendDlgItemMessageW(window_, IDC_WEIGHT_SCOPE, CB_GETCURSEL, 0, 0) != scope_index)
        SendDlgItemMessageW(window_, IDC_WEIGHT_SCOPE, CB_SETCURSEL, scope_index, 0);
    scope_visible_ = state.weight_scopes.size() > 1;
    for (const int id : {IDC_SCOPE_LABEL, IDC_WEIGHT_SCOPE, IDC_RESET_LOCAL})
        ShowWindow(GetDlgItem(window_, id), scope_visible_ ? SW_SHOWNA : SW_HIDE);
    EnableWindow(GetDlgItem(window_, IDC_WEIGHT_SCOPE), state.can_set_weight);
    EnableWindow(GetDlgItem(window_, IDC_RESET_LOCAL), state.can_clear_override);
    if (displayed_track_ != state.current_key) {
        displayed_track_ = state.current_key;
        const auto focused = GetFocus();
        if (focused && GetParent(focused) == window_ && is_weight_button(focused))
            SetFocus(GetDlgItem(window_, IDC_CURRENT_TRACK));
    }
    bool selector_changed = false;
    for (int weight = 0; weight <= 10; ++weight) {
        const auto control = IDC_QUICK_WEIGHT_0 + weight;
        const UINT selected = state.selected_weight == weight ? BST_CHECKED : BST_UNCHECKED;
        selector_changed = selector_changed || IsDlgButtonChecked(window_, control) != selected ||
            ((IsWindowEnabled(GetDlgItem(window_, control)) != FALSE) != state.can_set_weight);
    }
    if (selector_changed) {
        // Commit the entire selector before any one button can repaint it.
        for (int weight = 0; weight <= 10; ++weight)
            SendDlgItemMessageW(window_, IDC_QUICK_WEIGHT_0 + weight, WM_SETREDRAW, FALSE, 0);
        for (int weight = 0; weight <= 10; ++weight) {
            const auto control = IDC_QUICK_WEIGHT_0 + weight;
            const UINT selected = state.selected_weight == weight ? BST_CHECKED : BST_UNCHECKED;
            CheckDlgButton(window_, control, selected);
            EnableWindow(GetDlgItem(window_, control), state.can_set_weight);
        }
        for (int weight = 0; weight <= 10; ++weight) {
            const auto button = GetDlgItem(window_, IDC_QUICK_WEIGHT_0 + weight);
            SendMessageW(button, WM_SETREDRAW, TRUE, 0);
            InvalidateRect(button, nullptr, FALSE);
        }
    }
    set_text(window_, IDC_MODE, state.mode);
    set_text(window_, IDC_CURRENT_TRACK, state.current_track);
    set_text(window_, IDC_CURRENT_WEIGHT, state.current_weight);
    set_text(window_, IDC_NEXT_TRACK, state.next_track);
    set_text(window_, IDC_DECISION, state.decision);
    set_text(window_, IDC_PANEL_NOTICE, state.notice);
    set_text(window_, IDC_COOLDOWN_STATUS, state.cooldown_status);
    set_text(window_, IDC_UPDATE_SUMMARY, state.update_status);
    layout();
}
void MonitorWindow::layout() {
    if (!window_ || laying_out_ || IsIconic(window_)) return;
    laying_out_ = true;
    struct LayoutGuard { bool& active; ~LayoutGuard() { active = false; } } guard{laying_out_};
    struct Bounds { HWND control; int x, y, width, height; };
    std::vector<Bounds> controls;
    RECT client{}; GetClientRect(window_, &client);
    auto build = [&] {
        controls.clear();
        const auto margin = x_units(window_, 6), inset = x_units(window_, 5);
        const auto width = std::max(1, static_cast<int>(client.right) - 2 * margin);
        const auto inner = std::max(1, width - 2 * inset);
        auto place = [&](int id, int x, int y, int w, int h) {
            controls.push_back({GetDlgItem(window_, id), x, y, w, h});
        };
        auto text_height = [&](int id, int w, int minimum) {
            const auto child = GetDlgItem(window_, id);
            std::vector<wchar_t> text(GetWindowTextLengthW(child) + 1);
            GetWindowTextW(child, text.data(), static_cast<int>(text.size()));
            RECT size{0, 0, w, 0};
            const auto dc = GetDC(child);
            const auto old = SelectObject(dc, reinterpret_cast<HFONT>(SendMessageW(child, WM_GETFONT, 0, 0)));
            DrawTextW(dc, text.data(), -1, &size, DT_CALCRECT | DT_WORDBREAK | DT_NOPREFIX);
            SelectObject(dc, old); ReleaseDC(child, dc);
            return std::max(minimum, static_cast<int>(size.bottom) + y_units(window_, 1));
        };
        auto label_width = [&](int id) {
            const auto child = GetDlgItem(window_, id);
            std::vector<wchar_t> text(GetWindowTextLengthW(child) + 1);
            const auto count = GetWindowTextW(child, text.data(), static_cast<int>(text.size()));
            const auto dc = GetDC(child);
            const auto old = SelectObject(dc, reinterpret_cast<HFONT>(SendMessageW(child, WM_GETFONT, 0, 0)));
            SIZE size{}; GetTextExtentPoint32W(dc, text.data(), count, &size);
            SelectObject(dc, old); ReleaseDC(child, dc);
            return static_cast<int>(size.cx);
        };
        int y = y_units(window_, 4), x = margin;
        const auto gap = x_units(window_, 3), box_height = y_units(window_, 12);
        // Pack complete labels in order, wrapping the next checkbox as needed.
        for (const int id : {IDC_ENABLED, IDC_PINNED, IDC_ATTACHED}) {
            const auto size = label_width(id) + x_units(window_, 14);
            if (x != margin && x + size > margin + width) { x = margin; y += box_height + y_units(window_, 2); }
            place(id, x, y, size, box_height);
            x += size + gap;
        }
        y += box_height + y_units(window_, 3);
        const auto mode_height = text_height(IDC_MODE, width, y_units(window_, 9));
        place(IDC_MODE, margin, y, width, mode_height);
        y += mode_height + y_units(window_, 3);
        const auto track_height = std::min(text_height(IDC_CURRENT_TRACK, inner, y_units(window_, 9)), y_units(window_, 20));
        place(IDC_CURRENT_TRACK, margin + inset, y + y_units(window_, 11), inner, track_height);
        const auto exclude_width = x_units(window_, 59);
        const auto weight_height = text_height(IDC_CURRENT_WEIGHT, inner - exclude_width - gap, y_units(window_, 9));
        const auto weight_y = y_units(window_, 11) + track_height + y_units(window_, 2);
        const auto buttons_y = weight_y + std::max(weight_height + y_units(window_, 2), y_units(window_, 14)) + y_units(window_, 2);
        const auto current_height = buttons_y + y_units(window_, scope_visible_ ? 38 : 18);
        place(IDC_CURRENT_GROUP, margin, y, width, current_height);
        place(IDC_CURRENT_WEIGHT, margin + inset, y + weight_y + y_units(window_, 2), inner - exclude_width - gap, weight_height);
        place(IDC_QUICK_WEIGHT_0, margin + width - inset - exclude_width, y + weight_y, exclude_width, y_units(window_, 14));
        // Keep the ten values in one row at every supported width.
        const auto button_space = inner - 9 * gap;
        for (int weight = 1; weight <= 10; ++weight) {
            const auto left = (weight - 1) * button_space / 10 + (weight - 1) * gap;
            const auto right = weight * button_space / 10 + (weight - 1) * gap;
            place(IDC_QUICK_WEIGHT_0 + weight, margin + inset + left, y + buttons_y, right - left, y_units(window_, 14));
        }
        if (scope_visible_) {
            const auto row = y + buttons_y + y_units(window_, 17);
            place(IDC_SCOPE_LABEL, margin + inset, row + y_units(window_, 2), x_units(window_, 20), y_units(window_, 11));
            place(IDC_WEIGHT_SCOPE, margin + inset + x_units(window_, 23), row, x_units(window_, 90), y_units(window_, 90));
            place(IDC_RESET_LOCAL, margin + inset + x_units(window_, 117), row, x_units(window_, 48), y_units(window_, 15));
        }
        y += current_height + y_units(window_, 4);
        const auto cooldown_label_width = label_width(IDC_COOLDOWN_LABEL) + gap;
        const auto field_width = x_units(window_, 35), apply_width = x_units(window_, 33), off_width = label_width(IDC_COOLDOWN_OFF) + gap;
        if (width >= cooldown_label_width + field_width + apply_width + off_width + 3 * gap) {
            place(IDC_COOLDOWN_LABEL, margin, y + y_units(window_, 2), cooldown_label_width, y_units(window_, 10));
            place(IDC_COOLDOWN, margin + cooldown_label_width + gap, y + y_units(window_, 1), field_width, y_units(window_, 12));
            place(IDC_APPLY_COOLDOWN, margin + cooldown_label_width + field_width + 2 * gap, y, apply_width, y_units(window_, 14));
            place(IDC_COOLDOWN_OFF, margin + cooldown_label_width + field_width + apply_width + 3 * gap, y + y_units(window_, 2), off_width, y_units(window_, 10));
        } else {
            place(IDC_COOLDOWN_LABEL, margin, y, width, y_units(window_, 10));
            y += y_units(window_, 12);
            place(IDC_COOLDOWN, margin, y + y_units(window_, 1), field_width, y_units(window_, 12));
            place(IDC_APPLY_COOLDOWN, margin + field_width + gap, y, apply_width, y_units(window_, 14));
            place(IDC_COOLDOWN_OFF, margin + field_width + apply_width + 2 * gap, y + y_units(window_, 2), off_width, y_units(window_, 10));
        }
        y += y_units(window_, 17);
        const auto status_height = text_height(IDC_COOLDOWN_STATUS, width, y_units(window_, 9));
        place(IDC_COOLDOWN_STATUS, margin, y, width, status_height);
        y += status_height + y_units(window_, 4);
        const auto next_height = std::min(text_height(IDC_NEXT_TRACK, inner, y_units(window_, 9)), y_units(window_, 20));
        const auto details_height = text_height(IDC_DECISION, inner, y_units(window_, 9));
        const auto details_y = y_units(window_, 11) + next_height + y_units(window_, 2);
        place(IDC_NEXT_GROUP, margin, y, width, details_y + details_height + y_units(window_, 4));
        place(IDC_NEXT_TRACK, margin + inset, y + y_units(window_, 11), inner, next_height);
        place(IDC_DECISION, margin + inset, y + details_y, inner, details_height);
        y += details_y + details_height + y_units(window_, 8);
        const int buttons[]{IDC_EDIT_WEIGHTS, IDC_REROLL_NEXT, IDC_OPEN_LOGS};
        for (int i = 0; i < 3; ++i) {
            const auto left = i * (width + gap) / 3;
            const auto right = (i + 1) * (width + gap) / 3 - gap;
            place(buttons[i], margin + left, y, right - left, y_units(window_, 15));
        }
        y += y_units(window_, 19);
        const auto notice_height = text_height(IDC_PANEL_NOTICE, width, y_units(window_, 9));
        place(IDC_PANEL_NOTICE, margin, y, width, notice_height);
        y += notice_height + y_units(window_, 3);
        const auto updates_width = x_units(window_, 59);
        const auto update_height = text_height(IDC_UPDATE_SUMMARY, width - updates_width - gap, y_units(window_, 14));
        place(IDC_UPDATES, margin, y, updates_width, y_units(window_, 15));
        place(IDC_UPDATE_SUMMARY, margin + updates_width + gap, y, width - updates_width - gap, update_height);
        content_height_ = y + std::max(update_height, y_units(window_, 15)) + y_units(window_, 4);
    };
    // Account for the width consumed by the vertical scrollbar before wrapping.
    const auto style = GetWindowLongPtrW(window_, GWL_STYLE);
    if (style & WS_VSCROLL) client.right += GetSystemMetrics(SM_CXVSCROLL);
    build();
    const bool scrollable = content_height_ > client.bottom;
    if (scrollable) { client.right -= GetSystemMetrics(SM_CXVSCROLL); build(); }
    SCROLLINFO info{sizeof(info), SIF_RANGE | SIF_PAGE | SIF_POS};
    info.nMax = std::max(0, content_height_ - 1);
    info.nPage = static_cast<UINT>(std::max(0L, client.bottom));
    scroll_ = std::clamp(scroll_, 0, std::max(0, content_height_ - static_cast<int>(client.bottom)));
    info.nPos = scroll_;
    SetScrollInfo(window_, SB_VERT, &info, TRUE);
    bool moved = false;
    for (const auto& item : controls) {
        RECT previous{}; GetWindowRect(item.control, &previous);
        MapWindowPoints(nullptr, window_, reinterpret_cast<POINT*>(&previous), 2);
        const bool dropdown = GetDlgCtrlID(item.control) == IDC_WEIGHT_SCOPE;
        if (previous.left != item.x || previous.top != item.y - scroll_ || previous.right - previous.left != item.width || (!dropdown && previous.bottom - previous.top != item.height)) {
            // Do not copy pixels or paint overlapping group boxes midway through
            // reflow. Their old contents can otherwise survive over new labels.
            SetWindowPos(item.control, nullptr, item.x, item.y - scroll_, item.width, item.height,
                SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOREDRAW | SWP_NOCOPYBITS);
            moved = true;
        }
    }
    if (moved) RedrawWindow(window_, nullptr, nullptr, RDW_INVALIDATE | RDW_ERASE | RDW_ALLCHILDREN | RDW_FRAME);
}
void MonitorWindow::scroll_to(int position) {
    RECT client{}; GetClientRect(window_, &client);
    const auto next = std::clamp(position, 0, std::max(0, content_height_ - static_cast<int>(client.bottom)));
    if (next == scroll_) return;
    scroll_ = next;
    layout();
    RedrawWindow(window_, nullptr, nullptr, RDW_INVALIDATE | RDW_ALLCHILDREN);
}
void MonitorWindow::ensure_visible(HWND control) {
    if (!window_ || !control || laying_out_) return;
    RECT bounds{}, client{}; GetWindowRect(control, &bounds); GetClientRect(window_, &client);
    MapWindowPoints(nullptr, window_, reinterpret_cast<POINT*>(&bounds), 2);
    if (bounds.top < 0) scroll_to(scroll_ + bounds.top - y_units(window_, 4));
    else if (bounds.bottom > client.bottom) scroll_to(scroll_ + bounds.bottom - client.bottom + y_units(window_, 4));
}
RECT MonitorWindow::saved_bounds() const {
    if (!window_ || attached_ || IsIconic(window_)) return floating_bounds_;
    RECT bounds{}; GetWindowRect(window_, &bounds); return bounds;
}
void MonitorWindow::remember_geometry() {
    if (!window_ || IsIconic(window_)) return;
    RECT bounds{}; GetWindowRect(window_, &bounds);
    if (!attached_) floating_bounds_ = bounds;
    else {
        floating_bounds_.right = floating_bounds_.left + bounds.right - bounds.left;
        floating_bounds_.bottom = floating_bounds_.top + bounds.bottom - bounds.top;
    }
}
void MonitorWindow::restore_size(int width, int height) {
    if (!window_) return;
    RECT minimum{0, 0, x_units(window_, 200), y_units(window_, 125)};
    AdjustWindowRectEx(&minimum, static_cast<DWORD>(GetWindowLongPtrW(window_, GWL_STYLE)) | WS_VSCROLL, FALSE,
        static_cast<DWORD>(GetWindowLongPtrW(window_, GWL_EXSTYLE)));
    MONITORINFO display{sizeof(display)};
    GetMonitorInfoW(MonitorFromWindow(window_, MONITOR_DEFAULTTONEAREST), &display);
    width = std::clamp(width, static_cast<int>(minimum.right - minimum.left), std::max(static_cast<int>(minimum.right - minimum.left), static_cast<int>(display.rcWork.right - display.rcWork.left)));
    height = std::clamp(height, static_cast<int>(minimum.bottom - minimum.top), std::max(static_cast<int>(minimum.bottom - minimum.top), static_cast<int>(display.rcWork.bottom - display.rcWork.top)));
    SetWindowPos(window_, nullptr, 0, 0, width, height, SWP_NOZORDER | SWP_NOMOVE | SWP_NOACTIVATE);
    remember_geometry();
}
void MonitorWindow::set_attachment(bool attached, HWND host) {
    if (!window_) return;
    host_ = host && IsWindow(host) ? host : nullptr;
    if (attached_ != attached) {
        if (attached) floating_bounds_ = saved_bounds();
        attached_ = attached;
        if (!attached) {
            if (host_hidden_ && wanted_visible_) ShowWindow(window_, was_minimized_ ? SW_SHOWMINNOACTIVE : SW_SHOWNOACTIVATE);
            host_hidden_ = false;
            restore_position(floating_bounds_.left, floating_bounds_.top);
        }
    }
    sync_attachment();
}
void MonitorWindow::sync_attachment() {
    if (!window_ || !attached_ || !wanted_visible_ || sizing_) return;
    if (host_ && IsWindow(host_) && (!IsWindowVisible(host_) || IsIconic(host_))) {
        if (!host_hidden_) {
            was_minimized_ = IsIconic(window_) != FALSE;
            host_hidden_ = true;
            ShowWindow(window_, SW_HIDE);
        }
        return;
    }
    if (host_hidden_) {
        host_hidden_ = false;
        ShowWindow(window_, was_minimized_ ? SW_SHOWMINNOACTIVE : SW_SHOWNOACTIVATE);
    }
    if (!host_ || !IsWindow(host_) || IsIconic(window_)) return;
    RECT main{}, panel{}; GetWindowRect(host_, &main); GetWindowRect(window_, &panel);
    MONITORINFO display{sizeof(display)};
    if (!GetMonitorInfoW(MonitorFromWindow(host_, MONITOR_DEFAULTTONEAREST), &display)) return;
    const auto width = panel.right - panel.left, height = panel.bottom - panel.top;
    int x = main.right;
    if (x + width > display.rcWork.right) x = main.left - width;
    x = std::clamp(x, static_cast<int>(display.rcWork.left), std::max(static_cast<int>(display.rcWork.left), static_cast<int>(display.rcWork.right - width)));
    const auto y = std::clamp(static_cast<int>(main.top), static_cast<int>(display.rcWork.top), std::max(static_cast<int>(display.rcWork.top), static_cast<int>(display.rcWork.bottom - height)));
    if (panel.left != x || panel.top != y)
        SetWindowPos(window_, nullptr, x, y, 0, 0, SWP_NOZORDER | SWP_NOSIZE | SWP_NOACTIVATE);
}
void MonitorWindow::restore_position(int x, int y) {
    if (!window_) return;
    RECT rectangle{};
    GetWindowRect(window_, &rectangle);
    const auto width = rectangle.right - rectangle.left;
    const auto height = rectangle.bottom - rectangle.top;
    POINT position{x, y};
    MONITORINFO monitor{sizeof(monitor)};
    if (!GetMonitorInfoW(MonitorFromPoint(position, MONITOR_DEFAULTTONEAREST), &monitor)) return;
    x = std::clamp(x, static_cast<int>(monitor.rcWork.left), std::max(static_cast<int>(monitor.rcWork.left), static_cast<int>(monitor.rcWork.right - width)));
    y = std::clamp(y, static_cast<int>(monitor.rcWork.top), std::max(static_cast<int>(monitor.rcWork.top), static_cast<int>(monitor.rcWork.bottom - height)));
    SetWindowPos(window_, nullptr, x, y, 0, 0, SWP_NOZORDER | SWP_NOSIZE | SWP_NOACTIVATE);
    remember_geometry();
}
LRESULT CALLBACK MonitorWindow::control_proc(HWND control, UINT message, WPARAM wparam, LPARAM lparam, UINT_PTR, DWORD_PTR data) {
    auto* self = reinterpret_cast<MonitorWindow*>(data);
    if (is_weight_button(control)) {
        if (message == WM_PAINT) {
            PAINTSTRUCT painting{}; const auto dc = BeginPaint(control, &painting);
            paint_weight(control, dc); EndPaint(control, &painting); return 0;
        }
        if (message == WM_PRINTCLIENT) { paint_weight(control, reinterpret_cast<HDC>(wparam)); return 0; }
        if (message == WM_ERASEBKGND) return 1;
    }
    if (message == WM_SETFOCUS) self->ensure_visible(control);
    if (message == WM_MOUSEWHEEL) return SendMessageW(self->window_, message, wparam, lparam);
    if (message == WM_KEYDOWN && wparam == VK_TAB) {
        const auto next = GetNextDlgTabItem(self->window_, control, GetKeyState(VK_SHIFT) < 0);
        if (next) SetFocus(next);
        return 0;
    }
    if (message == WM_KEYDOWN && (wparam == VK_NEXT || wparam == VK_PRIOR))
        return SendMessageW(self->window_, message, wparam, lparam);
    return DefSubclassProc(control, message, wparam, lparam);
}
INT_PTR CALLBACK MonitorWindow::procedure(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    auto* self = reinterpret_cast<MonitorWindow*>(GetWindowLongPtrW(window, DWLP_USER));
    try {
        if (message == WM_INITDIALOG) {
            self = reinterpret_cast<MonitorWindow*>(lparam);
            self->window_ = window;
            self->displayed_cooldown_ = -1;
            self->displayed_track_.clear();
            self->displayed_scopes_.clear(); self->scope_visible_ = false;
            self->attached_ = self->wanted_visible_ = self->host_hidden_ = false;
            self->sizing_ = false;
            self->scroll_ = self->wheel_remainder_ = 0;
            SetWindowLongPtrW(window, DWLP_USER, lparam);
            GetWindowRect(window, &self->floating_bounds_);
            LOGFONTW weight_font{};
            if (GetObjectW(reinterpret_cast<HFONT>(SendDlgItemMessageW(window, IDC_CURRENT_WEIGHT, WM_GETFONT, 0, 0)), sizeof(weight_font), &weight_font)) {
                weight_font.lfWeight = FW_BOLD;
                self->weight_font_ = CreateFontIndirectW(&weight_font);
                if (self->weight_font_) SendDlgItemMessageW(window, IDC_CURRENT_WEIGHT, WM_SETFONT, reinterpret_cast<WPARAM>(self->weight_font_), FALSE);
            }
            EnumChildWindows(window, [](HWND child, LPARAM owner) -> BOOL {
                SetWindowSubclass(child, control_proc, 1, static_cast<DWORD_PTR>(owner)); return TRUE;
            }, reinterpret_cast<LPARAM>(self));
            SetTimer(window, 1, 500, nullptr);
            SetTimer(window, 2, 100, nullptr);
            self->layout();
            return TRUE;
        }
        if (!self) return FALSE;
        if (message == WM_TIMER && wparam == 2) { self->sync_attachment(); return TRUE; }
        if (message == WM_TIMER || message == WM_APP + 1) { if (self->on_tick) self->on_tick(); return TRUE; }
        if (message == WM_GETMINMAXINFO) {
            RECT minimum{0, 0, x_units(window, 200), y_units(window, 125)};
            AdjustWindowRectEx(&minimum, static_cast<DWORD>(GetWindowLongPtrW(window, GWL_STYLE)) | WS_VSCROLL, FALSE,
                static_cast<DWORD>(GetWindowLongPtrW(window, GWL_EXSTYLE)));
            auto* limits = reinterpret_cast<MINMAXINFO*>(lparam);
            limits->ptMinTrackSize = {minimum.right - minimum.left, minimum.bottom - minimum.top};
            return TRUE;
        }
        if (message == WM_SIZE) { self->layout(); self->sync_attachment(); return TRUE; }
        if (message == WM_ENTERSIZEMOVE) { self->sizing_ = true; return TRUE; }
        if (message == WM_EXITSIZEMOVE) {
            self->sizing_ = false;
            self->remember_geometry(); self->sync_attachment();
            if (self->on_move) self->on_move(); return TRUE;
        }
        if (message == WM_VSCROLL) {
            SCROLLINFO info{sizeof(info), SIF_ALL}; GetScrollInfo(window, SB_VERT, &info);
            int next = self->scroll_;
            switch (LOWORD(wparam)) {
            case SB_TOP: next = 0; break;
            case SB_BOTTOM: next = self->content_height_; break;
            case SB_LINEUP: next -= y_units(window, 14); break;
            case SB_LINEDOWN: next += y_units(window, 14); break;
            case SB_PAGEUP: next -= info.nPage; break;
            case SB_PAGEDOWN: next += info.nPage; break;
            case SB_THUMBTRACK: case SB_THUMBPOSITION: next = info.nTrackPos; break;
            }
            self->scroll_to(next); return TRUE;
        }
        if (message == WM_MOUSEWHEEL) {
            UINT lines = 3; SystemParametersInfoW(SPI_GETWHEELSCROLLLINES, 0, &lines, 0);
            self->wheel_remainder_ += static_cast<short>(HIWORD(wparam));
            const auto steps = self->wheel_remainder_ / WHEEL_DELTA;
            self->wheel_remainder_ %= WHEEL_DELTA;
            RECT client{}; GetClientRect(window, &client);
            const auto distance = lines == WHEEL_PAGESCROLL ? static_cast<int>(client.bottom) : y_units(window, 10) * static_cast<int>(lines);
            self->scroll_to(self->scroll_ - steps * distance); return TRUE;
        }
        if (message == WM_KEYDOWN) {
            RECT client{}; GetClientRect(window, &client);
            if (wparam == VK_NEXT) self->scroll_to(self->scroll_ + client.bottom);
            else if (wparam == VK_PRIOR) self->scroll_to(self->scroll_ - client.bottom);
            else if (wparam == VK_HOME) self->scroll_to(0);
            else if (wparam == VK_END) self->scroll_to(self->content_height_);
            else return FALSE;
            return TRUE;
        }
        if (message == WM_COMMAND) {
            const auto id = LOWORD(wparam);
            if (id == IDC_ENABLED && self->on_enabled) self->on_enabled(IsDlgButtonChecked(window, id) == BST_CHECKED);
            else if (id == IDC_PINNED && self->on_pinned) self->on_pinned(IsDlgButtonChecked(window, id) == BST_CHECKED);
            else if (id == IDC_ATTACHED && self->on_attached) self->on_attached(IsDlgButtonChecked(window, id) == BST_CHECKED);
            else if (id == IDC_EDIT_WEIGHTS && self->on_weights) self->on_weights();
            else if (id == IDC_REROLL_NEXT && self->on_reroll) self->on_reroll();
            else if (id >= IDC_QUICK_WEIGHT_0 && id <= IDC_QUICK_WEIGHT_10 && self->on_weight) self->on_weight(id - IDC_QUICK_WEIGHT_0);
            else if (id == IDC_OPEN_LOGS && self->on_logs) self->on_logs();
            else if (id == IDC_UPDATES && self->on_updates) self->on_updates();
            else if (id == IDC_RESET_LOCAL && self->on_clear_override) self->on_clear_override();
            else if (id == IDC_WEIGHT_SCOPE && HIWORD(wparam) == CBN_SELCHANGE && self->on_scope) {
                const auto index = SendDlgItemMessageW(window, id, CB_GETCURSEL, 0, 0);
                if (index >= 0) self->on_scope(static_cast<int>(SendDlgItemMessageW(window, id, CB_GETITEMDATA, index, 0)));
            }
            else if (id == IDC_APPLY_COOLDOWN && self->on_cooldown) {
                wchar_t input[16]{};
                const auto length = GetWindowTextLengthW(GetDlgItem(window, IDC_COOLDOWN));
                GetDlgItemTextW(window, IDC_COOLDOWN, input, 16);
                int count = 0;
                bool valid = length > 0 && length < 16;
                for (int i = 0; valid && i < length; ++i) {
                    valid = input[i] >= L'0' && input[i] <= L'9';
                    if (valid) { count = count * 10 + (input[i] - L'0'); valid = count <= 10000; }
                }
                if (!valid) throw std::invalid_argument("Enter a cooldown from 0 to 10000 tracks. Zero turns cooldown off.");
                self->on_cooldown(count);
            }
            else if (id == IDCANCEL) { self->wanted_visible_ = false; ShowWindow(window, SW_HIDE); }
            return TRUE;
        }
        if (message == WM_CLOSE) { self->wanted_visible_ = false; ShowWindow(window, SW_HIDE); return TRUE; }
        if (message == WM_DESTROY) {
            KillTimer(window, 1); KillTimer(window, 2);
            SendDlgItemMessageW(window, IDC_CURRENT_WEIGHT, WM_SETFONT, SendMessageW(window, WM_GETFONT, 0, 0), FALSE);
            if (self->weight_font_) DeleteObject(self->weight_font_);
            self->weight_font_ = nullptr; self->window_ = nullptr; return TRUE;
        }
    } catch (const std::exception& error) {
        if (self && self->on_error) self->on_error(error.what());
        MessageBoxA(window, error.what(), "IgnorantShuffle", MB_OK | MB_ICONERROR);
    } catch (...) {
        if (self && self->on_error) self->on_error("Unknown monitor exception.");
        OutputDebugStringW(L"IgnorantShuffle: monitor update failed.\n");
    }
    return FALSE;
}
