#pragma once
#include <windows.h>
#include <functional>
#include <set>
#include "statistics.h"

struct StatisticsWindowData {
    std::vector<ignorant::StatRecord> records;
    std::set<std::string> library;
    ignorant::LearningOptions options;
    bool collecting=true,finite=false;
    std::size_t pass_heard=0,pass_remaining=0;
    std::string status,logs;
};
class StatisticsWindow {
    HWND window_=nullptr;
    HFONT font_=nullptr;
    int dpi_=96, page_override_=-1;
    bool updating_=false;
    StatisticsWindowData data_;
    ignorant::LearningOptions displayed_options_;
    ignorant::StatisticsSummary summary_;
    struct Row {std::string id;std::vector<std::wstring> cells;};
    std::vector<Row> rows_;
    static LRESULT CALLBACK procedure(HWND,UINT,WPARAM,LPARAM);
    void create_controls();
    void layout();
    void refresh();
    void learning_controls();
    void apply_learning();
    void chart(HDC,RECT);
    int units(int value) const {return MulDiv(value,dpi_,96);}
public:
    std::function<StatisticsWindowData()> on_data;
    std::function<void(const ignorant::LearningOptions&,bool)> on_settings;
    std::function<void(const std::wstring&)> on_feedback;
    std::function<void()> on_clear,on_sync,on_log_folder;
    std::function<void(bool)> on_export;
    ~StatisticsWindow(){destroy();}
    HWND handle() const {return window_;}
    void show(HINSTANCE instance,HWND owner,int page=0,bool embedded=false);
    void destroy();
};
