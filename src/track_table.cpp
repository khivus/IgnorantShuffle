#include "track_table.h"
#include <algorithm>
#include <filesystem>
#include <iomanip>
#include <sstream>

namespace {
std::wstring number(double value) {
    std::wostringstream text; text << std::setprecision(8) << value; return text.str();
}
int units(HWND list, int value) {
    RECT rectangle{0, 0, value, 0}; MapDialogRect(GetParent(list), &rectangle); return rectangle.right;
}
}
void TrackTable::attach(HWND window) {
    detach(); window_ = window;
    SendMessageW(window_, CCM_SETVERSION, 5, 0);
    ListView_SetExtendedListViewStyle(window_, LVS_EX_FULLROWSELECT | LVS_EX_GRIDLINES | LVS_EX_DOUBLEBUFFER | LVS_EX_LABELTIP | LVS_EX_INFOTIP);
    const wchar_t* labels[]{L"Weight", L"Track", L"Source", L"Edit value", L"Folder"};
    for (int i = 0; i < 5; ++i) {
        LVCOLUMNW column{}; column.mask = LVCF_TEXT | LVCF_WIDTH | LVCF_FMT;
        column.pszText = const_cast<wchar_t*>(labels[i]); column.cx = 80;
        column.fmt = (i == 0 || i == 3) ? LVCFMT_CENTER : LVCFMT_LEFT;
        ListView_InsertColumn(window_, i, &column);
    }
    LOGFONTW font{};
    if (GetObjectW(reinterpret_cast<HFONT>(SendMessageW(window_, WM_GETFONT, 0, 0)), sizeof(font), &font)) {
        font.lfWeight = FW_BOLD; bold_ = CreateFontIndirectW(&font);
    }
    fit_columns();
}
void TrackTable::detach() {
    if (bold_) DeleteObject(bold_);
    bold_ = nullptr; window_ = nullptr; rows_.clear(); edit_caption_.clear(); edit_visible_ = false;
}
void TrackTable::fit_columns() {
    RECT client{}; GetClientRect(window_, &client);
    const auto weight = units(window_, 61), source = units(window_, 66), folder = units(window_, 106);
    const auto editing = edit_visible_ ? std::max(units(window_, 70), ListView_GetStringWidth(window_, edit_caption_.c_str()) + 18) : 0;
    const auto title = std::max(units(window_, 100), static_cast<int>(client.right) - weight - source - folder - editing - 2);
    const int widths[]{weight, title, source, editing, folder};
    for (int i = 0; i < 5; ++i) ListView_SetColumnWidth(window_, i, widths[i]);
}
std::vector<int> TrackTable::selected() const {
    std::vector<int> result;
    if (!window_) return result;
    result.reserve(ListView_GetSelectedCount(window_));
    for (int row = -1; (row = ListView_GetNextItem(window_, row, LVNI_SELECTED)) != -1;) result.push_back(row);
    return result;
}
void TrackTable::select_all() {
    updating_ = true;
    ListView_SetItemState(window_, -1, LVIS_SELECTED, LVIS_SELECTED);
    updating_ = false;
}
void TrackTable::replace(std::vector<TrackTableRow> rows, const std::vector<int>& selected, int caret, int anchor, int top,
    bool show_edit, const std::wstring& edit_caption) {
    updating_ = true;
    SendMessageW(window_, WM_SETREDRAW, FALSE, 0);
    struct Guard {
        HWND list; bool& updating;
        ~Guard() { updating = false; SendMessageW(list, WM_SETREDRAW, TRUE, 0); InvalidateRect(list, nullptr, TRUE); }
    } guard{window_, updating_};
    const bool resize = rows_.empty() || edit_visible_ != show_edit || edit_caption_ != edit_caption;
    ListView_DeleteAllItems(window_);
    rows_ = std::move(rows); edit_visible_ = show_edit; edit_caption_ = edit_caption;
    LVCOLUMNW edit{}; edit.mask = LVCF_TEXT; edit.pszText = const_cast<wchar_t*>(edit_caption.c_str());
    ListView_SetColumn(window_, 3, &edit);
    for (std::size_t i = 0; i < rows_.size(); ++i) {
        const auto& row = rows_[i];
        auto weight = number(row.weight);
        if (row.weight == 0) weight += L"  Excluded";
        LVITEMW item{}; item.mask = LVIF_TEXT; item.iItem = static_cast<int>(i); item.pszText = weight.data();
        const auto index = ListView_InsertItem(window_, &item);
        auto folder = std::filesystem::path(row.path).parent_path().wstring();
        auto editing = number(row.editing_weight);
        const std::wstring texts[]{row.title, row.source, editing, folder};
        for (int column = 1; column < 5; ++column)
            ListView_SetItemText(window_, index, column, const_cast<wchar_t*>(texts[column - 1].c_str()));
    }
    for (const auto row : selected)
        if (row >= 0 && static_cast<std::size_t>(row) < rows_.size()) ListView_SetItemState(window_, row, LVIS_SELECTED, LVIS_SELECTED);
    if (caret >= 0 && static_cast<std::size_t>(caret) < rows_.size()) ListView_SetItemState(window_, caret, LVIS_FOCUSED, LVIS_FOCUSED);
    if (anchor >= 0 && static_cast<std::size_t>(anchor) < rows_.size()) ListView_SetSelectionMark(window_, anchor);
    if (top > 0 && static_cast<std::size_t>(top) < rows_.size()) {
        RECT first{};
        if (ListView_GetItemRect(window_, 0, &first, LVIR_BOUNDS)) ListView_Scroll(window_, 0, top * (first.bottom - first.top));
    }
    if (resize) fit_columns();
}
LRESULT TrackTable::notify(NMHDR* notification) {
    if (notification->code == NM_CUSTOMDRAW) {
        auto* drawing = reinterpret_cast<NMLVCUSTOMDRAW*>(notification);
        const auto stage = drawing->nmcd.dwDrawStage;
        if (stage == CDDS_PREPAINT) {
            HIGHCONTRASTW contrast{sizeof(contrast)};
            high_contrast_ = SystemParametersInfoW(SPI_GETHIGHCONTRAST, sizeof(contrast), &contrast, 0) && (contrast.dwFlags & HCF_HIGHCONTRASTON);
            return CDRF_NOTIFYITEMDRAW;
        }
        if (stage == CDDS_ITEMPREPAINT) return CDRF_NOTIFYSUBITEMDRAW;
        if (stage == (CDDS_ITEMPREPAINT | CDDS_SUBITEM)) {
            const auto index = static_cast<std::size_t>(drawing->nmcd.dwItemSpec);
            SelectObject(drawing->nmcd.hdc, drawing->iSubItem == 0 && bold_ ? bold_ : reinterpret_cast<HFONT>(SendMessageW(window_, WM_GETFONT, 0, 0)));
            if (index < rows_.size() && !ListView_GetItemState(window_, static_cast<int>(index), LVIS_SELECTED)) {
                if (!high_contrast_ && drawing->iSubItem == 0 && rows_[index].weight == 0) drawing->clrText = RGB(175, 30, 30);
                else if (!high_contrast_ && drawing->iSubItem == 3 && rows_[index].weight != rows_[index].editing_weight) drawing->clrText = RGB(0, 80, 150);
                else drawing->clrText = GetSysColor(COLOR_WINDOWTEXT);
            }
            return CDRF_NEWFONT;
        }
    }
    if (notification->code == LVN_GETINFOTIPW) {
        auto* tip = reinterpret_cast<NMLVGETINFOTIPW*>(notification);
        if (tip->iItem >= 0 && static_cast<std::size_t>(tip->iItem) < rows_.size() && tip->cchTextMax > 0) {
            const auto& row = rows_[tip->iItem];
            std::wostringstream text;
            text << row.title << L"\r\nPlayback weight: " << std::setprecision(17) << row.weight;
            if (row.weight == 0) text << L" (excluded)";
            text << L"\r\nSource: " << row.source;
            if (edit_visible_) text << L"\r\n" << edit_caption_ << L": " << row.editing_weight;
            text << L"\r\n" << row.path;
            wcsncpy_s(tip->pszText, static_cast<std::size_t>(tip->cchTextMax), text.str().c_str(), _TRUNCATE);
        }
    }
    return 0;
}
