#pragma once
#include <windows.h>
#include <functional>
#include <string>
#include <vector>

// A modeless settings shell. Existing feature views live inside its content area.
class SettingsWindow {
    HWND window_=nullptr, navigation_=nullptr, title_=nullptr, status_=nullptr, content_=nullptr;
    HFONT font_=nullptr, title_font_=nullptr;
    HHOOK keyboard_hook_=nullptr;
    static thread_local SettingsWindow* active_;
    static LRESULT CALLBACK keyboard(int,WPARAM,LPARAM);
    int selected_=-1;
    std::vector<HWND> pages_;
    static LRESULT CALLBACK procedure(HWND,UINT,WPARAM,LPARAM);
    void layout();
    void select(int page);
public:
    std::function<HWND(int,HWND)> on_page;
    HWND handle() const {return window_;}
    void show(HINSTANCE instance,HWND owner,int page=0);
    void feedback(const std::wstring& text);
    void destroy();
    ~SettingsWindow(){destroy();}
};
