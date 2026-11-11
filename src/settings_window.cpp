#include "settings_window.h"
#include <commctrl.h>
#include <algorithm>
#include <stdexcept>
#include <cstring>
namespace {
const wchar_t* names[]{L"Track weights",L"Shuffle rules",L"Music folders",L"Statistics",L"Learning",L"Diagnostics and logs",L"About and updates"};
}
thread_local SettingsWindow* SettingsWindow::active_=nullptr;
LRESULT CALLBACK SettingsWindow::keyboard(int code,WPARAM wparam,LPARAM lparam){
    if(code>=0 && wparam==PM_REMOVE && active_ && IsWindowVisible(active_->window_)){
        auto* message=reinterpret_cast<MSG*>(lparam);
        if(message->message>=WM_KEYFIRST && message->message<=WM_KEYLAST &&
            (message->hwnd==active_->window_ || IsChild(active_->window_,message->hwnd)) && IsDialogMessageW(active_->window_,message)){
            message->message=WM_NULL;message->wParam=message->lParam=0;
        }
    }
    return CallNextHookEx(nullptr,code,wparam,lparam);
}
void SettingsWindow::show(HINSTANCE instance,HWND owner,int page) {
    if(!window_) {
        WNDCLASSEXW cls{sizeof(cls)};cls.lpfnWndProc=procedure;cls.hInstance=instance;cls.lpszClassName=L"IgnorantShuffle.Settings";
        cls.hCursor=LoadCursor(nullptr,IDC_ARROW);cls.hbrBackground=GetSysColorBrush(COLOR_BTNFACE);RegisterClassExW(&cls);
        if(!CreateWindowExW(WS_EX_CONTROLPARENT,cls.lpszClassName,L"IgnorantShuffle - Settings",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,
            CW_USEDEFAULT,CW_USEDEFAULT,1120,840,owner,nullptr,instance,this))throw std::runtime_error("Could not create Settings");
    }
    select(page);ShowWindow(window_,SW_SHOWNORMAL);SetForegroundWindow(window_);
}
void SettingsWindow::select(int page) {
    if(page<0 || page>=7)return;
    if(content_)ShowWindow(content_,SW_HIDE);
    if(on_page)pages_[page]=on_page(page,window_);
    selected_=page;content_=pages_[page];
    SetWindowTextW(title_,names[page]);
    for(int i=0;i<7;++i)SendMessageW(GetDlgItem(window_,3000+i),BM_SETCHECK,i==page?BST_CHECKED:BST_UNCHECKED,0);
    if(content_){
        SetParent(content_,window_);
        auto style=GetWindowLongPtrW(content_,GWL_STYLE);
        SetWindowLongPtrW(content_,GWL_STYLE,(style & ~(WS_POPUP|WS_CAPTION|WS_THICKFRAME|WS_SYSMENU|WS_MINIMIZEBOX|WS_MAXIMIZEBOX))|WS_CHILD|WS_CLIPCHILDREN);
        SetWindowLongPtrW(content_,GWL_EXSTYLE,GetWindowLongPtrW(content_,GWL_EXSTYLE)&~WS_EX_APPWINDOW);
        ShowWindow(GetDlgItem(content_,IDOK),SW_HIDE);ShowWindow(GetDlgItem(content_,IDCANCEL),SW_HIDE);
        SetWindowPos(content_,nullptr,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_FRAMECHANGED);
        layout();ShowWindow(content_,SW_SHOW);
    }
    feedback(L"Changes apply when you use the controls on this page.");
}
void SettingsWindow::layout(){
    if(!window_)return;RECT r{};GetClientRect(window_,&r);
    const int dpi=GetDpiForWindow(window_);auto u=[&](int x){return MulDiv(x,dpi,96);};
    MoveWindow(navigation_,u(12),u(12),u(192),std::max(1L,r.bottom-u(60)),TRUE);
    MoveWindow(title_,u(226),u(16),std::max(1L,r.right-u(250)),u(30),TRUE);
    MoveWindow(status_,u(16),r.bottom-u(34),std::max(1L,r.right-u(32)),u(24),TRUE);
    if(content_)MoveWindow(content_,u(216),u(55),std::max(1L,r.right-u(228)),std::max(1L,r.bottom-u(105)),TRUE);
}
void SettingsWindow::feedback(const std::wstring& text){if(status_)SetWindowTextW(status_,text.c_str());}
void SettingsWindow::destroy(){if(window_)DestroyWindow(window_);}
LRESULT CALLBACK SettingsWindow::procedure(HWND window,UINT message,WPARAM wparam,LPARAM lparam){
    auto* self=reinterpret_cast<SettingsWindow*>(GetWindowLongPtrW(window,GWLP_USERDATA));
    if(message==WM_NCCREATE){self=static_cast<SettingsWindow*>(reinterpret_cast<CREATESTRUCTW*>(lparam)->lpCreateParams);self->window_=window;SetWindowLongPtrW(window,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(self));}
    if(!self)return DefWindowProcW(window,message,wparam,lparam);
    try {
        if(message==WM_CREATE){
            NONCLIENTMETRICSW metrics{sizeof(metrics)};SystemParametersInfoW(SPI_GETNONCLIENTMETRICS,sizeof(metrics),&metrics,0);self->font_=CreateFontIndirectW(&metrics.lfMessageFont);
            auto add=[&](HWND parent,const wchar_t* cls,const wchar_t* text,int id,DWORD style,int x,int y,int w,int h){auto child=CreateWindowExW(0,cls,text,WS_CHILD|WS_VISIBLE|style,x,y,w,h,parent,reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),GetModuleHandleW(nullptr),nullptr);SendMessageW(child,WM_SETFONT,reinterpret_cast<WPARAM>(self->font_),TRUE);return child;};
            self->navigation_=add(window,L"STATIC",L"",0,WS_CLIPCHILDREN|WS_CLIPSIBLINGS,0,0,1,1);
            const int dpi=GetDpiForWindow(window);auto u=[&](int x){return MulDiv(x,dpi,96);};int y=8;
            for(int i=0;i<7;++i){
                if(i==0 || i==3 || i==5){add(self->navigation_,L"STATIC",i==0?L"LIBRARY":i==3?L"LISTENING":L"APPLICATION",0,SS_LEFT,u(8),u(y),u(180),u(22));y+=30;}
                // Buttons belong to the shell so keyboard navigation follows normal dialog order.
                add(window,L"BUTTON",names[i],3000+i,BS_RADIOBUTTON|BS_PUSHLIKE|WS_TABSTOP,u(20),u(y+12),u(184),u(34));y+=42;
            }
            self->title_=add(window,L"STATIC",L"",0,SS_LEFT,0,0,1,1);self->status_=add(window,L"STATIC",L"",0,SS_LEFT,0,0,1,1);
            active_=self;self->keyboard_hook_=SetWindowsHookExW(WH_GETMESSAGE,keyboard,nullptr,GetCurrentThreadId());
            metrics.lfMessageFont.lfHeight=-u(22);metrics.lfMessageFont.lfWeight=FW_SEMIBOLD;self->title_font_=CreateFontIndirectW(&metrics.lfMessageFont);SendMessageW(self->title_,WM_SETFONT,reinterpret_cast<WPARAM>(self->title_font_),TRUE);
            self->pages_.resize(7);self->layout();return 0;
        }
        if(message==WM_COMMAND && LOWORD(wparam)>=3000 && LOWORD(wparam)<3007){self->select(LOWORD(wparam)-3000);return 0;}
        if(message==WM_COMMAND && LOWORD(wparam)==IDCANCEL){ShowWindow(window,SW_HIDE);return 0;}
        if(message==WM_SIZE){self->layout();return 0;}
        if(message==WM_GETMINMAXINFO){const int dpi=GetDpiForWindow(window);reinterpret_cast<MINMAXINFO*>(lparam)->ptMinTrackSize={MulDiv(1080,dpi,96),MulDiv(790,dpi,96)};return 0;}
        if(message==WM_CLOSE){ShowWindow(window,SW_HIDE);return 0;}
        if(message==WM_DESTROY){if(self->keyboard_hook_)UnhookWindowsHookEx(self->keyboard_hook_);self->keyboard_hook_=nullptr;if(active_==self)active_=nullptr;self->window_=self->content_=nullptr;self->pages_.clear();if(self->font_)DeleteObject(self->font_);if(self->title_font_)DeleteObject(self->title_font_);self->font_=self->title_font_=nullptr;return 0;}
    } catch(const std::exception& e){self->feedback(std::wstring(L"Action failed. ")+std::wstring(e.what(),e.what()+strlen(e.what())));return 0;}
    return DefWindowProcW(window,message,wparam,lparam);
}
