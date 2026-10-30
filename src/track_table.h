#pragma once
#include <windows.h>
#include <commctrl.h>
#include <string>
#include <vector>

struct TrackTableRow {
    std::wstring title, path, source;
    double weight = 5, editing_weight = 5;
    bool below_minimum = false;
    bool excluded() const { return weight == 0 || below_minimum; }
};

// Native report view keeps numeric weights separate from long file paths.
class TrackTable {
    HWND window_ = nullptr;
    HFONT bold_ = nullptr;
    bool updating_ = false, edit_visible_ = false;
    bool high_contrast_ = false;
    int sort_column_ = -1;
    bool sort_ascending_ = true;
    std::wstring edit_caption_;
    std::vector<TrackTableRow> rows_;
    void fit_columns();
public:
    ~TrackTable() { detach(); }
    void attach(HWND window);
    void detach();
    bool updating() const { return updating_; }
    const auto& rows() const { return rows_; }
    std::vector<int> selected() const;
    void select_all();
    void sort_by(int column);
    void sort(std::vector<TrackTableRow>& rows) const;
    void replace(std::vector<TrackTableRow> rows, const std::vector<int>& selected, int caret, int anchor, int top,
        bool show_edit, const std::wstring& edit_caption);
    LRESULT notify(NMHDR* notification);
};
