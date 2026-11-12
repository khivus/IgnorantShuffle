#include "statistics_window.h"
#include <stdexcept>
#include "windows_statistics.h"
#include <commctrl.h>
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <sstream>

namespace {
enum {RANGE=2101,SEARCH,TABS,LIST,SUMMARY,CHART,COLLECT,SYNC,CSV,JSON,CLEAR,
    BALANCE,BALANCE_STRENGTH,SMART,STRENGTH,LIMIT,SKIPS,FULL,REPLAYS,VOLUME,APPLY,HELP,LOG_FOLDER};
std::wstring wide(const std::string& text) {
    if(text.empty())return {};
    const auto count=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),static_cast<int>(text.size()),nullptr,0);
    if(!count)return L"[Invalid UTF-8]";
    std::wstring result(count,0);MultiByteToWideChar(CP_UTF8,0,text.data(),static_cast<int>(text.size()),result.data(),count);return result;
}
std::wstring text(HWND control) {std::wstring value(GetWindowTextLengthW(control)+1,0);value.resize(GetWindowTextW(control,value.data(),static_cast<int>(value.size())));return value;}
std::wstring time_text(std::int64_t milliseconds) {
    std::wostringstream out;out<<milliseconds/3600000<<L"h "<<(milliseconds/60000)%60<<L"m "<<(milliseconds/1000)%60<<L"s";return out.str();
}
std::wstring lower(std::wstring value) {if(!value.empty())CharLowerBuffW(value.data(),static_cast<DWORD>(value.size()));return value;}
void combo(HWND parent,int id,const std::vector<std::wstring>& values) {for(const auto& value:values)SendDlgItemMessageW(parent,id,CB_ADDSTRING,0,reinterpret_cast<LPARAM>(value.c_str()));SendDlgItemMessageW(parent,id,CB_SETCURSEL,0,0);}
}
void StatisticsWindow::show(HINSTANCE instance,HWND owner,int page,bool embedded) {
    if(!window_) {
        INITCOMMONCONTROLSEX controls{sizeof(controls),ICC_TAB_CLASSES|ICC_LISTVIEW_CLASSES};InitCommonControlsEx(&controls);
        WNDCLASSEXW type{sizeof(type)};type.hInstance=instance;type.lpfnWndProc=procedure;type.lpszClassName=L"IgnorantShuffle.Statistics";type.hCursor=LoadCursor(nullptr,IDC_ARROW);type.hbrBackground=GetSysColorBrush(COLOR_BTNFACE);
        RegisterClassExW(&type);
        window_=CreateWindowExW(embedded?WS_EX_CONTROLPARENT:WS_EX_APPWINDOW,type.lpszClassName,L"IgnorantShuffle - Statistics and learning",(embedded?WS_CHILD:WS_OVERLAPPEDWINDOW)|WS_CLIPCHILDREN,CW_USEDEFAULT,CW_USEDEFAULT,1000,760,owner,nullptr,instance,this);
        if(!window_)throw std::runtime_error("Could not open Statistics");
    }
    page_override_=page>=5?page:-1;
    if(page<5)TabCtrl_SetCurSel(GetDlgItem(window_,TABS),page);learning_controls();layout();refresh();ShowWindow(window_,SW_SHOWNORMAL);if(!embedded)SetForegroundWindow(window_);
}
void StatisticsWindow::destroy(){if(window_)DestroyWindow(window_);}
void StatisticsWindow::create_controls() {
    const auto dc=GetDC(window_);dpi_=GetDeviceCaps(dc,LOGPIXELSX);ReleaseDC(window_,dc);
    NONCLIENTMETRICSW metrics{sizeof(metrics)};SystemParametersInfoW(SPI_GETNONCLIENTMETRICS,sizeof(metrics),&metrics,0);font_=CreateFontIndirectW(&metrics.lfMessageFont);
    auto add=[&](int id,const wchar_t* cls,const wchar_t* label,DWORD style,DWORD ex=0) {
        auto child=CreateWindowExW(ex,cls,label,WS_CHILD|WS_VISIBLE|style,0,0,1,1,window_,reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),GetModuleHandleW(nullptr),nullptr);
        SendMessageW(child,WM_SETFONT,reinterpret_cast<WPARAM>(font_),FALSE);
    };
    add(RANGE,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP|WS_VSCROLL);
    combo(window_,RANGE,{L"Today",L"7 days",L"30 days",L"All time"});SendDlgItemMessageW(window_,RANGE,CB_SETCURSEL,1,0);
    add(SEARCH,L"EDIT",L"",WS_TABSTOP|ES_AUTOHSCROLL,WS_EX_CLIENTEDGE);SendDlgItemMessageW(window_,SEARCH,EM_SETCUEBANNER,TRUE,reinterpret_cast<LPARAM>(L"Search tracks, artists, paths or logs"));
    add(COLLECT,L"BUTTON",L"Collect statistics",BS_AUTOCHECKBOX|WS_TABSTOP);
    add(SYNC,L"BUTTON",L"Reload / sync",BS_PUSHBUTTON|WS_TABSTOP);add(CSV,L"BUTTON",L"Export CSV",BS_PUSHBUTTON|WS_TABSTOP);add(JSON,L"BUTTON",L"Export JSON",BS_PUSHBUTTON|WS_TABSTOP);add(CLEAR,L"BUTTON",L"Clear statistics...",BS_PUSHBUTTON|WS_TABSTOP);
    add(LOG_FOLDER,L"BUTTON",L"Open log folder",BS_PUSHBUTTON|WS_TABSTOP);
    add(TABS,WC_TABCONTROLW,L"",WS_TABSTOP);
    const wchar_t* tabs[]{L"Overview",L"Tracks",L"Artists",L"History",L"Shuffle",L"Learning",L"Logs"};
    for(int i=0;i<5;++i){TCITEMW item{};item.mask=TCIF_TEXT;item.pszText=const_cast<wchar_t*>(tabs[i]);TabCtrl_InsertItem(GetDlgItem(window_,TABS),i,&item);}
    add(SUMMARY,L"STATIC",L"",SS_LEFT|SS_NOPREFIX);
    add(CHART,L"STATIC",L"",SS_OWNERDRAW);
    add(LIST,WC_LISTVIEWW,L"",LVS_REPORT|LVS_SHOWSELALWAYS|LVS_SINGLESEL|WS_TABSTOP,WS_EX_CLIENTEDGE);
    ListView_SetExtendedListViewStyle(GetDlgItem(window_,LIST),LVS_EX_FULLROWSELECT|LVS_EX_DOUBLEBUFFER|LVS_EX_LABELTIP);
    add(BALANCE,L"BUTTON",L"Balance listening frequency",BS_AUTOCHECKBOX|WS_TABSTOP);
    add(BALANCE_STRENGTH,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP);combo(window_,BALANCE_STRENGTH,{L"Gentle - 25%",L"Medium - 50%",L"Full - 100%"});
    add(SMART,L"BUTTON",L"Smart weights: learn from listening",BS_AUTOCHECKBOX|WS_TABSTOP);
    add(STRENGTH,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP);combo(window_,STRENGTH,{L"Gentle learning",L"Normal learning",L"Stronger learning"});
    add(LIMIT,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP);combo(window_,LIMIT,{L"Maximum +/-5%",L"Maximum +/-15%",L"Maximum +/-25%"});
    add(SKIPS,L"BUTTON",L"Early skips: -2%",BS_AUTOCHECKBOX|WS_TABSTOP);add(FULL,L"BUTTON",L"Full listens: +0.5%",BS_AUTOCHECKBOX|WS_TABSTOP);
    add(REPLAYS,L"BUTTON",L"Qualified replays: +1%",BS_AUTOCHECKBOX|WS_TABSTOP);add(VOLUME,L"BUTTON",L"Volume increases: +0.25%",BS_AUTOCHECKBOX|WS_TABSTOP);
    add(APPLY,L"BUTTON",L"Apply learning settings",BS_PUSHBUTTON|WS_TABSTOP);
    add(HELP,L"STATIC",L"Learning is opt-in. Base weights, minimum exclusions, cooldown and AIMP's native queue remain authoritative.\r\n\r\nBalance favors less-played songs using qualified plays across synced libraries. Smart learning uses small preference changes, with a shared maximum gain of +1.5% per listening session.\r\n\r\nA play qualifies after 30 seconds or half the duration. Pauses, seeks, gaps and previews do not add listening time. Full listens need observed progress and the ending; replays must qualify. Volume is a weak signal and can be disabled.\r\n\r\nPause collection to freeze local learning. Clearing statistics also clears learned feedback, with synchronized clear markers. New feedback affects future rolls; saved song weights are unchanged.",SS_LEFT|SS_NOPREFIX);
    SetTimer(window_,1,2000,nullptr);
}
void StatisticsWindow::layout() {
    if(!window_)return;
    RECT r{};GetClientRect(window_,&r);const int w=r.right,h=r.bottom,m=units(12),gap=units(8),line=units(27);
    auto place=[&](int id,int x,int y,int width,int height){MoveWindow(GetDlgItem(window_,id),x,y,std::max(1,width),std::max(1,height),TRUE);};
    place(RANGE,m,m,units(130),units(180));place(SEARCH,m+units(138),m,w-3*m-units(310),line);place(COLLECT,w-m-units(170),m,units(170),line);
    const int toolbar=m+line+gap;int x=m;
    for(const auto id:{SYNC,CSV,JSON,CLEAR}) {const int width=units(id==CLEAR?150:115);place(id,x,toolbar,width,line);x+=width+gap;}
    const int tabs=toolbar+line+gap;place(TABS,m,tabs,w-2*m,units(30));
    ShowWindow(GetDlgItem(window_,TABS),page_override_>=0?SW_HIDE:SW_SHOW);
    const int page=(page_override_>=0?page_override_:TabCtrl_GetCurSel(GetDlgItem(window_,TABS)));
    const bool learning=page==5,overview=page==0;
    const int top=learning?m:tabs+(page_override_>=0?0:units(38));
    for(const int id:{RANGE,SEARCH,COLLECT,SYNC,CSV,JSON,CLEAR})ShowWindow(GetDlgItem(window_,id),learning?SW_HIDE:SW_SHOW);
    ShowWindow(GetDlgItem(window_,LOG_FOLDER),page==6?SW_SHOW:SW_HIDE);place(LOG_FOLDER,x,toolbar,units(120),line);
    ShowWindow(GetDlgItem(window_,SUMMARY),learning?SW_HIDE:SW_SHOW);
    ShowWindow(GetDlgItem(window_,CHART),overview?SW_SHOW:SW_HIDE);
    ShowWindow(GetDlgItem(window_,LIST),learning?SW_HIDE:SW_SHOW);
    for(int id=BALANCE;id<=HELP;++id)ShowWindow(GetDlgItem(window_,id),learning?SW_SHOW:SW_HIDE);
    if(learning) {
        const int right=m+units(330),field=std::max(units(150),w-m-right);
        place(BALANCE,m,top,units(310),line);place(BALANCE_STRENGTH,right,top,field,units(160));
        place(SMART,m,top+units(42),w-2*m,line);
        place(STRENGTH,m,top+units(80),units(260),units(160));place(LIMIT,right,top+units(80),field,units(160));
        place(SKIPS,m,top+units(122),units(310),line);place(FULL,right,top+units(122),field,line);
        place(REPLAYS,m,top+units(158),units(310),line);place(VOLUME,right,top+units(158),field,line);
        place(APPLY,m,top+units(202),units(250),line);
        place(HELP,m,top+units(246),w-2*m,h-top-units(258));
    } else {
        place(SUMMARY,m,top,w-2*m,overview?units(90):units(45));
        const int chart_top=top+units(92),chart_height=units(145);
        if(overview)place(CHART,m,chart_top,w-2*m,chart_height);
        const int list_top=overview?chart_top+chart_height+gap:top+units(48);
        place(LIST,m,list_top,w-2*m,h-list_top-m);
    }
}
void StatisticsWindow::learning_controls() {
    if(!on_data)return;data_=on_data();const auto& o=data_.options;
    displayed_options_=o;
    for(const auto pair:std::vector<std::pair<int,bool>>{{BALANCE,o.balance},{SMART,o.smart},{SKIPS,o.skips},{FULL,o.completions},{REPLAYS,o.replays},{VOLUME,o.volume},{COLLECT,data_.collecting}})CheckDlgButton(window_,pair.first,pair.second?BST_CHECKED:BST_UNCHECKED);
    const auto select_value=[&](int id,double value,const std::vector<double>& presets){
        if(SendDlgItemMessageW(window_,id,CB_GETCOUNT,0,0)>3)SendDlgItemMessageW(window_,id,CB_DELETESTRING,3,0);
        const auto found=std::find(presets.begin(),presets.end(),value);
        const int index=static_cast<int>(found-presets.begin());
        if(found==presets.end()){
            std::wostringstream label;label<<L"Custom: "<<std::setprecision(17)<<value;
            SendDlgItemMessageW(window_,id,CB_ADDSTRING,0,reinterpret_cast<LPARAM>(label.str().c_str()));
        }
        SendDlgItemMessageW(window_,id,CB_SETCURSEL,index,0);
    };
    select_value(BALANCE_STRENGTH,o.balance_strength,{.25,.5,1});
    select_value(STRENGTH,o.strength,{.5,1,2});
    select_value(LIMIT,o.limit,{.05,.15,.25});
}
void StatisticsWindow::apply_learning() {
    auto o=data_.options;
    auto checked=[&](int id){return IsDlgButtonChecked(window_,id)==BST_CHECKED;};
    o.balance=checked(BALANCE);o.smart=checked(SMART);o.skips=checked(SKIPS);o.completions=checked(FULL);o.replays=checked(REPLAYS);o.volume=checked(VOLUME);
    const double balance[]{.25,.5,1,displayed_options_.balance_strength},strength[]{.5,1,2,displayed_options_.strength},limits[]{.05,.15,.25,displayed_options_.limit};
    const auto index=[&](int id){return std::clamp(static_cast<int>(SendDlgItemMessageW(window_,id,CB_GETCURSEL,0,0)),0,3);};
    o.balance_strength=balance[index(BALANCE_STRENGTH)];o.strength=strength[index(STRENGTH)];o.limit=limits[index(LIMIT)];o.validate();
    if(on_settings)on_settings(o,checked(COLLECT));refresh();
}
void StatisticsWindow::refresh() {
    if(!window_ || !on_data || updating_)return;
    updating_=true;struct Guard{bool& value;~Guard(){value=false;}}guard{updating_};
    data_=on_data();CheckDlgButton(window_,COLLECT,data_.collecting?BST_CHECKED:BST_UNCHECKED);
    const int period=static_cast<int>(SendDlgItemMessageW(window_,RANGE,CB_GETCURSEL,0,0));
    const std::string since=period==0?ignorant::statistics_day():period==1?ignorant::statistics_day(6):period==2?ignorant::statistics_day(29):"";
    summary_=ignorant::summarize(data_.records,since);
    const int page=(page_override_>=0?page_override_:TabCtrl_GetCurSel(GetDlgItem(window_,TABS)));if(page==5)return;
    std::set<std::string> artists;std::size_t covered=0;
    for(const auto& pair:summary_.tracks){if(!pair.second.artist.empty())artists.insert(pair.second.artist);if(data_.library.count(pair.first))++covered;}
    std::wostringstream description;
    if(page==0)description<<L"Listening: "<<time_text(summary_.milliseconds)<<L"     Qualified plays: "<<summary_.plays<<L"\r\nUnique tracks: "<<summary_.tracks.size()<<L"     Artists: "<<artists.size()<<L"     Early exits: "<<summary_.exits<<L"     Coverage: "<<covered<<L" / "<<data_.library.size()<<L"\r\n";
    description<<wide(data_.status);SetWindowTextW(GetDlgItem(window_,SUMMARY),description.str().c_str());
    std::vector<Row> rows;std::vector<std::wstring> columns;
    if(page==0){columns={L"Day",L"Listening time"};for(auto i=summary_.days.rbegin();i!=summary_.days.rend();++i)rows.push_back({i->first,{wide(i->first),time_text(i->second)}});}
    if(page==1 || page==2) {
        columns=page==1?std::vector<std::wstring>{L"Track",L"Artist",L"Qualified plays",L"Listening",L"Early exits",L"Path"}:std::vector<std::wstring>{L"Artist",L"Qualified plays",L"Listening",L"Early exits"};
        std::map<std::string,ignorant::TrackStatistics> entries;
        for(const auto& pair:summary_.tracks) {
            const auto& track=pair.second;if(page==1)entries[pair.first]=track;
            else if(!track.artist.empty()){auto& a=entries[track.artist];a.key=a.title=track.artist;a.milliseconds+=track.milliseconds;a.plays+=track.plays;a.exits+=track.exits;}
        }
        std::vector<ignorant::TrackStatistics> sorted;for(const auto& pair:entries)sorted.push_back(pair.second);
        std::sort(sorted.begin(),sorted.end(),[](const auto& a,const auto& b){return a.plays==b.plays?a.milliseconds>b.milliseconds:a.plays>b.plays;});
        for(const auto& t:sorted)rows.push_back({t.key,page==1?std::vector<std::wstring>{wide(t.title),wide(t.artist),std::to_wstring(t.plays),time_text(t.milliseconds),std::to_wstring(t.exits),wide(t.key)}:std::vector<std::wstring>{wide(t.title),std::to_wstring(t.plays),time_text(t.milliseconds),std::to_wstring(t.exits)}});
    }
    if(page==3) {
        columns={L"Day",L"Track",L"Artist",L"Listening",L"Qualified",L"Early exit",L"Path"};
        std::set<std::string> used;
        for(const auto& r:summary_.history) {if(rows.size()>=200)break;if(!used.insert(r.session).second)continue;
            std::int64_t ms=0,plays=0,exits=0;for(const auto& x:summary_.history)if(x.session==r.session){ms+=x.milliseconds;plays+=x.plays;exits+=x.exits;}
            rows.push_back({r.session,{wide(r.day),wide(r.title),wide(r.artist),time_text(ms),plays?L"Yes":L"No",exits?L"Yes":L"No",wide(r.key)}});
        }
    }
    if(page==4) {
        columns={L"Shuffle metric",L"Count / progress"};for(const auto& c:summary_.counters)rows.push_back({c.first,{wide(c.first),std::to_wstring(c.second)}});
        rows.push_back({"live-pass",{L"Current finite pass",data_.finite?std::to_wstring(data_.pass_heard)+L" heard; "+std::to_wstring(data_.pass_remaining)+L" eligible remaining":L"Off"}});
    }
    if(page==6) {columns={L"Diagnostic event (newest first; double-click for full text)"};std::istringstream input(data_.logs);std::string line;while(std::getline(input,line))if(!line.empty())rows.push_back({line,{wide(line)}});std::reverse(rows.begin(),rows.end());}
    const auto query=lower(text(GetDlgItem(window_,SEARCH)));
    if(!query.empty())rows.erase(std::remove_if(rows.begin(),rows.end(),[&](const auto& row){for(const auto& c:row.cells)if(lower(c).find(query)!=std::wstring::npos)return false;return true;}),rows.end());
    const auto list=GetDlgItem(window_,LIST);const int top=ListView_GetTopIndex(list),selected=ListView_GetNextItem(list,-1,LVNI_SELECTED);
    const auto top_id=top>=0 && static_cast<std::size_t>(top)<rows_.size()?rows_[top].id:"";
    const auto selected_id=selected>=0 && static_cast<std::size_t>(selected)<rows_.size()?rows_[selected].id:"";
    SendMessageW(list,WM_SETREDRAW,FALSE,0);ListView_DeleteAllItems(list);while(ListView_DeleteColumn(list,0)){}
    RECT bounds{};GetClientRect(list,&bounds);const int count=static_cast<int>(columns.size());
    for(int i=0;i<count;++i){LVCOLUMNW column{};column.mask=LVCF_TEXT|LVCF_WIDTH;column.pszText=columns[i].data();column.cx=count==1?std::max(400L,bounds.right-24):units(i==0?210:i==1?150:125);ListView_InsertColumn(list,i,&column);}
    int restore=0;
    for(std::size_t i=0;i<rows.size();++i){LVITEMW item{};item.mask=LVIF_TEXT;item.iItem=static_cast<int>(i);item.pszText=rows[i].cells[0].data();ListView_InsertItem(list,&item);
        for(std::size_t j=1;j<rows[i].cells.size();++j)ListView_SetItemText(list,static_cast<int>(i),static_cast<int>(j),rows[i].cells[j].data());
        if(rows[i].id==top_id)restore=static_cast<int>(i);if(rows[i].id==selected_id)ListView_SetItemState(list,static_cast<int>(i),LVIS_SELECTED,LVIS_SELECTED);
    }
    if(!rows.empty()) {ListView_EnsureVisible(list,restore,FALSE);RECT item{};if(ListView_GetItemRect(list,restore,&item,LVIR_BOUNDS))ListView_Scroll(list,0,(restore-ListView_GetTopIndex(list))*(item.bottom-item.top));}
    rows_=std::move(rows);SendMessageW(list,WM_SETREDRAW,TRUE,0);InvalidateRect(list,nullptr,TRUE);InvalidateRect(GetDlgItem(window_,CHART),nullptr,TRUE);
}
void StatisticsWindow::chart(HDC dc,RECT r) {
    FillRect(dc,&r,GetSysColorBrush(COLOR_WINDOW));SetBkMode(dc,TRANSPARENT);SelectObject(dc,font_);
    const int period=static_cast<int>(SendDlgItemMessageW(window_,RANGE,CB_GETCURSEL,0,0)),days=period==0?1:period==1?7:30;
    std::int64_t maximum=1;for(int i=0;i<days;++i)maximum=std::max(maximum,summary_.days[ignorant::statistics_day(i)]);
    const int bottom=r.bottom-units(24),height=std::max(1,bottom-static_cast<int>(r.top)-units(8)),width=std::max(1,static_cast<int>(r.right-r.left)/days);
    for(int i=0;i<days;++i){const auto value=summary_.days[ignorant::statistics_day(days-1-i)];RECT bar{r.left+i*width+units(2),bottom-static_cast<LONG>(height*static_cast<double>(value)/maximum),r.left+(i+1)*width-units(2),bottom};FillRect(dc,&bar,GetSysColorBrush(COLOR_HIGHLIGHT));}
    const auto label=wide(ignorant::statistics_day(days-1)+" - "+ignorant::statistics_day())+L"   Daily listening; max "+time_text(maximum)+(period==3?L" (latest 30 days; totals include all time)":L"");
    RECT caption{r.left,bottom+units(3),r.right,r.bottom};DrawTextW(dc,label.c_str(),-1,&caption,DT_SINGLELINE|DT_END_ELLIPSIS|DT_NOPREFIX);
}
LRESULT CALLBACK StatisticsWindow::procedure(HWND window,UINT message,WPARAM wparam,LPARAM lparam) {
    auto* self=reinterpret_cast<StatisticsWindow*>(GetWindowLongPtrW(window,GWLP_USERDATA));
    try {
        if(message==WM_NCCREATE){self=static_cast<StatisticsWindow*>(reinterpret_cast<CREATESTRUCTW*>(lparam)->lpCreateParams);self->window_=window;SetWindowLongPtrW(window,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(self));}
        if(!self)return DefWindowProcW(window,message,wparam,lparam);
        if(message==WM_CREATE){self->create_controls();self->learning_controls();self->layout();self->refresh();return 0;}
        if(message==WM_SIZE){self->layout();return 0;}
        if(message==WM_GETMINMAXINFO){auto* size=reinterpret_cast<MINMAXINFO*>(lparam);size->ptMinTrackSize={self->units(720),self->units(610)};return 0;}
        if(message==WM_TIMER){if(IsWindowVisible(window))self->refresh();return 0;}
        if(message==WM_DRAWITEM && wparam==CHART){auto* draw=reinterpret_cast<DRAWITEMSTRUCT*>(lparam);self->chart(draw->hDC,draw->rcItem);return TRUE;}
        if(message==WM_NOTIFY){const auto* n=reinterpret_cast<NMHDR*>(lparam);
            if(n->idFrom==TABS && n->code==TCN_SELCHANGE){self->layout();self->refresh();return 0;}
            if(n->idFrom==LIST && n->code==NM_DBLCLK){const int index=ListView_GetNextItem(n->hwndFrom,-1,LVNI_SELECTED);if(index>=0 && static_cast<std::size_t>(index)<self->rows_.size()){std::wstring detail;for(const auto& c:self->rows_[index].cells)detail+=c+L"\r\n";SetWindowTextW(GetDlgItem(window,SUMMARY),detail.c_str());}return 0;}
        }
        if(message==WM_COMMAND){const int id=LOWORD(wparam),event=HIWORD(wparam);
            if((id==RANGE && event==CBN_SELCHANGE) || (id==SEARCH && event==EN_CHANGE)){self->refresh();return 0;}
            if(event==BN_CLICKED){
                if(id==APPLY)self->apply_learning();
                else if(id==COLLECT && self->on_settings)self->on_settings(self->data_.options,IsDlgButtonChecked(window,COLLECT)==BST_CHECKED);
                else if(id==LOG_FOLDER && self->on_log_folder)self->on_log_folder();
                else if(id==SYNC && self->on_sync)self->on_sync();
                else if((id==CSV || id==JSON) && self->on_export)self->on_export(id==CSV);
                else if(id==CLEAR && self->on_clear && MessageBoxW(window,L"Clear all known listening and learning records in these libraries? Clear markers synchronize with the statistics files. Previously unseen offline records may arrive later. Weights and logs are kept.",L"Clear statistics?",MB_YESNO|MB_ICONWARNING|MB_DEFBUTTON2)==IDYES)self->on_clear();
                self->refresh();if(self->on_feedback)self->on_feedback(L"Action applied.");return 0;
            }
        }
        if(message==WM_CLOSE){ShowWindow(window,SW_HIDE);return 0;}
        if(message==WM_DESTROY){KillTimer(window,1);if(self->font_)DeleteObject(self->font_);self->font_=nullptr;self->window_=nullptr;return 0;}
    }catch(const std::exception& e){if(self && self->on_feedback)self->on_feedback(wide(e.what()));return 0;}
    return DefWindowProcW(window,message,wparam,lparam);
}
