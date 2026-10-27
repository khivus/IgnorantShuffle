// Exercise the adapter against the real SDK's interfaces without running AIMP.
#include "../src/plugin.cpp"
#include <iostream>

namespace {
void check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
template<class Interface> class FakeUnknown : public Interface {
    GUID iid_;
    ULONG references_ = 1;
public:
    explicit FakeUnknown(GUID iid) : iid_(iid) {}
    HRESULT WINAPI QueryInterface(REFIID iid, void** object) override {
        if (!object) return E_POINTER;
        *object = nullptr;
        if (iid != IID_IUnknown && iid != iid_) return E_NOINTERFACE;
        *object = static_cast<Interface*>(this); AddRef(); return S_OK;
    }
    ULONG WINAPI AddRef() override { return ++references_; }
    ULONG WINAPI Release() override { return --references_; } // Fixtures live on the stack.
};
#define STUB(name, args) HRESULT WINAPI name args override { return E_NOTIMPL; }
class PlaylistProperties final : public FakeUnknown<IAIMPPropertyList> {
public:
    int cursor = -1;
    Ref<IAIMPString> name;
    PlaylistProperties() : FakeUnknown(IID_IAIMPPropertyList) {}
    void WINAPI BeginUpdate() override {}
    void WINAPI EndUpdate() override {}
    STUB(Reset, ())
    STUB(GetValueAsFloat, (int, double*))
    HRESULT WINAPI GetValueAsInt32(int property, int* value) override {
        if (property != AIMP_PLAYLIST_PROPID_PLAYBACKCURSOR) return E_NOTIMPL;
        *value = cursor; return S_OK;
    }
    STUB(GetValueAsInt64, (int, INT64*))
    HRESULT WINAPI GetValueAsObject(int property, REFIID iid, void** output) override {
        if (property == AIMP_PLAYLIST_PROPID_NAME && name) return name->QueryInterface(iid, output);
        *output = nullptr; return E_NOTIMPL;
    }
    STUB(SetValueAsFloat, (int, const double))
    STUB(SetValueAsInt32, (int, int))
    STUB(SetValueAsInt64, (int, const INT64))
    STUB(SetValueAsObject, (int, IUnknown*))
};
class EmptyPlaylist final : public FakeUnknown<IAIMPPlaylist> {
public:
    std::vector<IAIMPPlaylistItem*> items;
    PlaylistProperties properties;
    EmptyPlaylist() : FakeUnknown(IID_IAIMPPlaylist) {}
    HRESULT WINAPI QueryInterface(REFIID iid, void** output) override {
        if (iid == IID_IAIMPPropertyList) return properties.QueryInterface(iid, output);
        return FakeUnknown::QueryInterface(iid, output);
    }
    STUB(Add, (IUnknown*, LongWord, int))
    STUB(AddList, (IAIMPObjectList*, LongWord, int))
    STUB(Delete, (IAIMPPlaylistItem*))
    STUB(Delete2, (int))
    STUB(Delete3, (LongWord, TAIMPPlaylistDeleteProc, void*))
    STUB(DeleteAll, ())
    STUB(Sort, (int))
    STUB(Sort2, (IAIMPString*))
    STUB(Sort3, (TAIMPPlaylistCompareProc*, void*))
    STUB(BeginUpdate, ())
    STUB(EndUpdate, ())
    STUB(Close, (LongWord))
    STUB(GetFiles, (LongWord, IAIMPObjectList**))
    STUB(MergeGroup, (IAIMPPlaylistGroup*))
    STUB(ReloadFromPreimage, ())
    STUB(ReloadInfo, (LongWord))
    HRESULT WINAPI GetItem(int index, REFIID iid, void** output) override {
        if (index < 0 || static_cast<std::size_t>(index) >= items.size()) { *output = nullptr; return E_INVALIDARG; }
        return items[index]->QueryInterface(iid, output);
    }
    int WINAPI GetItemCount() override { return static_cast<int>(items.size()); }
    STUB(GetGroup, (int, REFIID, void**))
    int WINAPI GetGroupCount() override { return 0; }
    STUB(ListenerAdd, (IAIMPPlaylistListener*))
    STUB(ListenerRemove, (IAIMPPlaylistListener*))
};
class Manager final : public FakeUnknown<IAIMPServicePlaylistManager> {
public:
    IAIMPPlaylist* active = nullptr;
    std::vector<IAIMPPlaylist*> loaded;
    Manager() : FakeUnknown(IID_IAIMPServicePlaylistManager) {}
    STUB(CreatePlaylist, (IAIMPString*, BOOL, IAIMPPlaylist**))
    STUB(CreatePlaylistFromFile, (IAIMPString*, BOOL, IAIMPPlaylist**))
    HRESULT WINAPI GetActivePlaylist(IAIMPPlaylist** output) override {
        if (active) return active->QueryInterface(IID_IAIMPPlaylist, reinterpret_cast<void**>(output));
        *output = nullptr; return E_NOTIMPL;
    }
    STUB(SetActivePlaylist, (IAIMPPlaylist*))
    STUB(GetPlayingPlaylist, (IAIMPPlaylist**))
    HRESULT WINAPI GetLoadedPlaylist(int index, IAIMPPlaylist** output) override {
        if (index < 0 || static_cast<std::size_t>(index) >= loaded.size()) { *output = nullptr; return E_INVALIDARG; }
        return loaded[index]->QueryInterface(IID_IAIMPPlaylist, reinterpret_cast<void**>(output));
    }
    STUB(GetLoadedPlaylistByName, (IAIMPString*, IAIMPPlaylist**))
    int WINAPI GetLoadedPlaylistCount() override { return static_cast<int>(loaded.size()); }
    STUB(GetLoadedPlaylistByID, (IAIMPString*, IAIMPPlaylist**))
};
class Messages final : public FakeUnknown<IAIMPServiceMessageDispatcher> {
public:
    BOOL shuffle = FALSE;
    int end_action = 2;
    bool end_action_supported = true;
    HWND main_window = nullptr;
    Ref<IAIMPMessageHook> observer;
    Messages() : FakeUnknown(IID_IAIMPServiceMessageDispatcher) {}
    HRESULT WINAPI Send(LongWord message, int operation, void* value) override {
        if (message == AIMP_MSG_PROPERTY_HWND && operation == AIMP_MPH_MAINFORM) { *static_cast<HWND*>(value) = main_window; return S_OK; }
        if (message == AIMP_MSG_PROPERTY_ACTION_ON_END_OF_PLAYLIST && operation == AIMP_MSG_PROPVALUE_GET && end_action_supported) { *static_cast<int*>(value) = end_action; return S_OK; }
        if (message != AIMP_MSG_PROPERTY_SHUFFLE || operation != AIMP_MSG_PROPVALUE_GET) return E_NOTIMPL;
        *static_cast<BOOL*>(value) = shuffle; return S_OK;
    }
    LongWord WINAPI Register(TChar*) override { return 0; }
    HRESULT WINAPI Hook(IAIMPMessageHook* value) override { observer = query<IAIMPMessageHook>(value, IID_IAIMPMessageHook); return S_OK; }
    HRESULT WINAPI Unhook(IAIMPMessageHook*) override { observer = {}; return S_OK; }
};
class QueueOutput final : public FakeUnknown<IAIMPPlaybackQueueItem> {
public:
    Ref<IUnknown> item, custom;
    bool custom_as_iunknown_only = false;
    QueueOutput() : FakeUnknown(IID_IAIMPPlaybackQueueItem) {}
    void WINAPI BeginUpdate() override {}
    void WINAPI EndUpdate() override {}
    STUB(Reset, ())
    STUB(GetValueAsFloat, (int, double*))
    STUB(GetValueAsInt32, (int, int*))
    STUB(GetValueAsInt64, (int, INT64*))
    HRESULT WINAPI GetValueAsObject(int property, REFIID iid, void** output) override {
        if (property == AIMP_PLAYBACKQUEUEITEM_PROPID_CUSTOM && custom_as_iunknown_only && iid != IID_IUnknown) {
            *output = nullptr; return E_NOINTERFACE;
        }
        auto& value = property == AIMP_PLAYBACKQUEUEITEM_PROPID_CUSTOM ? custom : item;
        if (!value) { *output = nullptr; return E_NOINTERFACE; }
        return value->QueryInterface(iid, output);
    }
    STUB(SetValueAsFloat, (int, const double))
    STUB(SetValueAsInt32, (int, int))
    STUB(SetValueAsInt64, (int, const INT64))
    HRESULT WINAPI SetValueAsObject(int property, IUnknown* value) override {
        auto& destination = property == AIMP_PLAYBACKQUEUEITEM_PROPID_CUSTOM ? custom : item;
        destination = query<IUnknown>(value, IID_IUnknown); return S_OK;
    }
};
class Text final : public FakeUnknown<IAIMPString> {
    std::wstring value_;
public:
    explicit Text(std::wstring value) : FakeUnknown(IID_IAIMPString), value_(std::move(value)) {}
    TChar* WINAPI GetData() override { return value_.data(); }
    int WINAPI GetLength() override { return static_cast<int>(value_.size()); }
    int WINAPI GetHashCode() override { return 0; }
    STUB(GetChar, (int, TChar*))
    STUB(SetChar, (int, TChar))
    STUB(SetData, (TChar*, int))
    STUB(Add, (IAIMPString*))
    STUB(Add2, (TChar*, int))
    STUB(ChangeCase, (int))
    STUB(Clone, (IAIMPString**))
    STUB(Compare, (IAIMPString*, int*, BOOL))
    STUB(Compare2, (TChar*, int, int*, BOOL))
    STUB(Delete, (int, int))
    STUB(Find, (IAIMPString*, int*, int, int))
    STUB(Find2, (TChar*, int, int*, int, int))
    STUB(Insert, (int, IAIMPString*))
    STUB(Insert2, (int, TChar*, int))
    STUB(Replace, (IAIMPString*, IAIMPString*, int))
    STUB(Replace2, (TChar*, int, TChar*, int, int))
    STUB(SubString, (int, int, IAIMPString**))
};
class Track final : public FakeUnknown<IAIMPPlaylistItem> {
    Text filename_;
public:
    IAIMPPlaylist* owner = nullptr;
    int index = 0;
    int playback_index = -1;
    explicit Track(std::wstring filename) : FakeUnknown(IID_IAIMPPlaylistItem), filename_(std::move(filename)) {}
    void WINAPI BeginUpdate() override {}
    void WINAPI EndUpdate() override {}
    STUB(Reset, ())
    STUB(GetValueAsFloat, (int, double*))
    HRESULT WINAPI GetValueAsInt32(int property, int* value) override {
        if (property == AIMP_PLAYLISTITEM_PROPID_INDEX) *value = index;
        else if (property == AIMP_PLAYLISTITEM_PROPID_PLAYBACKQUEUEINDEX) *value = playback_index < 0 ? index : playback_index;
        else return E_NOTIMPL;
        return S_OK;
    }
    STUB(GetValueAsInt64, (int, INT64*))
    HRESULT WINAPI GetValueAsObject(int property, REFIID iid, void** output) override {
        if (property == AIMP_PLAYLISTITEM_PROPID_FILENAME || property == AIMP_PLAYLISTITEM_PROPID_DISPLAYTEXT)
            return filename_.QueryInterface(iid, output);
        if (property == AIMP_PLAYLISTITEM_PROPID_PLAYLIST && owner) return owner->QueryInterface(iid, output);
        *output = nullptr; return E_NOTIMPL;
    }
    STUB(SetValueAsFloat, (int, const double))
    STUB(SetValueAsInt32, (int, int))
    STUB(SetValueAsInt64, (int, const INT64))
    STUB(SetValueAsObject, (int, IUnknown*))
    STUB(ReloadInfo, ())
};
class PlaybackQueue final : public FakeUnknown<IAIMPServicePlaybackQueue2> {
public:
    int notifications = 0;
    PlaybackQueue() : FakeUnknown(IID_IAIMPServicePlaybackQueue2) {}
    STUB(GetNextTrack, (IAIMPPlaybackQueueItem**))
    STUB(GetPrevTrack, (IAIMPPlaybackQueueItem**))
    void WINAPI NotifyChanged(IAIMPExtensionPlaybackQueue*) override { ++notifications; }
};
class Player final : public FakeUnknown<IAIMPServicePlayer> {
public:
    IAIMPPlaylistItem* current = nullptr;
    int state = AIMP_PLAYER_STATE_PLAYING;
    Player() : FakeUnknown(IID_IAIMPServicePlayer) {}
    STUB(Play, (IAIMPPlaybackQueueItem*))
    STUB(Play2, (IAIMPPlaylistItem*))
    STUB(Play3, (IAIMPPlaylist*))
    STUB(Play4, (IAIMPString*, LongWord))
    STUB(GoToNext, ())
    STUB(GoToPrev, ())
    STUB(GetDuration, (double*))
    STUB(GetPosition, (double*))
    STUB(SetPosition, (const double))
    STUB(GetMute, (BOOL*))
    STUB(SetMute, (const BOOL))
    STUB(GetVolume, (float*))
    STUB(SetVolume, (const float))
    STUB(GetInfo, (IAIMPFileInfo**))
    HRESULT WINAPI GetPlaylistItem(IAIMPPlaylistItem** item) override {
        if (!current) { *item = nullptr; return E_FAIL; }
        return current->QueryInterface(IID_IAIMPPlaylistItem, reinterpret_cast<void**>(item));
    }
    int WINAPI GetState() override { return state; }
    STUB(Pause, ())
    STUB(Resume, ())
    STUB(Stop, ())
    STUB(StopAfterTrack, ())
};
class Core final : public FakeUnknown<IAIMPCore> {
    Manager& manager_;
    Messages& messages_;
    PlaybackQueue& queue_;
    Text profile_;
public:
    Ref<IUnknown> extension;
    Core(Manager& manager, Messages& messages, PlaybackQueue& queue, std::wstring profile)
        : FakeUnknown(IID_IAIMPCore), manager_(manager), messages_(messages), queue_(queue), profile_(std::move(profile)) {}
    HRESULT WINAPI QueryInterface(REFIID iid, void** object) override {
        if (iid == IID_IAIMPServicePlaylistManager) return manager_.QueryInterface(iid, object);
        if (iid == IID_IAIMPServiceMessageDispatcher) return messages_.QueryInterface(iid, object);
        if (iid == IID_IAIMPServicePlaybackQueue2) return queue_.QueryInterface(iid, object);
        return FakeUnknown::QueryInterface(iid, object);
    }
    STUB(CreateObject, (REFIID, void**))
    HRESULT WINAPI GetPath(int path, IAIMPString** output) override {
        if (path != AIMP_CORE_PATH_PROFILE) return E_INVALIDARG;
        return profile_.QueryInterface(IID_IAIMPString, reinterpret_cast<void**>(output));
    }
    HRESULT WINAPI RegisterExtension(REFIID iid, IUnknown* value) override {
        if (iid != IID_IAIMPServicePlaybackQueue) return E_INVALIDARG;
        extension = query<IUnknown>(value, IID_IUnknown); return S_OK;
    }
    STUB(RegisterService, (IUnknown*))
    HRESULT WINAPI UnregisterExtension(IUnknown*) override { extension = {}; return S_OK; }
};
#undef STUB
struct PluginChecks {
    static void default_weight_checks(Manager& manager, Messages& messages) {
        Track first(L"default-first.flac"), second(L"default-second.flac");
        EmptyPlaylist playlist; playlist.items = {&first, &second};
        first.owner = second.owner = &playlist; second.index = 1; manager.active = &playlist;
        Player player; player.current = &first;
        PlaybackQueue queue;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.player_ = query<IAIMPServicePlayer>(&player, IID_IAIMPServicePlayer);
        plugin.queue_ = query<IAIMPServicePlaybackQueue2>(&queue, IID_IAIMPServicePlaybackQueue2);
        plugin.preferences_file_ = std::filesystem::current_path() / L"default-weight-prefs.ini";
        std::filesystem::remove(plugin.preferences_file_);
        plugin.weights_.set(track_key(&second), 5);
        module = GetModuleHandleW(nullptr); plugin.open_monitor();
        const auto editor = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_SETTINGS), nullptr, Plugin::dialog_proc, reinterpret_cast<LPARAM>(&plugin));
        const auto settings = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_STORAGE), editor, Plugin::storage_proc, reinterpret_cast<LPARAM>(&plugin));
        check(Plugin::control_text(settings, IDC_DEFAULT_WEIGHT) == L"5", "Default-weight settings start at five.");
        ListView_SetItemState(GetDlgItem(editor, IDC_TRACKS), 0, LVIS_SELECTED, LVIS_SELECTED);
        const auto apply = [&](const wchar_t* value) {
            SetDlgItemTextW(settings, IDC_DEFAULT_WEIGHT, value);
            SendMessageW(settings, WM_COMMAND, MAKEWPARAM(IDC_APPLY_DEFAULT_WEIGHT, BN_CLICKED), 0);
        };
        QueueOutput next;
        plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &next);
        const auto before = queue.notifications;
        apply(L"2.5");
        check(plugin.weights_.get(track_key(&first)) == 2.5 && plugin.weights_.get(track_key(&second)) == 5 && plugin.weights_.entries().size() == 1,
            "The default changes immediately without overwriting or materializing explicit track entries.");
        check(queue.notifications == before + 1 && plugin.monitor_state().current_weight.find(L"2.5") != std::wstring::npos,
            "Changing the default invalidates prefetched choices once and refreshes current playback weight.");
        check(plugin.track_table_.rows().front().weight == 2.5 && plugin.selected_tracks(editor) == std::vector<std::string>{track_key(&first)},
            "Changing the default refreshes table values and preserves selection.");
        Plugin restored; restored.preferences_file_ = plugin.preferences_file_; restored.load_preferences();
        check(restored.weights_.get("unsaved") == 2.5, "Configured default survives restart.");
        apply(L"25.123456789012344");
        restored.load_preferences();
        check(restored.weights_.fallback() == plugin.weights_.fallback() && plugin.monitor_state().selected_weight == -1,
            "Fractional defaults above ten round-trip without precision loss or a misleading quick-button highlight.");
        apply(L"0");
        plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &next);
        check(same_object(next.item.get(), &second) && plugin.monitor_state().selected_weight == 0,
            "A zero default excludes unsaved tracks from the real weighted queue and updates the panel.");
        const auto preserved_notifications = queue.notifications;
        for (const auto* bad : {L"", L"-1", L"NaN", L"inf", L"1e999", L"2junk"}) {
            SetDlgItemTextW(settings, IDC_DEFAULT_WEIGHT, bad);
            bool rejected = false;
            try { plugin.apply_default_weight(settings); } catch (const std::exception&) { rejected = true; }
            check(rejected && plugin.weights_.fallback() == 0 && queue.notifications == preserved_notifications,
                "Invalid default input leaves the setting and prefetched queue unchanged.");
        }
        restored.load_preferences(); check(restored.weights_.fallback() == 0, "Invalid default input does not alter saved preferences.");
        WritePrivateProfileStringW(L"Shuffle", L"DefaultWeight", L"NaN", plugin.preferences_file_.c_str());
        restored.load_preferences();
        check(restored.weights_.fallback() == 5 && !restored.preferences_error_.empty(), "Invalid INI defaults safely fall back to five with a diagnostic.");
        SetDlgItemTextW(settings, IDC_DEFAULT_WEIGHT, L"0");
        ShowWindow(settings, SW_SHOWNOACTIVATE); UpdateWindow(settings); capture_preview(settings, L"default-weight-settings.bmp");
        DestroyWindow(settings); DestroyWindow(editor); plugin.Finalize(); manager.active = nullptr;
        std::filesystem::remove(plugin.preferences_file_);
    }
    static void mouse_input_checks() {
        module = GetModuleHandleW(nullptr);
        MonitorWindow panel;
        check(panel.create(module), "Create input regression panel.");
        MonitorState state; state.can_set_weight = true; state.current_key = L"first"; state.selected_weight = 5;
        int clicks = 0; panel.on_weight = [&](int) { ++clicks; };
        panel.update(state); panel.restore_size(600, 900); panel.show(true);
        const auto button = GetDlgItem(panel.handle(), IDC_QUICK_WEIGHT_3);
        RECT bounds{}; GetWindowRect(button, &bounds);
        const auto point = MAKELPARAM((bounds.right - bounds.left) / 2, (bounds.bottom - bounds.top) / 2);
        const auto down = [&] { SendMessageW(button, WM_LBUTTONDOWN, MK_LBUTTON, point); };
        const auto up = [&] { SendMessageW(button, WM_LBUTTONUP, 0, point); };
        check(WindowFromPoint({(bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2}) == button, "Input fixture button is exposed.");
        down(); up();
        check(clicks == 1, "An exposed mouse press and release activates once.");
        const auto cover = CreateWindowExW(WS_EX_TOPMOST | WS_EX_NOACTIVATE, L"STATIC", L"Cover", WS_POPUP | WS_VISIBLE,
            bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top, nullptr, nullptr, module, nullptr);
        check(cover != nullptr, "Create an overlapping window.");
        down(); up();
        check(clicks == 1, "Mouse messages to a button behind another window are ignored.");
        DestroyWindow(cover);
        down();
        const auto late_cover = CreateWindowExW(WS_EX_TOPMOST | WS_EX_NOACTIVATE, L"STATIC", L"Cover", WS_POPUP | WS_VISIBLE,
            bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top, nullptr, nullptr, module, nullptr);
        up(); DestroyWindow(late_cover);
        check(clicks == 1 && GetCapture() != button, "A window covering a held button cancels the pending click and capture.");
        down(); state.current_key = L"second"; panel.update(state); up();
        check(clicks == 1, "A press started on the old track cannot assign a weight to the new track.");
        down(); SendMessageW(panel.handle(), WM_ACTIVATE, WA_INACTIVE, 0); up();
        check(clicks == 1 && GetCapture() != button, "Deactivation cancels a pending button press.");
        SendMessageW(button, WM_KEYDOWN, VK_SPACE, 0); SendMessageW(button, WM_KEYUP, VK_SPACE, 0);
        check(clicks == 2, "Intentional keyboard Space activation remains supported.");
        SendMessageW(button, BM_CLICK, 0, 0);
        check(clicks == 3, "Native accessibility button activation remains supported.");
        panel.destroy();
    }
    static void sorting_checks(Manager& manager) {
        Track first(L"Z:\\Music\\Zulu.flac"), second(L"A:\\Music\\alpha.flac"), third(L"M:\\Music\\Beta.flac");
        EmptyPlaylist playlist; playlist.items = {&first, &second, &third, &second};
        manager.active = &playlist;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.weights_file_ = std::filesystem::current_path() / L"sort-weights.tsv";
        plugin.weights_.set(track_key(&first), 25); plugin.weights_.set(track_key(&second), 2.5); plugin.weights_.set(track_key(&third), 0);
        module = GetModuleHandleW(nullptr);
        const auto editor = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_SETTINGS), nullptr, Plugin::dialog_proc, reinterpret_cast<LPARAM>(&plugin));
        const auto table = GetDlgItem(editor, IDC_TRACKS);
        const auto sort = [&](int column) {
            NMLISTVIEW click{}; click.hdr = {table, IDC_TRACKS, LVN_COLUMNCLICK}; click.iSubItem = column;
            SendMessageW(editor, WM_NOTIFY, IDC_TRACKS, reinterpret_cast<LPARAM>(&click));
        };
        ListView_SetItemState(table, 0, LVIS_SELECTED, LVIS_SELECTED);
        ListView_SetItemState(table, 3, LVIS_SELECTED | LVIS_FOCUSED, LVIS_SELECTED | LVIS_FOCUSED);
        ListView_SetSelectionMark(table, 3);
        sort(0);
        check(plugin.visible_tracks_ == std::vector<std::string>({track_key(&third), track_key(&second), track_key(&second), track_key(&first)}), "Weight sorting is numeric, including zero and fractional values.");
        check(plugin.selected_rows(editor) == std::vector<int>({2, 3}) && ListView_GetNextItem(table, -1, LVNI_FOCUSED) == 2 && ListView_GetSelectionMark(table) == 2,
            "Sorting preserves selected identities, duplicate occurrences, caret and Shift anchor.");
        HDITEMW header{}; header.mask = HDI_FORMAT; Header_GetItem(ListView_GetHeader(table), 0, &header);
        check((header.fmt & HDF_SORTUP) != 0, "The active column shows an ascending arrow.");
        sort(0);
        check(plugin.visible_tracks_.front() == track_key(&first) && plugin.visible_tracks_.back() == track_key(&third), "Clicking the same header reverses sorting.");
        plugin.apply_editor_weight(editor, 10);
        check(plugin.weights_.get(track_key(&first)) == 10 && plugin.weights_.get(track_key(&second)) == 10 && plugin.weights_.get(track_key(&third)) == 0,
            "Bulk edits after sorting target the selected filenames and preserve the sort.");
        sort(1);
        check(plugin.visible_tracks_.front() == track_key(&second) && plugin.visible_tracks_.back() == track_key(&first), "Track titles sort without case sensitivity.");
        sort(4);
        check(plugin.visible_tracks_.front() == track_key(&second) && plugin.visible_tracks_.back() == track_key(&first), "Folder column sorts by folder path.");
        // Exercise Source and the numeric editing column with distinct layer values.
        auto rows = plugin.track_table_.rows();
        rows[0].source = L"Playlist"; rows[1].source = L"Global"; rows[2].source = L"Folder"; rows[3].source = L"Global";
        rows[0].editing_weight = 25; rows[1].editing_weight = 2.5; rows[2].editing_weight = 0; rows[3].editing_weight = 10;
        plugin.track_table_.replace(rows, {}, -1, -1, 0, true, L"Edit Global");
        plugin.track_table_.sort_by(2); plugin.track_table_.sort(rows);
        check(rows.front().source == L"Folder" && rows.back().source == L"Playlist", "Source column supports text sorting.");
        plugin.track_table_.sort_by(3); plugin.track_table_.sort(rows);
        check(rows.front().editing_weight == 0 && rows[1].editing_weight == 2.5 && rows.back().editing_weight == 25, "Edit values sort numerically independently of playback weights.");
        DestroyWindow(editor); plugin.Finalize(); manager.active = nullptr;
        std::filesystem::remove(plugin.weights_file_);
    }
    static void bulk_editor_checks(Manager& manager, Messages& messages) {
        namespace fs = std::filesystem;
        const auto fixture = fs::current_path() / (L"adapter-bulk-" + std::to_wstring(GetCurrentProcessId()));
        check(!fs::exists(fixture), "Bulk fixture must be new."); fs::create_directory(fixture);
        Track first(L"bulk-first.flac"), second(L"bulk-second.flac"), third(L"bulk-third.flac"), fourth(L"bulk-fourth.flac");
        EmptyPlaylist playlist; playlist.items = {&first, &second, &third, &fourth};
        first.owner = second.owner = third.owner = fourth.owner = &playlist;
        second.index = 1; third.index = 2; fourth.index = 3;
        manager.active = &playlist;
        PlaybackQueue queue;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.queue_ = query<IAIMPServicePlaybackQueue2>(&queue, IID_IAIMPServicePlaybackQueue2);
        plugin.weights_file_ = fixture / L"profile.tsv";
        plugin.weights_.set("bulk-first.flac", 1); plugin.weights_.set("bulk-second.flac", 2);
        plugin.weights_.set("bulk-third.flac", 3); plugin.weights_.set("bulk-fourth.flac", 4);
        plugin.weights_.save(plugin.weights_file_);
        module = GetModuleHandleW(nullptr);
        const auto editor = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_SETTINGS), nullptr, Plugin::dialog_proc, reinterpret_cast<LPARAM>(&plugin));
        const auto table = GetDlgItem(editor, IDC_TRACKS);
        check(editor && (GetWindowLongPtrW(table, GWL_STYLE) & LVS_TYPEMASK) == LVS_REPORT && !(GetWindowLongPtrW(table, GWL_STYLE) & LVS_SINGLESEL), "Track table supports Ctrl/Shift multi-selection.");
        auto cell = [&](int row, int column) { wchar_t text[1024]{}; ListView_GetItemText(table, row, column, text, 1024); return std::wstring(text); };
        check(cell(0, 0) == L"1" && cell(0, 1) == L"bulk-first.flac" && cell(0, 2) == L"Global", "Numeric weight, track name and source occupy distinct readable columns.");
        check(ListView_GetColumnWidth(table, 3) == 0, "The redundant edit-value column is hidden in profile-only mode.");
        check(!GetDlgItem(editor, IDC_IMPORT) && !GetDlgItem(editor, IDC_EXPORT), "Import/export no longer occupy track-view controls.");
        check(!IsWindowEnabled(GetDlgItem(editor, IDC_APPLY_WEIGHT)), "Applying a weight requires a selection.");
        auto select = [&](std::initializer_list<int> rows) {
            ListView_SetItemState(table, -1, 0, LVIS_SELECTED);
            for (const auto row : rows) ListView_SetItemState(table, row, LVIS_SELECTED, LVIS_SELECTED);
            SendMessageW(editor, WM_APP + 3, 0, 0);
        };
        select({0, 2});
        ListView_SetItemState(table, 2, LVIS_FOCUSED, LVIS_FOCUSED);
        ListView_SetSelectionMark(table, 2);
        check(Plugin::control_text(editor, IDC_STATUS).find(L"mixed") != std::wstring::npos, "Mixed selected weights are displayed honestly.");
        RECT previous{};
        for (int value = 0; value <= 10; ++value) {
            const auto button = GetDlgItem(editor, IDC_EDITOR_WEIGHT_0 + value);
            RECT bounds{}; GetWindowRect(button, &bounds);
            check(button && IsWindowEnabled(button), "All eleven quick bulk buttons are available after selection.");
            if (value) check(bounds.top == previous.top && bounds.left >= previous.right, "Bulk weight buttons form one nonoverlapping row.");
            previous = bounds;
        }
        const auto before = queue.notifications;
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_EDITOR_WEIGHT_0 + 6, BN_CLICKED), 0);
        check(plugin.weights_.get("bulk-first.flac") == 6 && plugin.weights_.get("bulk-third.flac") == 6 && plugin.weights_.get("bulk-second.flac") == 2 && plugin.weights_.get("bulk-fourth.flac") == 4,
            "A quick button assigns only the selected disjoint rows.");
        check(queue.notifications == before + 1 && plugin.selected_rows(editor) == std::vector<int>({0, 2}),
            "A batch refreshes the queue once and preserves the selected rows.");
        check(ListView_GetNextItem(table, -1, LVNI_FOCUSED) == 2 && ListView_GetSelectionMark(table) == 2,
            "Bulk edits preserve the keyboard caret and Shift-selection anchor.");
        SetDlgItemTextW(editor, IDC_WEIGHT, L"25.5");
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_APPLY_WEIGHT, BN_CLICKED), 0);
        ignorant::Weights saved; saved.load(plugin.weights_file_);
        check(saved.get("bulk-first.flac") == 25.5 && saved.get("bulk-third.flac") == 25.5 && saved.get("bulk-second.flac") == 2,
            "Custom fractional weights above ten are saved for every selected row.");
        check(cell(0, 0) == L"25.5" && cell(2, 0) == L"25.5" && cell(1, 0) == L"2", "Fractional and imported weights update directly in their table cells.");
        SetFocus(GetDlgItem(editor, IDC_WEIGHT)); SetDlgItemTextW(editor, IDC_WEIGHT, L"2.5");
        SendMessageW(editor, WM_COMMAND, IDOK, 0);
        check(IsWindow(editor) && plugin.weights_.get("bulk-first.flac") == 2.5 && plugin.weights_.get("bulk-third.flac") == 2.5,
            "Enter in the custom field applies its value without closing the track view.");
        for (const auto* bad : {L"", L"-1", L"NaN", L"inf", L"1e999", L"25junk"}) {
            const auto original = plugin.weights_.entries();
            bool rejected = false;
            try { plugin.apply_editor_weight(editor, Plugin::parse_custom_weight(bad)); } catch (const std::exception&) { rejected = true; }
            check(rejected && plugin.weights_.entries() == original && plugin.selected_rows(editor) == std::vector<int>({0, 2}),
                "Invalid custom values leave saved weights and selection unchanged.");
        }
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_SELECT_ALL, BN_CLICKED), 0);
        check(plugin.selected_rows(editor).size() == 4, "Select all includes the entire list.");
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_EDITOR_WEIGHT_0, BN_CLICKED), 0);
        QueueOutput next;
        check(plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next) == TRUE && !next.item,
            "Bulk zero exclusions reach the actual queue and stop an all-zero list.");
        select({});
        check(cell(0, 0).find(L"Excluded") != std::wstring::npos, "Weight zero has an explicit exclusion label in each row.");
        wchar_t tip_text[1024]{};
        NMLVGETINFOTIPW tip{}; tip.hdr.hwndFrom = table; tip.hdr.idFrom = IDC_TRACKS; tip.hdr.code = LVN_GETINFOTIPW;
        tip.iItem = 0; tip.pszText = tip_text; tip.cchTextMax = 1024;
        SendMessageW(editor, WM_NOTIFY, IDC_TRACKS, reinterpret_cast<LPARAM>(&tip));
        check(std::wstring(tip_text).find(L"Playback weight: 0 (excluded)") != std::wstring::npos && std::wstring(tip_text).find(L"bulk-first.flac") != std::wstring::npos,
            "Weight tooltips expose the playback value, exclusion and full track identity.");
        ShowWindow(editor, SW_SHOWNOACTIVATE); UpdateWindow(editor); capture_preview(editor, L"track-weights-excluded.bmp");
        check(!IsWindowEnabled(GetDlgItem(editor, IDC_EDITOR_WEIGHT_10)) && !IsWindowEnabled(GetDlgItem(editor, IDC_APPLY_WEIGHT)),
            "Clearing selection disables bulk actions.");
        bool empty_rejected = false;
        try { plugin.apply_editor_weight(editor, 8); } catch (const std::exception&) { empty_rejected = true; }
        check(empty_rejected && plugin.weights_.get("bulk-first.flac") == 0, "No-selection calls cannot change a focused but unselected row.");
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_SELECT_ALL, BN_CLICKED), 0);
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_EDITOR_WEIGHT_10, BN_CLICKED), 0);
        SendDlgItemMessageW(editor, IDC_WEIGHT_SCOPE, CB_SETCURSEL, 0, 0);
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_WEIGHT_SCOPE, CBN_SELCHANGE), 0);
        check(plugin.selected_rows(editor).size() == 4, "Scope refresh preserves the whole multi-selection.");
        const auto settings = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_STORAGE), editor, Plugin::storage_proc, reinterpret_cast<LPARAM>(&plugin));
        check(settings && GetDlgItem(settings, IDC_IMPORT) && GetDlgItem(settings, IDC_EXPORT) && SendDlgItemMessageW(settings, IDC_WEIGHT_SCOPE, CB_GETCOUNT, 0, 0) == 1,
            "Weight storage exposes Import/Export with an explicit available scope selector.");
        const auto view = plugin.storage_view();
        const auto exported = fixture / L"export.tsv";
        plugin.transfer_file(exported, true, ignorant::WeightScope::Global, view);
        ignorant::Weights exported_weights; exported_weights.load(exported);
        check(exported_weights.entries().size() == 4 && exported_weights.get("bulk-third.flac") == 10, "Storage export includes the applied scope and current list.");
        { std::ofstream input(fixture / L"import.tsv"); input << "30.5\tbulk-first.flac\n0\tbulk-third.flac\n"; }
        plugin.transfer_file(fixture / L"import.tsv", false, ignorant::WeightScope::Global, view);
        check(plugin.weights_.get("bulk-first.flac") == 30.5 && plugin.weights_.get("bulk-third.flac") == 0 && plugin.selected_rows(editor).size() == 4,
            "Storage import supports large/fractional/zero values and retains the editor's selected entries.");
        ShowWindow(editor, SW_SHOWNOACTIVATE); UpdateWindow(editor); capture_preview(editor, L"bulk-track-editor.bmp");
        ShowWindow(settings, SW_SHOWNOACTIVATE); UpdateWindow(settings); capture_preview(settings, L"bulk-weight-storage.bmp");
        DestroyWindow(settings); DestroyWindow(editor); plugin.Finalize(); manager.active = nullptr;
        fs::remove_all(fixture);
    }
    static void storage_checks(Manager& manager, Messages& messages) {
        namespace fs = std::filesystem;
        using ignorant::WeightScope;
        const auto fixture = fs::current_path() / (L"adapter-storage-" + std::to_wstring(GetCurrentProcessId()));
        check(!fs::exists(fixture), "Storage fixture must be new.");
        const auto music = fixture / L"Music", folder = music / L"Favorites", other = music / L"Other";
        fs::create_directories(folder); fs::create_directories(other);
        Track first((folder / L"first.flac").wstring()), second((folder / L"second.flac").wstring());
        Track broad_first((folder / L"first.flac").wstring()), broad_second((other / L"other.flac").wstring());
        EmptyPlaylist narrow, broad;
        narrow.items = {&first, &second}; broad.items = {&broad_first, &broad_second};
        first.owner = second.owner = &narrow; second.index = 1;
        broad_first.owner = broad_second.owner = &broad; broad_second.index = 1;
        Text narrow_name(L"Default"), broad_name(L"All music");
        narrow.properties.name = query<IAIMPString>(&narrow_name, IID_IAIMPString);
        broad.properties.name = query<IAIMPString>(&broad_name, IID_IAIMPString);
        manager.active = &narrow;
        Player player; player.current = &first;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.player_ = query<IAIMPServicePlayer>(&player, IID_IAIMPServicePlayer);
        plugin.weights_file_ = fixture / L"profile.tsv";
        plugin.preferences_file_ = fixture / L"preferences.ini";
        plugin.weights_.set(track_key(&first), 8); plugin.weights_.save(plugin.weights_file_);
        module = GetModuleHandleW(nullptr);
        QueueOutput selected, next;
        HRESULT event = E_NOTIMPL;
        auto play = [&](IAIMPPlaylistItem* item) {
            QueueOutput manual; plugin.OnSelect(item, &manual);
            plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &event); player.current = item; plugin.update_monitor();
        };
        play(&first); plugin.open_monitor();
        const auto panel = plugin.monitor_.handle();
        check(!plugin.shared_global_ && !plugin.folder_local_ && !plugin.playlist_local_ && !IsWindowVisible(GetDlgItem(panel, IDC_WEIGHT_SCOPE)),
            "Portable options are off by default and preserve the compact legacy panel.");
        const auto settings = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_STORAGE), panel, Plugin::storage_proc, reinterpret_cast<LPARAM>(&plugin));
        check(settings != nullptr, "Storage settings dialog creates.");
        SetDlgItemTextW(settings, IDC_MUSIC_FOLDER, music.c_str());
        CheckDlgButton(settings, IDC_FOLDER_LOCAL, BST_CHECKED);
        SendMessageW(settings, WM_COMMAND, MAKEWPARAM(IDC_APPLY_STORAGE, BN_CLICKED), 0);
        check(plugin.folder_local_ && !plugin.shared_global_ && plugin.storage_error_.empty(), "Folder overrides can be enabled independently of shared global storage.");
        auto choose_scope = [&](int value) {
            const auto count = SendDlgItemMessageW(panel, IDC_WEIGHT_SCOPE, CB_GETCOUNT, 0, 0);
            for (LRESULT i = 0; i < count; ++i) if (SendDlgItemMessageW(panel, IDC_WEIGHT_SCOPE, CB_GETITEMDATA, i, 0) == value) {
                SendDlgItemMessageW(panel, IDC_WEIGHT_SCOPE, CB_SETCURSEL, i, 0);
                SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_WEIGHT_SCOPE, CBN_SELCHANGE), 0); return;
            }
            throw std::runtime_error("Expected weight scope is missing from the panel.");
        };
        choose_scope(1);
        SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_QUICK_WEIGHT_0, BN_CLICKED), 0);
        const auto context = plugin.context_for(query<IAIMPPlaylist>(&narrow, IID_IAIMPPlaylist));
        check(plugin.weights_.get(track_key(&first)) == 8 && plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 0,
            "Quick local edits keep the old profile/global value separate.");
        check(IsWindowEnabled(GetDlgItem(panel, IDC_RESET_LOCAL)), "An explicit local override offers Inherit.");
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        check(same_object(next.item.get(), &second), "The actual shuffle excludes a local weight-zero track.");
        CheckDlgButton(settings, IDC_SHARED_GLOBAL, BST_CHECKED);
        SendMessageW(settings, WM_COMMAND, MAKEWPARAM(IDC_SHARED_GLOBAL, BN_CLICKED), 0);
        check(IsWindowEnabled(GetDlgItem(settings, IDC_COPY_PROFILE)), "Shared global storage enables the profile migration action.");
        SendMessageW(settings, WM_COMMAND, MAKEWPARAM(IDC_COPY_PROFILE, BN_CLICKED), 0);
        ignorant::Weights portable; portable.load(music / L"IgnorantShuffle.weights.tsv");
        check(portable.get("Favorites/first.flac") == 8, "Copy existing profile weights creates portable relative keys.");
        choose_scope(0);
        SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_QUICK_WEIGHT_7, BN_CLICKED), 0);
        check(plugin.monitor_state().selected_weight == 7 && plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 0,
            "Editing global updates its selector value without bypassing an existing local exclusion.");
        check(plugin.monitor_state().current_weight.find(L"Current weight: 0") != std::wstring::npos && plugin.monitor_state().current_weight.find(L"Editing Global: 7") != std::wstring::npos,
            "The panel distinguishes the effective playback weight from the selected editing scope.");
        play(&broad_first); manager.active = &broad;
        const auto broad_context = plugin.context_for(query<IAIMPPlaylist>(&broad, IID_IAIMPPlaylist));
        check(plugin.storage_.resolve(plugin.weights_, track_key(&broad_first), broad_context).weight == 7,
            "The same file outside its folder's list uses its shared global weight.");
        play(&first); manager.active = &narrow;
        CheckDlgButton(settings, IDC_PLAYLIST_LOCAL, BST_CHECKED);
        SendMessageW(settings, WM_COMMAND, MAKEWPARAM(IDC_APPLY_STORAGE, BN_CLICKED), 0);
        choose_scope(2);
        SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_QUICK_WEIGHT_9, BN_CLICKED), 0);
        check(plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 9, "AIMP-playlist-specific edits override folder/global weights.");
        SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_RESET_LOCAL, BN_CLICKED), 0);
        check(plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 0, "Inherit removes a playlist override and restores the folder value.");
        choose_scope(1);
        SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_RESET_LOCAL, BN_CLICKED), 0);
        check(plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 7, "Inherit removes a folder override and restores the global value.");
        Plugin restored; restored.preferences_file_ = plugin.preferences_file_; restored.load_preferences();
        check(restored.shared_global_ && restored.folder_local_ && restored.playlist_local_ && restored.music_folder_ == music && restored.edit_scope_ == WeightScope::Folder,
            "Storage choices, root and editing scope survive restart.");
        // Use the actual editor controls too, without invoking its modal loop.
        const auto editor = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_SETTINGS), panel, Plugin::dialog_proc, reinterpret_cast<LPARAM>(&plugin));
        check(editor && ListView_GetItemCount(GetDlgItem(editor, IDC_TRACKS)) == 2, "Editor lists the active folder's tracks.");
        const auto table = GetDlgItem(editor, IDC_TRACKS);
        ListView_SetItemState(table, 0, LVIS_SELECTED, LVIS_SELECTED);
        SendMessageW(editor, WM_APP + 3, 0, 0);
        SetDlgItemTextW(editor, IDC_WEIGHT, L"4");
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_APPLY_WEIGHT, BN_CLICKED), 0);
        check(plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 4 && plugin.weights_.get(track_key(&first)) == 8,
            "The playlist editor honors the same selected local scope as quick controls.");
        wchar_t name_text[1024]{}; ListView_GetItemText(table, 0, 1, name_text, 1024);
        check(std::wstring(name_text) == L"first.flac" && ListView_GetColumnWidth(table, 3) > 0,
            "Local-weight views show filenames without long path prefixes and expose a separate edit column.");
        SendDlgItemMessageW(editor, IDC_WEIGHT_SCOPE, CB_SETCURSEL, 0, 0);
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_WEIGHT_SCOPE, CBN_SELCHANGE), 0);
        wchar_t used_text[64]{}, edit_text[64]{}, source_text[64]{};
        ListView_GetItemText(table, 0, 0, used_text, 64); ListView_GetItemText(table, 0, 3, edit_text, 64); ListView_GetItemText(table, 0, 2, source_text, 64);
        check(std::wstring(used_text) == L"4" && std::wstring(edit_text) == L"7" && std::wstring(source_text) == L"Folder",
            "The table distinguishes the actual folder weight from the global value being edited.");
        ShowWindow(editor, SW_SHOWNOACTIVATE); UpdateWindow(editor); capture_preview(editor, L"track-weights-scopes.bmp");
        SendDlgItemMessageW(editor, IDC_WEIGHT_SCOPE, CB_SETCURSEL, 1, 0);
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_WEIGHT_SCOPE, CBN_SELCHANGE), 0);
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_SELECT_ALL, BN_CLICKED), 0);
        SetDlgItemTextW(editor, IDC_WEIGHT, L"25.5");
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_APPLY_WEIGHT, BN_CLICKED), 0);
        check(plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 25.5 && plugin.storage_.resolve(plugin.weights_, track_key(&second), context).weight == 25.5 && plugin.selected_rows(editor).size() == 2,
            "Bulk custom edits update every folder-local entry and preserve multi-selection.");
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_RESET_LOCAL, BN_CLICKED), 0);
        check(plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 7 && plugin.storage_.resolve(plugin.weights_, track_key(&second), context).weight == 5,
            "Bulk Inherit restores the separate global values for every selected track.");
        SendDlgItemMessageW(editor, IDC_WEIGHT_SCOPE, CB_SETCURSEL, 2, 0);
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_WEIGHT_SCOPE, CBN_SELCHANGE), 0);
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_EDITOR_WEIGHT_0, BN_CLICKED), 0);
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        check(!next.item && plugin.weights_.get(track_key(&first)) == 8,
            "Playlist-scoped bulk zero changes playback without touching the profile fallback.");
        SendMessageW(editor, WM_COMMAND, MAKEWPARAM(IDC_RESET_LOCAL, BN_CLICKED), 0);
        SendDlgItemMessageW(settings, IDC_WEIGHT_SCOPE, CB_SETCURSEL, 1, 0);
        SendMessageW(settings, WM_COMMAND, MAKEWPARAM(IDC_WEIGHT_SCOPE, CBN_SELCHANGE), 0);
        const auto transfer = plugin.storage_view();
        plugin.transfer_file(fixture / L"folder-export.tsv", true, WeightScope::Folder, transfer);
        ignorant::Weights folder_export; folder_export.load(fixture / L"folder-export.tsv");
        check(folder_export.get("first.flac") == 7 && folder_export.get("second.flac") == 5,
            "Storage export for a folder uses relative keys and the chosen layer.");
        { std::ofstream input(fixture / L"folder-import.tsv"); input << "2.5\tfirst.flac\n25\tsecond.flac\n"; }
        plugin.transfer_file(fixture / L"folder-import.tsv", false, WeightScope::Folder, transfer);
        check(plugin.storage_.resolve(plugin.weights_, track_key(&first), context).weight == 2.5 && plugin.storage_.resolve(plugin.weights_, track_key(&second), context).weight == 25 && plugin.selected_rows(editor).size() == 2 && plugin.weights_.get(track_key(&first)) == 8,
            "Storage import updates only its selected shared layer and preserves bulk selection.");
        capture_preview(panel, L"monitor-shared-weights.bmp");
        ShowWindow(settings, SW_SHOWNOACTIVATE); UpdateWindow(settings);
        capture_preview(settings, L"weight-storage-settings.bmp");
        ShowWindow(editor, SW_SHOWNOACTIVATE); UpdateWindow(editor);
        capture_preview(editor, L"weight-storage-editor.bmp");
        DestroyWindow(editor); DestroyWindow(settings);
        { std::ofstream corrupt(folder / L"IgnorantShuffle.folder.weights.tsv"); corrupt << "invalid weights\n"; }
        bool rejected = false;
        try { plugin.set_storage(music, true, true, true); } catch (const std::exception&) { rejected = true; }
        check(rejected && !plugin.storage_error_.empty(), "Malformed shared weights produce a visible storage error.");
        check(plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next) == TRUE && !next.item,
            "Malformed shared weights stop weighted selection instead of silently bypassing exclusions.");
        check(plugin.monitor_state().next_track.find(L"check Weight storage") != std::wstring::npos, "Panel gives a recovery instruction for a malformed shared file.");
        plugin.set_storage({}, false, false, false);
        check(plugin.storage_error_.empty() && plugin.effective_weight(track_key(&first), {}).weight == 8,
            "Disabling shared options restores the untouched original profile behavior.");
        plugin.Finalize(); manager.active = nullptr;
        fs::remove_all(fixture);
    }
    static void capture_preview(HWND window, const wchar_t* name) {
        wchar_t requested[2]{};
        if (!GetEnvironmentVariableW(L"IGNORANT_SHUFFLE_CAPTURE", requested, 2)) return;
        // Render only this test fixture's own window, never the user's desktop.
        RECT bounds{}; GetWindowRect(window, &bounds);
        const int width = bounds.right - bounds.left, height = bounds.bottom - bounds.top;
        const auto dc = GetDC(window), memory = CreateCompatibleDC(dc);
        const auto bitmap = CreateCompatibleBitmap(dc, width, height);
        const auto old = SelectObject(memory, bitmap);
        check(PrintWindow(window, memory, 0) != FALSE, "Fixture window preview must render.");
        SelectObject(memory, old);
        BITMAPINFO info{};
        info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        info.bmiHeader.biWidth = width; info.bmiHeader.biHeight = -height;
        info.bmiHeader.biPlanes = 1; info.bmiHeader.biBitCount = 24;
        const auto size = ((width * 3 + 3) & ~3) * height;
        std::vector<char> pixels(size);
        check(GetDIBits(memory, bitmap, 0, height, pixels.data(), &info, DIB_RGB_COLORS) != 0, "Fixture preview pixels.");
        BITMAPFILEHEADER header{};
        header.bfType = 0x4d42; header.bfOffBits = sizeof(header) + sizeof(BITMAPINFOHEADER); header.bfSize = header.bfOffBits + size;
        std::ofstream output(std::filesystem::current_path() / name, std::ios::binary);
        output.write(reinterpret_cast<const char*>(&header), sizeof(header));
        output.write(reinterpret_cast<const char*>(&info.bmiHeader), sizeof(BITMAPINFOHEADER));
        output.write(pixels.data(), pixels.size());
        DeleteObject(bitmap); DeleteDC(memory); ReleaseDC(window, dc);
    }
    static void panel_window_checks(Manager& manager, Messages& messages) {
        module = GetModuleHandleW(nullptr);
        const auto host = CreateWindowExW(0, L"STATIC", L"IgnorantShuffle test host", WS_OVERLAPPEDWINDOW,
            100, 100, 300, 330, nullptr, nullptr, module, nullptr);
        check(host != nullptr, "Create fixture AIMP window.");
        ShowWindow(host, SW_SHOWNOACTIVATE);
        messages.main_window = host;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.preferences_file_ = std::filesystem::current_path() / (L"panel-prefs-" + std::to_wstring(GetCurrentProcessId()) + L".ini");
        plugin.pinned_ = false;
        plugin.open_monitor();
        const auto panel = plugin.monitor_.handle();
        const auto exstyle = GetWindowLongPtrW(panel, GWL_EXSTYLE), style = GetWindowLongPtrW(panel, GWL_STYLE);
        check((exstyle & WS_EX_APPWINDOW) && !(exstyle & WS_EX_TOOLWINDOW) && !GetWindow(panel, GW_OWNER),
            "Floating panel is a normal unowned app window eligible for taskbar and Alt+Tab.");
        check((style & WS_THICKFRAME) && (style & WS_MINIMIZEBOX), "Panel has resize borders and a minimize button.");
        auto bounds = [](HWND window) { RECT value{}; GetWindowRect(window, &value); return value; };
        auto child_bounds = [&](int id) { auto value = bounds(GetDlgItem(panel, id)); MapWindowPoints(nullptr, panel, reinterpret_cast<POINT*>(&value), 2); return value; };
        auto scroll_info = [&] { SCROLLINFO value{sizeof(value), SIF_ALL}; GetScrollInfo(plugin.monitor_.handle(), SB_VERT, &value); return value; };
        plugin.monitor_.restore_size(650, 700);
        check(child_bounds(IDC_ENABLED).top == child_bounds(IDC_PINNED).top && child_bounds(IDC_PINNED).top == child_bounds(IDC_ATTACHED).top,
            "All three checkboxes share one row when their full labels fit.");
        plugin.monitor_.restore_size(1, 320);
        RECT narrow{}; GetClientRect(panel, &narrow);
        check(child_bounds(IDC_ATTACHED).top > child_bounds(IDC_ENABLED).top, "Checkboxes wrap when the next full label does not fit.");
        RECT prior_box{};
        for (const int id : {IDC_ENABLED, IDC_PINNED, IDC_ATTACHED}) {
            const auto box = child_bounds(id);
            check(box.left >= 0 && box.right <= narrow.right, "Wrapped checkboxes stay entirely inside the viewport.");
            if (prior_box.right) check(box.top >= prior_box.bottom || box.left >= prior_box.right, "Checkbox rows never overlap.");
            prior_box = box;
        }
        RECT old_field{0, 0, 0, 17}; MapDialogRect(panel, &old_field);
        check(child_bounds(IDC_COOLDOWN).bottom - child_bounds(IDC_COOLDOWN).top < old_field.bottom,
            "The cooldown input is shorter than the previous fixed-height field.");
        plugin.monitor_.restore_position(70, 80);
        plugin.monitor_.restore_size(400, 320);
        SendMessageW(panel, WM_EXITSIZEMOVE, 0, 0);
        auto compact = bounds(panel);
        const auto scroll = scroll_info();
        check(scroll.nMax >= static_cast<int>(scroll.nPage) && (GetWindowLongPtrW(panel, GWL_STYLE) & WS_VSCROLL),
            "Short panel displays a scrollbar with an accessible content range.");
        RECT client{}; GetClientRect(panel, &client);
        for (int value = 1; value <= 10; ++value) {
            const auto button = child_bounds(IDC_QUICK_WEIGHT_0 + value);
            check(button.left >= 0 && button.right <= client.right && button.right > button.left,
                "Every weight button stays inside a narrow viewport.");
            if (value > 1) check(button.left >= child_bounds(IDC_QUICK_WEIGHT_0 + value - 1).right && button.top == child_bounds(IDC_QUICK_WEIGHT_1).top,
                "Narrow layout preserves one nonoverlapping quick-selector row.");
        }
        const auto before_scroll = child_bounds(IDC_ENABLED).top;
        SendMessageW(panel, WM_VSCROLL, SB_PAGEDOWN, 0);
        check(scroll_info().nPos > 0 && child_bounds(IDC_ENABLED).top < before_scroll, "Page scrolling actually moves controls.");
        SendMessageW(panel, WM_VSCROLL, SB_BOTTOM, 0);
        auto notice = child_bounds(IDC_PANEL_NOTICE);
        check(notice.top >= 0 && notice.bottom <= client.bottom, "The bottom of the content is reachable without clipping.");
        SendMessageW(panel, WM_KEYDOWN, VK_HOME, 0);
        check(scroll_info().nPos == 0, "Home returns to the beginning.");
        SendMessageW(GetDlgItem(panel, IDC_COOLDOWN), WM_KEYDOWN, VK_NEXT, 0);
        check(scroll_info().nPos > 0, "Page Down works while a child control has focus.");
        SendMessageW(panel, WM_VSCROLL, SB_TOP, 0);
        SendMessageW(panel, WM_MOUSEWHEEL, MAKEWPARAM(0, -WHEEL_DELTA), 0);
        check(scroll_info().nPos > 0, "Mouse wheel scrolls the compact panel.");
        SendMessageW(panel, WM_VSCROLL, SB_TOP, 0);
        SendMessageW(GetDlgItem(panel, IDC_OPEN_LOGS), WM_SETFOCUS, 0, 0);
        const auto logs = child_bounds(IDC_OPEN_LOGS);
        check(logs.top >= 0 && logs.bottom <= client.bottom, "Keyboard focus scrolls hidden controls into view.");
        SendMessageW(panel, WM_VSCROLL, SB_TOP, 0);
        capture_preview(panel, L"monitor-compact.bmp");
        ShowWindow(panel, SW_MINIMIZE);
        plugin.open_monitor(true);
        check(!IsIconic(panel) && IsWindowVisible(panel), "Reopening restores a minimized floating panel.");
        auto click_attach = [&](bool attached) {
            CheckDlgButton(panel, IDC_ATTACHED, attached ? BST_CHECKED : BST_UNCHECKED);
            SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_ATTACHED, BN_CLICKED), 0);
        };
        click_attach(true);
        check(plugin.side_panel_, "Side-panel checkbox applies attachment mode.");
        auto main = bounds(host), attached = bounds(panel);
        check(attached.left == main.right && attached.top == main.top, "Attached panel aligns with the host's right edge.");
        SetWindowPos(host, nullptr, 160, 140, 300, 330, SWP_NOZORDER | SWP_NOACTIVATE);
        SendMessageW(panel, WM_TIMER, 2, 0);
        main = bounds(host); attached = bounds(panel);
        check(attached.left == main.right && attached.top == main.top, "Attachment follows host movement without playback/UI polling.");
        ShowWindow(host, SW_MINIMIZE); SendMessageW(panel, WM_TIMER, 2, 0);
        check(!IsWindowVisible(panel), "Side panel hides with the minimized host.");
        ShowWindow(host, SW_SHOWNOACTIVATE); SendMessageW(panel, WM_TIMER, 2, 0);
        check(IsWindowVisible(panel) && !IsIconic(panel), "Side panel returns when the host is restored.");
        ShowWindow(host, SW_HIDE); SendMessageW(panel, WM_TIMER, 2, 0);
        check(!IsWindowVisible(panel), "Side panel also follows the host into the tray.");
        ShowWindow(host, SW_SHOWNOACTIVATE); SendMessageW(panel, WM_TIMER, 2, 0);
        SendMessageW(panel, WM_CLOSE, 0, 0);
        ShowWindow(host, SW_HIDE); SendMessageW(panel, WM_TIMER, 2, 0);
        ShowWindow(host, SW_SHOWNOACTIVATE); SendMessageW(panel, WM_TIMER, 2, 0);
        check(!IsWindowVisible(panel), "Host restore must not resurrect a panel explicitly closed by the user.");
        plugin.open_monitor();
        MONITORINFO display{sizeof(display)}; GetMonitorInfoW(MonitorFromWindow(host, MONITOR_DEFAULTTONEAREST), &display);
        SetWindowPos(host, nullptr, display.rcWork.right - 300, 140, 300, 330, SWP_NOZORDER | SWP_NOACTIVATE);
        SendMessageW(panel, WM_TIMER, 2, 0);
        main = bounds(host); attached = bounds(panel);
        check(attached.right == main.left && attached.left >= display.rcWork.left, "Panel uses the left edge when no room remains on the right.");
        Plugin saved;
        saved.preferences_file_ = plugin.preferences_file_; saved.load_preferences();
        check(saved.side_panel_, "Attachment toggle survives restart.");
        const auto saved_x = static_cast<int>(GetPrivateProfileIntW(L"Monitor", L"X", -1, plugin.preferences_file_.c_str()));
        check(saved_x == compact.left, "Automatic attachment movement does not overwrite the floating position.");
        click_attach(false);
        auto floating = bounds(panel);
        check(!plugin.side_panel_ && floating.left == compact.left && floating.top == compact.top,
            "Detaching restores the independent floating position.");
        const auto width = compact.right - compact.left, height = compact.bottom - compact.top;
        plugin.monitor_.destroy(); plugin.open_monitor();
        floating = bounds(plugin.monitor_.handle());
        check(floating.right - floating.left == width && floating.bottom - floating.top == height,
            "Saved window size is restored after recreation.");
        plugin.monitor_.restore_size(650, 1000);
        check(scroll_info().nPos == 0, "Expanding the panel clamps away obsolete scrolling.");
        capture_preview(plugin.monitor_.handle(), L"monitor-expanded.bmp");
        plugin.Finalize(); messages.main_window = nullptr; DestroyWindow(host);
        std::filesystem::remove(plugin.preferences_file_);
    }
    static void cooldown_checks(Manager& manager, Messages& messages) {
        Track first(L"cool-first.flac"), second(L"cool-second.flac"), third(L"cool-third.flac"), excluded(L"cool-zero.flac");
        EmptyPlaylist playlist;
        playlist.items = {&first, &second, &third, &excluded};
        first.owner = second.owner = third.owner = excluded.owner = &playlist;
        second.index = 1; third.index = 2; excluded.index = 3;
        Player player; player.current = &first;
        PlaybackQueue queue;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.player_ = query<IAIMPServicePlayer>(&player, IID_IAIMPServicePlayer);
        plugin.queue_ = query<IAIMPServicePlaybackQueue2>(&queue, IID_IAIMPServicePlaybackQueue2);
        plugin.preferences_file_ = std::filesystem::current_path() / (L"cooldown-prefs-" + std::to_wstring(GetCurrentProcessId()) + L".ini");
        plugin.weights_.set("cool-first.flac", 10);
        plugin.weights_.set("cool-third.flac", 25);
        plugin.weights_.set("cool-zero.flac", 0);
        QueueOutput manual, next;
        plugin.OnSelect(&first, &manual);
        HRESULT result = E_NOTIMPL;
        plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        module = GetModuleHandleW(nullptr);
        plugin.open_monitor();
        const auto window = plugin.monitor_.handle();
        SetDlgItemInt(window, IDC_COOLDOWN, 3, FALSE);
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_APPLY_COOLDOWN, BN_CLICKED), 0);
        check(plugin.cooldown_ == 3 && plugin.monitor_state().cooldown_status.find(L"Playlist too short") != std::wstring::npos,
            "Applying cooldown immediately explains an undersized playlist, before any queue request.");
        wchar_t status[512]{};
        GetDlgItemTextW(window, IDC_COOLDOWN_STATUS, status, 512);
        check(std::wstring(status).find(L"need 4") != std::wstring::npos && std::wstring(status).find(L"without cooldown") != std::wstring::npos,
            "Visible warning explains the required distinct track count and fallback.");
        const auto label = GetDlgItem(window, IDC_COOLDOWN_STATUS);
        RECT bounds{}; GetClientRect(label, &bounds);
        RECT measured = bounds;
        const auto dc = GetDC(label);
        const auto font = SelectObject(dc, reinterpret_cast<HFONT>(SendMessageW(label, WM_GETFONT, 0, 0)));
        DrawTextW(dc, status, -1, &measured, DT_CALCRECT | DT_WORDBREAK | DT_NOPREFIX);
        SelectObject(dc, font); ReleaseDC(label, dc);
        check(measured.bottom <= bounds.bottom, "The complete fallback warning must fit its visible status area.");
        Plugin restored;
        restored.preferences_file_ = plugin.preferences_file_;
        restored.load_preferences();
        check(restored.cooldown_ == 3, "Cooldown setting persists across plugin instances.");
        SetDlgItemInt(window, IDC_COOLDOWN, 10, FALSE);
        plugin.update_monitor();
        check(GetDlgItemInt(window, IDC_COOLDOWN, nullptr, FALSE) == 10 && plugin.cooldown_ == 3,
            "UI refresh preserves unfinished input without applying it.");
        plugin.set_cooldown(2);
        check(GetDlgItemInt(window, IDC_COOLDOWN, nullptr, FALSE) == 2, "Applied setting is reflected in the edit control.");
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        check(!plugin.playing_->cooldown.fallback && plugin.playing_->cooldown.blocked == 1 && !same_object(next.item.get(), &first),
            "N+1 positive tracks activates cooldown and excludes the confirmed current filename.");
        check(plugin.monitor_state().decision.find(L"Cooling down: 1") != std::wstring::npos,
            "Next-track info reports cooling-down tracks separately from zero weights.");
        // Editing a positive weight to zero must shrink capacity; zero never
        // becomes an emergency fallback candidate.
        plugin.weights_.set("cool-third.flac", 0); plugin.changed();
        check(plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next) == TRUE && next.item,
            "Undersized cooldown keeps the weighted queue handled rather than delegating to AIMP.");
        check(plugin.playing_->cooldown.fallback && plugin.playing_->decision.eligible == 2 && plugin.playing_->decision.excluded == 2,
            "Only distinct positive weights count toward capacity; fallback preserves zero exclusions.");
        check(plugin.monitor_state().decision.find(L"Cooling down: 0") != std::wstring::npos,
            "Fallback next-track info reports no active cooldown exclusions.");
        check(!same_object(next.item.get(), &third) && !same_object(next.item.get(), &excluded), "Fallback cannot choose a weight-zero track.");
        check(plugin.monitor_state().cooldown_status.find(L"Playlist too short") != std::wstring::npos, "Decision panel displays fallback after a weight change.");
        plugin.weights_.set("cool-third.flac", 25); plugin.changed();
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        check(!plugin.playing_->cooldown.fallback && !same_object(next.item.get(), &first), "Cooldown resumes when capacity returns.");
        // Remove/restore a playlist item without changing the saved setting.
        playlist.items = {&first, &second, &excluded}; excluded.index = 2;
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        check(plugin.playing_->cooldown.fallback, "A shorter source playlist automatically suspends cooldown.");
        playlist.items = {&first, &second, &third, &excluded}; excluded.index = 3;
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        check(!plugin.playing_->cooldown.fallback, "Restored playlist capacity automatically reactivates cooldown.");
        const auto played = plugin.recent_tracks_.size();
        plugin.set_enabled(false);
        QueueOutput off_manual;
        plugin.OnSelect(&excluded, &off_manual);
        plugin.OnSelect(&excluded, &off_manual);
        check(plugin.recent_tracks_.size() == played, "Manual preparation while off is not yet a playback start.");
        plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        player.current = &excluded;
        check(plugin.recent_tracks_.size() == played + 1 && plugin.recent_tracks_.back() == "cool-zero.flac",
            "Manual weight-zero playback while off still counts exactly once toward cooldown.");
        plugin.set_enabled(true);
        plugin.GetNext(&excluded, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        check(!same_object(next.item.get(), &first) && !same_object(next.item.get(), &excluded), "Toggling preserves confirmed listening history.");
        check(plugin.recent_tracks_.size() == played + 1, "Enabling the plugin and drawing a choice do not advance cooldown.");
        plugin.set_cooldown(0);
        check(plugin.monitor_state().cooldown_status.find(L"off") != std::wstring::npos, "Zero restores the default no-cooldown mode.");
        plugin.Finalize();
        std::filesystem::remove(plugin.preferences_file_);
    }
    static void quick_controls_checks(Manager& manager, Messages& messages) {
        Track first(L"quick-first.flac"), second(L"quick-second.flac"), excluded(L"quick-excluded.flac");
        EmptyPlaylist playlist;
        playlist.items = {&first, &second, &excluded};
        first.owner = second.owner = excluded.owner = &playlist;
        second.index = 1; excluded.index = 2;
        playlist.properties.cursor = 0;
        Player player; player.current = &first;
        PlaybackQueue queue;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.player_ = query<IAIMPServicePlayer>(&player, IID_IAIMPServicePlayer);
        plugin.queue_ = query<IAIMPServicePlaybackQueue2>(&queue, IID_IAIMPServicePlaybackQueue2);
        plugin.weights_file_ = std::filesystem::current_path() / (L"quick-weights-" + std::to_wstring(GetCurrentProcessId()) + L".tsv");
        plugin.weights_.set("quick-excluded.flac", 0);
        QueueOutput manual, next;
        plugin.OnSelect(&first, &manual);
        HRESULT result = E_NOTIMPL;
        plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        module = GetModuleHandleW(nullptr);
        plugin.open_monitor();
        const auto window = plugin.monitor_.handle();
        auto click = [&](int id) { SendMessageW(window, WM_COMMAND, MAKEWPARAM(id, BN_CLICKED), 0); };
        check(IsDlgButtonChecked(window, IDC_QUICK_WEIGHT_5) == BST_CHECKED, "Default current weight must be highlighted.");
        const auto before_focus = plugin.weights_.entries();
        const auto focus_notifications = queue.notifications;
        SetFocus(GetDlgItem(window, IDC_CURRENT_TRACK));
        for (int value = 0; value <= 10; ++value) SetFocus(GetDlgItem(window, IDC_QUICK_WEIGHT_0 + value));
        SetFocus(GetDlgItem(window, IDC_CURRENT_TRACK));
        check(plugin.weights_.entries() == before_focus && queue.notifications == focus_notifications,
            "Focusing or tabbing through quick controls must never save a weight or invalidate playback.");
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_QUICK_WEIGHT_2, BN_SETFOCUS), reinterpret_cast<LPARAM>(GetDlgItem(window, IDC_QUICK_WEIGHT_2)));
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_QUICK_WEIGHT_2, BN_KILLFOCUS), reinterpret_cast<LPARAM>(GetDlgItem(window, IDC_QUICK_WEIGHT_2)));
        check(plugin.weights_.entries() == before_focus, "Non-click button notifications cannot change weights.");
        RECT previous{};
        for (int weight = 1; weight <= 10; ++weight) {
            const auto button = GetDlgItem(window, IDC_QUICK_WEIGHT_0 + weight);
            RECT bounds{}; GetWindowRect(button, &bounds);
            check(button && IsWindowVisible(button) && IsWindowEnabled(button), "All ten quick weight buttons must be visible and usable.");
            if (weight > 1) check(bounds.top == previous.top && bounds.left >= previous.right, "Weight buttons must form one nonoverlapping row.");
            previous = bounds;
        }
        plugin.weights_.set("quick-first.flac", 25);
        plugin.update_monitor();
        check(plugin.monitor_state().selected_weight == -1 && plugin.monitor_state().current_weight.find(L"25") != std::wstring::npos,
            "Imported weights above ten must display exactly without a misleading button highlight.");
        for (int weight = 0; weight <= 10; ++weight) check(IsDlgButtonChecked(window, IDC_QUICK_WEIGHT_0 + weight) == BST_UNCHECKED, "Imported weight must not be clamped to an editor button.");
        const auto before_edit = queue.notifications;
        click(IDC_QUICK_WEIGHT_3);
        check(plugin.weights_.get("quick-first.flac") == 3 && plugin.weights_.get("quick-second.flac") == 5, "One click edits the actual current track only.");
        check(IsDlgButtonChecked(window, IDC_QUICK_WEIGHT_3) == BST_CHECKED && IsDlgButtonChecked(window, IDC_QUICK_WEIGHT_5) == BST_UNCHECKED, "Applied weight must be highlighted immediately.");
        ignorant::Weights saved; saved.load(plugin.weights_file_);
        check(saved.get("quick-first.flac") == 3 && queue.notifications == before_edit + 1, "Quick edits save and invalidate lookahead immediately.");
        click(IDC_QUICK_WEIGHT_0);
        check(plugin.weights_.get("quick-first.flac") == 0 && IsDlgButtonChecked(window, IDC_QUICK_WEIGHT_0) == BST_CHECKED, "Zero exclusion must remain one click away.");
        plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &next);
        check(same_object(next.item.get(), &second) && player.current == &first, "Excluding the current track changes future selection without interrupting it.");
        click(IDC_QUICK_WEIGHT_10);
        plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &next);
        const auto original_next = next.item;
        const auto original_playing = plugin.playing_;
        const auto before_reroll = queue.notifications;
        check(IsWindowEnabled(GetDlgItem(window, IDC_REROLL_NEXT)), "Reroll is available during active weighted playback.");
        click(IDC_REROLL_NEXT);
        const auto rerolled = plugin.playing_->next.lock();
        check(rerolled && !same_object(rerolled->item.get(), original_next.get()) && !same_object(rerolled->item.get(), &excluded), "Reroll replaces the old choice and respects zero exclusions.");
        check(plugin.playing_ == original_playing && player.current == &first && queue.notifications == before_reroll + 1 && messages.shuffle == TRUE,
            "Reroll refreshes the host queue once without playing/skipping a track or toggling AIMP Shuffle.");
        check(plugin.monitor_state().next_track == Plugin::track_label(rerolled->item.get()) && plugin.monitor_state().decision.find(L"Reroll") != std::wstring::npos, "Panel shows the committed reroll immediately.");
        const auto after_reroll = plugin.random_;
        plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &next);
        check(plugin.random_ == after_reroll && same_object(next.item.get(), rerolled->item.get()), "AIMP's refresh must reuse the rerolled choice, not draw a different one.");

        // A later playing track must drive the selector, regardless of editor focus.
        SetFocus(GetDlgItem(window, IDC_QUICK_WEIGHT_10));
        QueueOutput second_manual;
        plugin.OnSelect(&second, &second_manual);
        plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        player.current = &second; player.state = AIMP_PLAYER_STATE_PAUSED;
        playlist.properties.cursor = 1;
        plugin.update_monitor();
        check(IsDlgButtonChecked(window, IDC_QUICK_WEIGHT_5) == BST_CHECKED, "Selector follows the newly playing track, including while paused.");
        check(GetFocus() != GetDlgItem(window, IDC_QUICK_WEIGHT_10), "Changing tracks clears focus from the previous track's weight button.");
        SendDlgItemMessageW(window, IDC_QUICK_WEIGHT_7, BM_CLICK, 0, 0);
        check(plugin.weights_.get("quick-second.flac") == 7 && plugin.weights_.get("quick-first.flac") == 10, "Quick edit follows the real player item after a transition.");
        for (int repeat = 0; repeat < 3; ++repeat) {
            plugin.update_monitor();
            for (int value = 0; value <= 10; ++value) {
                const UINT expected = value == 7 ? BST_CHECKED : BST_UNCHECKED;
                check(IsDlgButtonChecked(window, IDC_QUICK_WEIGHT_0 + value) == expected,
                    "Only the new track's saved weight stays selected across click and timer refreshes.");
            }
        }
        capture_preview(window, L"monitor-weight-selection.bmp");
        // Only one positive candidate: reroll retains that track rather than stopping.
        plugin.weights_.set("quick-first.flac", 0); plugin.changed();
        plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &next);
        click(IDC_REROLL_NEXT);
        check(plugin.playing_->next.lock() && same_object(plugin.playing_->next.lock()->item.get(), &second), "Reroll keeps the sole eligible track.");
        player.state = AIMP_PLAYER_STATE_STOPPED;
        plugin.update_monitor();
        for (int weight = 0; weight <= 10; ++weight) check(!IsWindowEnabled(GetDlgItem(window, IDC_QUICK_WEIGHT_0 + weight)), "Weight controls are disabled when nothing is playing.");
        check(!IsWindowEnabled(GetDlgItem(window, IDC_REROLL_NEXT)), "Reroll is disabled when nothing is playing.");
        player.state = AIMP_PLAYER_STATE_PLAYING;
        plugin.set_enabled(false);
        plugin.update_monitor();
        check(IsWindowEnabled(GetDlgItem(window, IDC_QUICK_WEIGHT_4)) && !IsWindowEnabled(GetDlgItem(window, IDC_REROLL_NEXT)), "Weights can be edited with the plugin off, while reroll stays disabled.");
        click(IDC_QUICK_WEIGHT_4);
        check(plugin.weights_.get("quick-second.flac") == 4 && !plugin.enabled_, "Weight editing must not enable the plugin.");
        plugin.Finalize();
        std::filesystem::remove(plugin.weights_file_);
    }
    static void transition_trace_checks(Manager& manager, Messages& messages, int cooldown = 0, bool finite = false) {
        Track first(L"trace-first.flac"), second(L"trace-second.flac"), third(L"trace-third.flac");
        EmptyPlaylist playlist;
        playlist.items = {&first, &second, &third};
        first.owner = second.owner = third.owner = &playlist;
        second.index = 1; third.index = 2;
        // Internal shuffle order differs from displayed playlist row order.
        first.playback_index = 2; second.playback_index = 0; third.playback_index = 1;
        Player player;
        player.current = &first;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.player_ = query<IAIMPServicePlayer>(&player, IID_IAIMPServicePlayer);
        plugin.weights_.set("trace-first.flac", 10); plugin.weights_.set("trace-second.flac", 3); plugin.weights_.set("trace-third.flac", 10);
        plugin.random_.seed(260325);
        plugin.cooldown_ = cooldown;
        plugin.finite_ = finite;
        const auto saved_end_action = messages.end_action;
        if (finite) messages.end_action = 1;
        QueueOutput manual;
        plugin.OnSelect(&first, &manual);
        HRESULT result = E_NOTIMPL;
        plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        std::map<std::shared_ptr<FinitePass>, std::set<std::string>> finite_heard;
        if (finite) finite_heard[plugin.active_pass_].insert("trace-first.flac");
        int repeats = 0;
        for (int transition = 0; transition < 128; ++transition) {
            int cursor = -1;
            player.current->GetValueAsInt32(AIMP_PLAYLISTITEM_PROPID_PLAYBACKQUEUEINDEX, &cursor);
            playlist.properties.cursor = cursor;
            QueueOutput next, lookahead, probe;
            plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &next);
            auto item = query<IAIMPPlaylistItem>(next.item.get(), IID_IAIMPPlaylistItem);
            check(static_cast<bool>(item), "Transition trace must have an eligible next track.");
            if (cooldown && !finite) {
                const auto recent = plugin.projected_recent(plugin.audible_);
                const auto begin = recent.size() > static_cast<std::size_t>(cooldown) ? recent.size() - cooldown : 0;
                for (std::size_t i = begin; i < recent.size(); ++i)
                    check(track_key(item.get()) != recent[i], "Every committed transition must honor its full cooldown.");
            }
            if (transition % 17 == 0) {
                const auto old_choice = item;
                plugin.reroll_next();
                plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &next);
                item = query<IAIMPPlaylistItem>(next.item.get(), IID_IAIMPPlaylistItem);
                check(item && (finite || cooldown == 2 || !same_object(item.get(), old_choice.get())), "Reroll changes the choice when another cooldown-eligible track exists.");
            }
            const auto shown_before = plugin.monitor_state();
            check(shown_before.next_track == Plugin::track_label(item.get()) && shown_before.decision.find(L"Weight:") != std::wstring::npos,
                "Every pre-transition display must match the cursor-based next track.");
            plugin.GetNext(item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &lookahead);
            if (cooldown && !finite) {
                auto future = query<IAIMPPlaylistItem>(lookahead.item.get(), IID_IAIMPPlaylistItem);
                check(future && !same_object(future.get(), item.get()), "Lookahead must include the not-yet-started source in cooldown.");
                if (cooldown == 2) check(!same_object(future.get(), player.current), "Two-track cooldown must include both confirmed and pending predecessors.");
            }
            const auto before_probe = plugin.random_;
            plugin.GetNext(item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &probe);
            check(plugin.random_ == before_probe && occurrence_from(lookahead.custom.get()) == occurrence_from(probe.custom.get()),
                "Repeated future probes must stay stable even when a track repeats within lookahead.");
            const auto previous_context = plugin.playing_;
            const auto chosen_context = occurrence_from(next.custom.get());
            if (same_object(item.get(), player.current)) ++repeats;
            plugin.OnSelect(item.get(), &next); // preparation; player still reports old audio
            check(plugin.playing_ == chosen_context && plugin.playing_ != previous_context, "A new logical playback must advance exactly one context.");
            plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &probe);
            check(same_object(probe.item.get(), item.get()), "While preparing, old cursor requests must retain the already-selected transition.");
            plugin.GetNext(item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &lookahead);
            const auto decision_before_duplicate = plugin.playing_->next.lock();
            plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_END, AIMP_MES_HAS_NEXT_TRACK, nullptr, &result);
            plugin.CoreMessage(AIMP_MSG_EVENT_PLAYER_STATE, AIMP_PLAYER_STATE_STOPPED, nullptr, &result);
            plugin.OnSelect(item.get(), &next); // same transition, immediately before STREAM_START
            check(plugin.playing_ == chosen_context && !plugin.queue_refresh_pending_ && plugin.playing_->next.lock() == decision_before_duplicate,
                "The duplicate selection notification must not consume lookahead or start loop recovery.");
            // Also exercise a duplicate whose host wrapper omits custom data.
            if (transition % 2 == 0) {
                QueueOutput without_custom;
                plugin.OnSelect(item.get(), &without_custom);
                check(plugin.playing_ == chosen_context, "Missing-token duplicate must be idempotent.");
            }
            plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
            if (finite) check(finite_heard[plugin.active_pass_].insert(track_key(item.get())).second, "Finite cursor/prefetch transitions never repeat a filename within one pass.");
            check(plugin.recent_tracks_.size() == static_cast<std::size_t>(transition + 2), "Only one actual stream start counts per double-selection transition; probes and rerolls never count.");
            player.current = item.get();
            player.current->GetValueAsInt32(AIMP_PLAYLISTITEM_PROPID_PLAYBACKQUEUEINDEX, &cursor);
            playlist.properties.cursor = cursor;
            plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &probe);
            const auto shown_after = plugin.monitor_state();
            check(shown_after.next_track == Plugin::track_label(query<IAIMPPlaylistItem>(probe.item.get(), IID_IAIMPPlaylistItem).get()) && shown_after.decision.find(L"Weight:") != std::wstring::npos,
                "After every stream start, next weight and displayed choice must match AIMP's actual cursor request.");
        }
        check(finite || (cooldown ? repeats == 0 : repeats > 0), "Cooldown suppresses repeats; default trace still exercises legitimate weighted repeats.");
        plugin.Finalize();
        messages.end_action = saved_end_action;
        std::cout << "Replayed 128 cursor/prefetch/double-selection/stream-start transitions, cooldown=" << cooldown << ", repeats=" << repeats << ".\n";
    }
    static void cursor_and_duplicate_checks(Manager& manager, Messages& messages) {
        Track first(L"cursor-first.flac"), second(L"cursor-second.flac"), third(L"cursor-third.flac");
        EmptyPlaylist playlist;
        playlist.items = {&first, &second, &third};
        first.owner = second.owner = third.owner = &playlist;
        second.index = 1; third.index = 2;
        first.playback_index = 11; second.playback_index = 5; third.playback_index = 8;
        playlist.properties.cursor = 11;
        QueueOutput manual, from_cursor, direct, ahead;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.random_.seed(3);
        plugin.OnSelect(&first, &manual);
        plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &from_cursor);
        const auto after_cursor = plugin.random_;
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &direct);
        check(plugin.random_ == after_cursor && occurrence_from(from_cursor.custom.get()) == occurrence_from(direct.custom.get()),
            "PlaybackCursor is an internal queue index: cursor and explicit-item requests must share one decision.");
        check(plugin.monitor_state().next_track == Plugin::track_label(query<IAIMPPlaylistItem>(from_cursor.item.get(), IID_IAIMPPlaylistItem).get()),
            "The panel must show the same choice that AIMP receives through START_FROM_CURSOR.");
        auto chosen = query<IAIMPPlaylistItem>(from_cursor.item.get(), IID_IAIMPPlaylistItem);
        check(same_object(chosen.get(), &second), "Seeded log replay selects the second track.");
        plugin.GetNext(chosen.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &ahead);
        plugin.OnSelect(chosen.get(), &from_cursor);
        const auto selected_context = plugin.playing_;
        const auto before_duplicate = plugin.random_;
        // Actual logs: OnSelect -> old stream stops -> OnSelect again -> STREAM_START.
        plugin.OnSelect(chosen.get(), &from_cursor);
        check(plugin.playing_ == selected_context && plugin.playing_->decision_ready && !plugin.queue_refresh_pending_ && plugin.random_ == before_duplicate,
            "Two selection callbacks before one STREAM_START must preserve the same playback and prefetched readout.");
        HRESULT result = E_NOTIMPL;
        plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        playlist.properties.cursor = 5;
        plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &direct);
        check(same_object(direct.item.get(), ahead.item.get()) && plugin.monitor_state().next_track == Plugin::track_label(query<IAIMPPlaylistItem>(direct.item.get(), IID_IAIMPPlaylistItem).get()),
            "After playback starts, cursor requests and the panel must advance to the new track's next choice.");
        plugin.Finalize();

        Plugin startup;
        startup.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        startup.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        startup.random_.seed(3);
        playlist.properties.cursor = 11;
        QueueOutput queued_startup, following_startup;
        startup.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR, &queued_startup);
        auto starting_item = query<IAIMPPlaylistItem>(queued_startup.item.get(), IID_IAIMPPlaylistItem);
        startup.GetNext(starting_item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &following_startup);
        const auto prepared = occurrence_from(queued_startup.custom.get());
        queued_startup.custom = {};
        startup.OnSelect(starting_item.get(), &queued_startup);
        check(startup.playing_ == prepared && startup.playing_->decision_ready,
            "Startup without a custom token must recover the cursor-based prepared choice, not create an empty display context.");
        startup.OnSelect(starting_item.get(), &queued_startup);
        check(startup.playing_ == prepared && !startup.queue_refresh_pending_, "Startup's second selection callback is the same playback.");
        startup.Finalize();
    }
    static void prefetched_display_checks(Manager& manager, Messages& messages) {
        Track first(L"display-first.flac"), second(L"display-second.flac");
        EmptyPlaylist playlist;
        playlist.items = {&first, &second};
        first.owner = second.owner = &playlist;
        second.index = 1;
        QueueOutput manual, next, ahead;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.random_.seed(3);
        plugin.OnSelect(&first, &manual);
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        check(same_object(next.item.get(), &second), "Display regression starts with the second track queued.");
        plugin.GetNext(&second, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &ahead);
        check(same_object(ahead.item.get(), &first), "AIMP has already prefetched the following track.");
        const auto random_state = plugin.random_;
        // The SDK documents CUSTOM as readable through IID_IUnknown. A host
        // need not accept the plugin's private IID in GetValueAsObject itself.
        next.custom_as_iunknown_only = true;
        plugin.OnSelect(&second, &next);
        const auto state = plugin.monitor_state();
        check(state.next_track == L"display-first.flac" && state.decision.find(L"Weight: 5") != std::wstring::npos,
            "Starting a prefetched track must retain its next-track weight instead of the default message.");
        check(plugin.random_ == random_state, "Recovering a prefetched display must not draw a replacement track.");
        plugin.Finalize();

        // Also cover the host omitting CUSTOM entirely on alternate transitions.
        Plugin missing;
        missing.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        missing.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        missing.random_.seed(3);
        manual.item = {}; manual.custom = {};
        next.item = {}; next.custom = {};
        missing.OnSelect(&first, &manual);
        missing.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next);
        missing.GetNext(&second, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &ahead);
        next.custom = {};
        const auto before_select = missing.random_;
        missing.OnSelect(&second, &next);
        check(missing.monitor_state().next_track == L"display-first.flac" && missing.playing_->decision_ready,
            "A missing host token must recover the expected pending choice and its weight details.");
        check(missing.random_ == before_select && !missing.queue_refresh_pending_, "Recovering valid prefetch must not redraw or invalidate it.");
        missing.Finalize();
    }
    static void repeat_checks(Manager& manager, Messages& messages) {
        Track first(L"repeat-first.flac"), second(L"repeat-second.flac");
        EmptyPlaylist playlist;
        playlist.items = {&first, &second};
        first.owner = second.owner = &playlist;
        second.index = 1;
        QueueOutput selected_output, next_output, repeated_output;
        PlaybackQueue queue;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.queue_ = query<IAIMPServicePlaybackQueue2>(&queue, IID_IAIMPServicePlaybackQueue2);
        plugin.random_.seed(3);
        QueueOutput manual;
        plugin.OnSelect(&first, &manual);
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &selected_output);
        auto selected = query<IAIMPPlaylistItem>(selected_output.item.get(), IID_IAIMPPlaylistItem);
        check(static_cast<bool>(selected), "Repeat regression requires a selected track.");
        plugin.OnSelect(selected.get(), &selected_output);
        check(!plugin.queue_refresh_pending_, "A fresh queue context must not invalidate lookahead.");
        HRESULT committed_result = E_NOTIMPL;
        plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &committed_result);
        plugin.GetNext(selected.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next_output);
        const auto after_draw = plugin.random_;
        // AIMP's lookahead can carry an earlier queue context back into playback
        // when the same IAIMPPlaylistItem appears more than once in the queue.
        plugin.OnSelect(selected.get(), &selected_output);
        check(plugin.queue_refresh_pending_ && queue.notifications == 0, "Stale lookahead recovery must defer host queue invalidation until OnSelect returns.");
        plugin.GetNext(selected.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &repeated_output);
        check(plugin.random_ != after_draw, "A replayed queue context must make a fresh weighted draw instead of looping its cached successor.");
        const auto after_fresh_draw = plugin.random_;
        QueueOutput repeated_probe;
        plugin.GetNext(selected.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &repeated_probe);
        check(plugin.random_ == after_fresh_draw && same_object(repeated_probe.item.get(), repeated_output.item.get()), "Repeated probes within one playback must remain stable.");
        const auto fresh_next = plugin.playing_->next.lock();
        HRESULT result = E_NOTIMPL;
        plugin.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        check(!plugin.queue_refresh_pending_ && queue.notifications == 1 && result == E_NOTIMPL, "Stream start flushes the stale host cache without swallowing the event.");
        check(plugin.playing_->decision_ready && plugin.playing_->next.lock() == fresh_next,
            "Deferred stale-cache recovery must preserve the fresh next decision instead of blanking the panel.");
        plugin.Finalize();

        Plugin history;
        history.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        history.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        history.random_.seed(3);
        manual.item = {}; manual.custom = {};
        history.OnSelect(&first, &manual);
        history.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &selected_output);
        check(same_object(selected_output.item.get(), &second), "Seeded history setup selects the second track.");
        history.OnSelect(&second, &selected_output);
        history.GetNext(&second, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next_output);
        check(same_object(next_output.item.get(), &first), "Seeded history setup returns to the first track.");
        history.OnSelect(&first, &next_output);
        const auto before_future = history.random_;
        history.GetNext(&second, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &repeated_output);
        check(history.random_ != before_future, "Future lookahead must not reuse the cached successor of a completed playback.");
        QueueOutput backward;
        history.GetPrev(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &backward);
        check(same_object(backward.item.get(), &second), "Previous returns the actual prior track.");
        const auto prior = history.playing_->previous.lock();
        history.OnSelect(&second, &backward);
        check(history.playing_ == prior && !history.queue_refresh_pending_, "Explicit Previous navigation must preserve the history position.");
        history.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &committed_result);
        history.OnSelect(&second, &backward);
        check(history.playing_ != prior && history.queue_refresh_pending_, "History-navigation permission must be consumed after one playback start.");
        history.Finalize();
    }
    static void startup_checks(Manager& manager, Messages& messages) {
        const auto profile = std::filesystem::current_path() / (L"adapter-profile-" + std::to_wstring(GetCurrentProcessId()));
        PlaybackQueue queue;
        Core core(manager, messages, queue, profile.wstring());
        Plugin plugin;
        check(plugin.Initialize(&core) == S_OK, "Plugin initialization with host services.");
        const auto window = plugin.monitor_.handle();
        check(window && IsWindowVisible(window), "Initialization automatically opens the floating window.");
        check(messages.observer && core.extension, "Initialization registers the message hook and queue.");
        const auto notifications = queue.notifications;
        plugin.set_enabled(false);
        check(queue.notifications > notifications, "Toggling invalidates AIMP's prefetched queue.");
        check(plugin.Finalize() == S_OK && !IsWindow(window), "Finalization destroys the floating window.");
        check(!messages.observer && !core.extension, "Finalization removes host hooks and extensions.");
        {
            std::ifstream input(profile / L"IgnorantShuffle" / L"plugin.log", std::ios::binary);
            const std::string contents{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
            check(contents.find("plugin.start version=" + std::string(ignorant::build_version)) != std::string::npos && contents.find("plugin.stop") != std::string::npos,
                "Plugin lifecycle must produce and flush diagnostic logs.");
        }
        std::filesystem::remove(profile / L"IgnorantShuffle" / L"preferences.ini");
        std::filesystem::remove(profile / L"IgnorantShuffle" / L"plugin.log");
        std::filesystem::remove(profile / L"IgnorantShuffle");
        std::filesystem::remove(profile);
    }
    static void monitor_checks(Manager& manager, Messages& messages) {
        Track first(L"first.flac"), second(L"second.flac"), excluded(L"excluded.flac");
        EmptyPlaylist playlist;
        playlist.items = {&first, &second, &excluded};
        first.owner = second.owner = excluded.owner = &playlist;
        second.index = 1; excluded.index = 2;
        QueueOutput output, prefetch;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.weights_.set("first.flac", 5);
        plugin.weights_.set("second.flac", 25);
        plugin.weights_.set("excluded.flac", 0);
        plugin.playing_ = plugin.remember(query<IAIMPPlaylistItem>(&first, IID_IAIMPPlaylistItem), query<IAIMPPlaylist>(&playlist, IID_IAIMPPlaylist));
        check(plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output) == TRUE && output.item, "Weighted decision produces a next item.");
        check(!same_object(output.item.get(), &excluded), "Zero-weight track must never enter the queue.");
        const auto random_state = plugin.random_;
        const auto state = plugin.monitor_state();
        auto chosen = query<IAIMPPlaylistItem>(output.item.get(), IID_IAIMPPlaylistItem);
        check(state.next_track == Plugin::track_label(chosen.get()), "Panel must display the track supplied to AIMP.");
        check(state.decision.find(L"Eligible: 2 | Weight 0 excluded: 1") != std::wstring::npos, "Panel explains candidate counts.");
        check(plugin.random_ == random_state, "Reading the panel must not consume a random draw.");
        plugin.GetNext(&second, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &prefetch);
        check(plugin.monitor_state().next_track == state.next_track, "Prefetch for a different source must not replace the current decision.");
        plugin.weights_.set("first.flac", 0); plugin.weights_.set("second.flac", 0); plugin.changed();
        plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        check(!output.item && plugin.monitor_state().next_track.find(L"No eligible tracks") != std::wstring::npos, "All-zero decision must explain the stop.");

        plugin.preferences_file_ = std::filesystem::current_path() / L"test-monitor-preferences.ini";
        module = GetModuleHandleW(nullptr);
        plugin.open_monitor();
        const auto window = plugin.monitor_.handle();
        check(window && IsWindowVisible(window), "Floating window must open without a modal loop.");
        check((GetWindowLongPtrW(window, GWL_EXSTYLE) & WS_EX_TOPMOST) != 0, "Window defaults to always on top.");
        CheckDlgButton(window, IDC_ENABLED, BST_UNCHECKED);
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_ENABLED, BN_CLICKED), 0);
        check(!plugin.enabled_, "Panel switch disables weighted shuffle.");
        check(plugin.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output) == FALSE, "Disabled plugin hands playback back to AIMP.");
        check(plugin.monitor_state().mode.find(L"Off") != std::wstring::npos, "Panel reports disabled state.");
        CheckDlgButton(window, IDC_PINNED, BST_UNCHECKED);
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_PINNED, BN_CLICKED), 0);
        check(!plugin.pinned_ && !(GetWindowLongPtrW(window, GWL_EXSTYLE) & WS_EX_TOPMOST), "Pin switch changes actual topmost style.");
        Plugin restored;
        restored.preferences_file_ = plugin.preferences_file_;
        restored.load_preferences();
        check(!restored.enabled_ && !restored.pinned_, "Toggle preferences survive restart.");
        SendMessageW(window, WM_CLOSE, 0, 0);
        check(IsWindow(window) && !IsWindowVisible(window), "Close hides the floating window for this session.");
        plugin.open_monitor();
        check(IsWindowVisible(window), "Window can be reopened.");
        HRESULT message_result = E_NOTIMPL;
        plugin.CoreMessage(AIMP_MSG_EVENT_TERMINATING, 0, nullptr, &message_result);
        check(!IsWindow(window) && message_result == E_NOTIMPL, "Shutdown destroys the window without swallowing the host message.");
        plugin.Finalize();
        std::filesystem::remove(plugin.preferences_file_);
    }
    static void repaint_checks() {
        module = GetModuleHandleW(nullptr);
        MonitorWindow panel;
        check(panel.create(module), "Create sequential-playback repaint fixture.");
        panel.restore_size(472, 548); panel.restore_position(40, 40); panel.show();
        MonitorState state;
        state.mode = L"On, waiting - turn AIMP Shuffle on to activate.";
        state.current_weight = L"Current weight: 1 (Global)"; state.can_set_weight = true; state.selected_weight = 1;
        state.next_track = L"AIMP controls the next track";
        state.decision = L"Weighted selection begins when AIMP Shuffle is enabled.";
        state.cooldown_status = L"Cooldown: 10 other tracks before a repeat. Cooling down: 10.";
        state.notice = L"AIMP's manual queue and repeat settings can take priority.";
        auto pixels = [&] {
            RECT area{}; GetWindowRect(GetDlgItem(panel.handle(), IDC_COOLDOWN_STATUS), &area);
            RECT end{}; GetWindowRect(GetDlgItem(panel.handle(), IDC_NEXT_GROUP), &end); area.bottom = end.bottom;
            MapWindowPoints(nullptr, panel.handle(), reinterpret_cast<POINT*>(&area), 2);
            const auto dc = GetDC(panel.handle()), memory = CreateCompatibleDC(dc);
            const int width = area.right - area.left, height = area.bottom - area.top;
            const auto bitmap = CreateCompatibleBitmap(dc, width, height); const auto old = SelectObject(memory, bitmap);
            BitBlt(memory, 0, 0, width, height, dc, area.left, area.top, SRCCOPY); SelectObject(memory, old);
            BITMAPINFO info{}; info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER); info.bmiHeader.biWidth = width;
            info.bmiHeader.biHeight = -height; info.bmiHeader.biPlanes = 1; info.bmiHeader.biBitCount = 32;
            std::vector<unsigned char> result(static_cast<std::size_t>(width) * height * 4);
            check(GetDIBits(memory, bitmap, 0, height, result.data(), &info, DIB_RGB_COLORS) != 0, "Read fixture's painted client pixels.");
            DeleteObject(bitmap); DeleteDC(memory); ReleaseDC(panel.handle(), dc); return result;
        };
        for (int i = 0; i < 16; ++i) {
            state.current_key = std::to_wstring(i);
            state.current_track = i % 2 ? L"Short track" : L"Maximum The Hormone - ChuChu Lovely MuniMuni MuraMura PrinPrin Boron Nururu ReroRero";
            state.current_weight = i % 3 ? L"Current weight: 1 (Global)" : L"Current weight: 25 (Folder)\nEditing Global: 1";
            panel.update(state);
            // Drain pending paint only; do not invalidate or repair the layout.
            RedrawWindow(panel.handle(), nullptr, nullptr, RDW_UPDATENOW | RDW_ALLCHILDREN);
            const auto normal = pixels();
            RedrawWindow(panel.handle(), nullptr, nullptr, RDW_INVALIDATE | RDW_ERASE | RDW_ALLCHILDREN | RDW_UPDATENOW);
            check(normal == pixels(), "Track changes must paint identically to a full clean redraw, without stale text or borders.");
        }
        capture_preview(panel.handle(), L"monitor-sequential-repaint.bmp");
        panel.destroy();
    }
    static void update_settings_checks() {
        std::atomic<int> requests{0};
        Plugin plugin([&](const auto& url, auto, const auto&, const auto&) {
            check(url.find("api.github.com/repos/example/shuffle/releases/latest") != std::string::npos, "Automatic checks may only fetch release metadata.");
            ++requests;
            const std::string json = "{\"tag_name\":\"v1.0.0\",\"assets\":[]}";
            return ignorant::HttpReply{200, {json.begin(), json.end()}};
        });
        check(plugin.auto_updates_ && plugin.update_repository_ == ignorant::default_update_repository,
            "Auto updates default on with the compiled release repository.");
        plugin.update_repository_.clear();
        plugin.next_update_check_ = 0; plugin.update_tick();
        check(plugin.updater_.state().phase == ignorant::UpdatePhase::Unconfigured && requests == 0, "Blank source does not start update checks.");
        plugin.preferences_file_ = std::filesystem::current_path() / L"update-ui-prefs.ini";
        module = GetModuleHandleW(nullptr);
        const auto window = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_UPDATES), nullptr, Plugin::updates_procedure, reinterpret_cast<LPARAM>(&plugin));
        check(window && IsDlgButtonChecked(window, IDC_UPDATE_AUTO) == BST_CHECKED, "Update dialog reflects default automatic option.");
        check(!IsWindowEnabled(GetDlgItem(window, IDC_UPDATE_CHECK)) && !IsWindowEnabled(GetDlgItem(window, IDC_UPDATE_INSTALL)), "Placeholder source disables check/install.");
        CheckDlgButton(window, IDC_UPDATE_AUTO, BST_UNCHECKED);
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_UPDATE_AUTO, BN_CLICKED), 0);
        Plugin restored; restored.preferences_file_ = plugin.preferences_file_; restored.load_preferences();
        check(!restored.auto_updates_, "Automatic update opt-out survives restart.");
        ShowWindow(window, SW_SHOWNOACTIVATE); capture_preview(window, L"update-settings.bmp");
        plugin.update_repository_ = "example/shuffle"; plugin.update_tick();
        check(requests == 0, "Automatic opt-out prevents scheduled network requests.");
        CheckDlgButton(window, IDC_UPDATE_AUTO, BST_CHECKED);
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_UPDATE_AUTO, BN_CLICKED), 0);
        auto finish = [&] {
            const auto deadline = GetTickCount64() + 3000;
            while (plugin.updater_.state().busy() && GetTickCount64() < deadline) Sleep(5);
            check(!plugin.updater_.state().busy(), "Fixture update check finishes."); plugin.update_tick();
        };
        finish();
        check(requests == 1 && plugin.updater_.state().phase == ignorant::UpdatePhase::Available && !plugin.install_requested_, "Auto check reports new releases without downloading or installing.");
        plugin.update_tick(); plugin.update_tick();
        check(requests == 1 && plugin.next_update_check_ > GetTickCount64() + 23ULL * 60 * 60 * 1000, "Automatic checks are limited to daily polling.");
        CheckDlgButton(window, IDC_UPDATE_AUTO, BST_UNCHECKED);
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_UPDATE_AUTO, BN_CLICKED), 0);
        SendMessageW(window, WM_COMMAND, MAKEWPARAM(IDC_UPDATE_CHECK, BN_CLICKED), 0); finish();
        check(requests == 2, "Manual check works with automatic checks off.");
        restored.load_preferences(); check(restored.update_repository_ == "example/shuffle", "Configured source survives restart.");
        DestroyWindow(window); plugin.Finalize(); std::filesystem::remove(plugin.preferences_file_);
    }
    static void update_install_checks() {
        Messages messages;
        const auto host = CreateWindowW(L"STATIC", L"", 0, 0, 0, 0, 0, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
        check(host != nullptr, "Create update host."); messages.main_window = host;
        const std::vector<unsigned char> bytes{'P','K',3,4,1,2,3,4};
        std::atomic<int> downloads{0}; int launches = 0;
        Plugin plugin([&](const auto& url, auto, const auto&, const auto&) {
            if (url.find("api.github.com") == std::string::npos) { ++downloads; return ignorant::HttpReply{200, bytes}; }
            const std::string json = "{\"tag_name\":\"v99.0.0\",\"assets\":[{\"name\":\"IgnorantShuffle-v99.0.0.aimppack\",\"state\":\"uploaded\",\"size\":8,\"digest\":\"sha256:" + ignorant::update_sha256(bytes) + "\",\"browser_download_url\":\"https://github.com/example/shuffle/releases/download/v99.0.0/IgnorantShuffle-v99.0.0.aimppack\"}]}";
            return ignorant::HttpReply{200, {json.begin(), json.end()}};
        }, [&](HWND owner, const ignorant::UpdateInstallCommand& command) {
            check(owner == host && !command.arguments.empty(), "Installer receives host and package."); ++launches;
        });
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        plugin.auto_updates_ = false; plugin.update_repository_ = "example/shuffle";
        const auto folder = std::filesystem::current_path() / L"update-install-ui-fixture";
        plugin.weights_file_ = folder / L"weights.tsv";
        module = GetModuleHandleW(nullptr);
        const auto window = CreateDialogParamW(module, MAKEINTRESOURCEW(IDD_UPDATES), nullptr, Plugin::updates_procedure, reinterpret_cast<LPARAM>(&plugin));
        check(window != nullptr, "Create installation dialog.");
        auto finish = [&] {
            const auto deadline = GetTickCount64() + 3000;
            while (plugin.updater_.state().busy() && GetTickCount64() < deadline) Sleep(5);
            check(!plugin.updater_.state().busy(), "Installation worker finishes."); plugin.update_tick();
        };
        plugin.check_updates(); finish();
        SendMessageW(window, WM_COMMAND, IDC_UPDATE_INSTALL, 0); finish();
        const auto package = plugin.updater_.state().package;
        check(launches == 1 && downloads == 1 && plugin.updater_.state().phase == ignorant::UpdatePhase::HandedOff, "Download button requests installation once.");
        check(IsWindow(window) && plugin.update_dialog_ == window && IsWindowEnabled(GetDlgItem(window, IDC_UPDATE_INSTALL)) && IsWindowEnabled(GetDlgItem(window, IDC_UPDATE_FOLDER)), "Dialog remains available with retry and package folder.");
        wchar_t label[64]{}; GetDlgItemTextW(window, IDC_UPDATE_INSTALL, label, 64);
        check(std::wstring(label) == L"Open installer again", "Retry is clearly labeled.");
        SendMessageW(window, WM_COMMAND, IDC_UPDATE_INSTALL, 0); plugin.update_tick();
        check(launches == 2 && downloads == 1 && plugin.updater_.state().package == package, "Retry reuses the verified package without downloading or automatic repeat launches.");
        ShowWindow(window, SW_SHOWNOACTIVATE); capture_preview(window, L"update-installer-ready.bmp");
        DestroyWindow(window); plugin.Finalize(); DestroyWindow(host);
        std::filesystem::remove(package); std::filesystem::remove(package.parent_path());
        std::filesystem::remove(folder / L"Updates"); std::filesystem::remove(folder);
    }
    static void finite_checks() {
        Manager manager;
        Messages messages; messages.shuffle = TRUE;
        Track first(L"finite-A.flac"), second(L"finite-B.flac"), third(L"finite-C.flac"), duplicate(L"finite-B.flac"), zero(L"finite-zero.flac");
        EmptyPlaylist playlist; playlist.items = {&first, &second, &third, &duplicate, &zero};
        for (int i = 0; i < playlist.GetItemCount(); ++i) {
            auto* item = static_cast<Track*>(playlist.items[i]); item->owner = &playlist; item->index = i;
        }
        manager.active = &playlist; manager.loaded = {&playlist};
        PlaybackQueue queue;
        Player player;
        auto setup = [&](Plugin& plugin) {
            plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
            plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
            plugin.queue_ = query<IAIMPServicePlaybackQueue2>(&queue, IID_IAIMPServicePlaybackQueue2);
            plugin.player_ = query<IAIMPServicePlayer>(&player, IID_IAIMPServicePlayer);
            plugin.finite_ = true; plugin.cooldown_ = 10000;
            plugin.weights_.set("finite-A.flac", 100); plugin.weights_.set("finite-B.flac", 10);
            plugin.weights_.set("finite-C.flac", 1); plugin.weights_.set("finite-zero.flac", 0);
        };
        HRESULT result = E_NOTIMPL;
        Plugin stop; setup(stop);
        QueueOutput output;
        check(stop.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &output) == TRUE && output.item, "Finite pass starts with a positive weighted track.");
        std::set<std::string> heard;
        for (int i = 0; i < 3; ++i) {
            auto item = query<IAIMPPlaylistItem>(output.item.get(), IID_IAIMPPlaylistItem);
            check(item && heard.insert(track_key(item.get())).second && !same_object(item.get(), &zero), "Finite pass visits each positive filename once, including duplicate entries.");
            player.current = item.get();
            if (i == 1) output.custom = {}; // tokenless transition recovery
            stop.OnSelect(item.get(), &output);
            const auto played_before = stop.playing_->pass->played.size();
            stop.OnSelect(item.get(), &output);
            QueueOutput next, repeated;
            check(stop.GetNext(item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &next) == TRUE, "Finite lookahead stays handled.");
            check(stop.playing_->pass->played.size() == played_before, "Preparation, duplicate OnSelect and lookahead do not commit listening history.");
            const auto random = stop.random_;
            stop.GetNext(item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &repeated);
            check(stop.random_ == random && (next.item ? same_object(next.item.get(), repeated.item.get()) : !repeated.item), "Finite repeated probes remain stable, including an empty end.");
            stop.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
            check(stop.active_pass_->played.size() == static_cast<std::size_t>(i + 1), "Only committed starts advance the finite pass.");
            if (i == 2) {
                check(!next.item && !next.custom && stop.monitor_state().next_track.find(L"Playlist complete") != std::wstring::npos, "AIMP stop-at-end produces a handled null queue and completion message.");
                check(stop.monitor_state().decision.find(L"Already played: 3 / 3 | Remaining: 0") != std::wstring::npos, "Finite progress is visible.");
            }
            output.item = next.item; output.custom = next.custom;
        }
        // End settings may change after a stop decision was prefetched.
        messages.end_action = 1;
        stop.CoreMessage(AIMP_MSG_EVENT_PROPERTY_VALUE, AIMP_MSG_PROPERTY_ACTION_ON_END_OF_PLAYLIST, nullptr, &result);
        stop.GetNext(player.current, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        auto next_pass = occurrence_from(&output);
        check(next_pass && next_pass->pass != stop.active_pass_ && next_pass->pass->played.empty(), "AIMP repeat starts a fresh speculative pass without resetting the current one.");
        const auto repeat_random = stop.random_;
        QueueOutput same_boundary;
        stop.GetNext(player.current, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &same_boundary);
        check(stop.random_ == repeat_random && same_object(output.item.get(), same_boundary.item.get()), "Repeat boundary is cached without extra draws.");
        const auto original_next = output.item;
        stop.reroll_next();
        stop.GetNext(player.current, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        check(output.item && !same_object(output.item.get(), original_next.get()), "Reroll at a repeat boundary replaces the next pass's first choice.");
        heard.clear();
        for (int i = 0; i < 30; ++i) {
            if (i % 3 == 0) heard.clear();
            auto item = query<IAIMPPlaylistItem>(output.item.get(), IID_IAIMPPlaylistItem);
            check(item && heard.insert(track_key(item.get())).second, "Each repeated pass remains finite and contains no duplicate filename.");
            player.current = item.get(); stop.OnSelect(item.get(), &output);
            stop.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
            QueueOutput upcoming, future;
            stop.GetNext(item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &upcoming);
            stop.GetNext(upcoming.item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &future);
            check(future.item && !same_object(future.item.get(), &zero), "Multi-track lookahead across a repeat boundary excludes zero.");
            output.item = upcoming.item; output.custom = upcoming.custom;
        }
        player.state = AIMP_PLAYER_STATE_STOPPED;
        stop.CoreMessage(AIMP_MSG_EVENT_PLAYER_STATE, 0, nullptr, &result);
        check(!stop.active_pass_ && !stop.playing_, "Stopping clears the finite pass for a fresh start.");
        stop.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &output);
        check(static_cast<bool>(output.item), "Restart after stop has a fresh weighted order.");
        stop.Finalize(); player.state = AIMP_PLAYER_STATE_PLAYING;

        // Next playlist follows loaded order, skips empty/excluded lists, and wraps.
        Track target_first(L"finite-target-A.flac"), target_second(L"finite-target-B.flac");
        EmptyPlaylist empty, excluded, target;
        Track only_zero(L"finite-target-zero.flac"); only_zero.owner = &excluded; excluded.items = {&only_zero};
        target_first.owner = target_second.owner = &target; target_second.index = 1; target.items = {&target_first, &target_second};
        manager.loaded = {&target, &playlist, &empty, &excluded};
        messages.end_action = 0;
        Plugin move; setup(move); move.weights_.set("finite-target-zero.flac", 0);
        QueueOutput manual;
        player.current = &first; move.OnSelect(&first, &manual); move.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        move.active_pass_->played = {"finite-A.flac", "finite-B.flac", "finite-C.flac"};
        move.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        auto moved = occurrence_from(&output);
        check(moved && same_object(moved->playlist.get(), &target) && move.active_pass_ != moved->pass, "Next-playlist action skips excluded lists, wraps, and does not commit a speculative switch.");
        player.current = moved->item.get(); move.OnSelect(moved->item.get(), &output); move.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        check(move.active_pass_ == moved->pass && move.active_pass_->played.size() == 1, "Actual next-playlist playback commits its new finite pass.");
        move.GetNext(moved->item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        check(output.item && !same_object(output.item.get(), moved->item.get()), "The destination playlist uses weighted selection without repeats.");
        move.Finalize();
        for (const auto action : {0, 1, 2}) {
            Plugin none; setup(none); messages.end_action = action;
            none.weights_.set("finite-A.flac", 0); none.weights_.set("finite-B.flac", 0); none.weights_.set("finite-C.flac", 0);
            manager.loaded = {&playlist};
            none.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &output);
            check(!output.item && !output.custom, "An all-zero finite playlist never loops or delegates to unweighted shuffle.");
            none.Finalize();
        }
        Plugin missing; setup(missing); messages.end_action_supported = false;
        player.current = &first; missing.OnSelect(&first, &manual); missing.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        missing.active_pass_->played = {"finite-A.flac", "finite-B.flac", "finite-C.flac"};
        missing.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        check(!output.item, "Unavailable host end settings safely stop."); missing.Finalize(); messages.end_action_supported = true;

        Plugin edits; setup(edits); messages.end_action = 2; player.current = &first;
        edits.OnSelect(&first, &manual); edits.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        edits.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        edits.weights_.set("finite-A.flac", 100000); edits.weights_.set("finite-C.flac", 0); edits.changed();
        edits.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        check(track_key(query<IAIMPPlaylistItem>(output.item.get(), IID_IAIMPPlaylistItem).get()) == "finite-B.flac", "Weight edits preserve finite progress and remove new zero exclusions.");
        auto edited_next = query<IAIMPPlaylistItem>(output.item.get(), IID_IAIMPPlaylistItem);
        player.current = edited_next.get(); edits.OnSelect(edited_next.get(), &output); edits.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        edits.weights_.set("finite-zero.flac", 25); edits.changed();
        edits.GetNext(edited_next.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        check(same_object(output.item.get(), &zero), "A newly positive unplayed track can join the remaining pass.");
        QueueOutput previous;
        edits.GetPrev(edited_next.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &previous);
        check(same_object(previous.item.get(), &first), "Finite mode retains explicit Previous navigation.");
        player.current = &first; edits.OnSelect(&first, &previous); edits.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        check(edits.active_pass_->played.size() == 2, "Previous playback does not duplicate the finite completion count.");
        edits.Finalize();

        Plugin single; setup(single); messages.end_action = 1;
        single.weights_.set("finite-B.flac", 0); single.weights_.set("finite-C.flac", 0);
        player.current = &first; single.OnSelect(&first, &manual); single.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        for (int repeat = 0; repeat < 8; ++repeat) {
            const auto old_pass = single.active_pass_;
            single.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
            check(same_object(output.item.get(), &first) && occurrence_from(&output)->pass != old_pass, "Single-positive repeat is a fresh one-track pass.");
            single.OnSelect(&first, &output); single.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        }
        single.Finalize();

        // Deep lookahead must outlive the bounded 256-node playback history.
        Plugin deep; setup(deep); messages.end_action = 2;
        EmptyPlaylist large; std::vector<std::unique_ptr<Track>> tracks;
        for (int i = 0; i < 320; ++i) {
            auto item = std::make_unique<Track>(L"finite-long-" + std::to_wstring(i) + L".flac");
            item->owner = &large; item->index = i; large.items.push_back(item.get()); tracks.push_back(std::move(item));
        }
        deep.GetNext(&large, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &output); heard.clear();
        for (int i = 0; i < 320; ++i) {
            auto item = query<IAIMPPlaylistItem>(output.item.get(), IID_IAIMPPlaylistItem);
            check(item && heard.insert(track_key(item.get())).second, "Deep speculative finite pass never repeats after history eviction.");
            deep.GetNext(item.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output);
        }
        check(!output.item && !deep.active_pass_, "Long speculative pass ends without committing tracks."); deep.Finalize();

        Plugin settings; setup(settings); settings.finite_ = false; player.current = &first;
        settings.preferences_file_ = std::filesystem::current_path() / L"finite-prefs.ini";
        module = GetModuleHandleW(nullptr); settings.open_monitor();
        settings.OnSelect(&first, &manual); settings.CoreMessage(AIMP_MSG_EVENT_STREAM_START, 0, nullptr, &result);
        const auto panel = settings.monitor_.handle();
        check(IsDlgButtonChecked(panel, IDC_FINITE_PLAYLIST) == BST_UNCHECKED, "Finite mode is disabled by default.");
        CheckDlgButton(panel, IDC_FINITE_PLAYLIST, BST_CHECKED);
        SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_FINITE_PLAYLIST, BN_CLICKED), 0);
        check(settings.finite_ && !IsWindowEnabled(GetDlgItem(panel, IDC_COOLDOWN)), "Finite checkbox applies immediately and pauses cooldown controls.");
        settings.GetNext(&first, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, &output); settings.update_monitor();
        Plugin restored; restored.preferences_file_ = settings.preferences_file_; restored.load_preferences();
        check(restored.finite_ && restored.cooldown_ == 10000, "Finite preference survives restart and preserves the saved cooldown.");
        settings.monitor_.restore_size(1, 400);
        RECT narrow_client{}; GetClientRect(panel, &narrow_client);
        RECT prior{};
        for (const int id : {IDC_ENABLED, IDC_PINNED, IDC_ATTACHED, IDC_FINITE_PLAYLIST}) {
            RECT box{}; GetWindowRect(GetDlgItem(panel, id), &box); MapWindowPoints(nullptr, panel, reinterpret_cast<POINT*>(&box), 2);
            check(box.left >= 0 && box.right <= narrow_client.right && (prior.right == 0 || box.top >= prior.bottom || box.left >= prior.right), "All four checkbox labels reflow without clipping or overlap.");
            prior = box;
        }
        settings.monitor_.restore_size(540, 680); capture_preview(panel, L"monitor-finite.bmp");
        CheckDlgButton(panel, IDC_FINITE_PLAYLIST, BST_UNCHECKED);
        SendMessageW(panel, WM_COMMAND, MAKEWPARAM(IDC_FINITE_PLAYLIST, BN_CLICKED), 0);
        check(!settings.finite_ && IsWindowEnabled(GetDlgItem(panel, IDC_COOLDOWN)), "Turning finite mode off restores cooldown controls.");
        settings.Finalize(); std::filesystem::remove(settings.preferences_file_);
    }
    static void run() {
        Manager manager;
        Messages messages;
        EmptyPlaylist playlist;
        QueueOutput output;
        Plugin plugin;
        plugin.playlists_ = query<IAIMPServicePlaylistManager>(&manager, IID_IAIMPServicePlaylistManager);
        plugin.messages_ = query<IAIMPServiceMessageDispatcher>(&messages, IID_IAIMPServiceMessageDispatcher);
        check(plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &output) == FALSE, "Shuffle off must return boolean false, not E_NOTIMPL.");
        check(plugin.GetPrev(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &output) == FALSE, "Previous with shuffle off.");
        messages.shuffle = TRUE;
        default_weight_checks(manager, messages);
        mouse_input_checks();
        sorting_checks(manager);
        quick_controls_checks(manager, messages);
        storage_checks(manager, messages);
        bulk_editor_checks(manager, messages);
        panel_window_checks(manager, messages);
        repaint_checks();
        update_settings_checks();
        update_install_checks();
        finite_checks();
        cooldown_checks(manager, messages);
        cursor_and_duplicate_checks(manager, messages);
        transition_trace_checks(manager, messages);
        transition_trace_checks(manager, messages, 1);
        transition_trace_checks(manager, messages, 2);
        transition_trace_checks(manager, messages, 2, true);
        prefetched_display_checks(manager, messages);
        repeat_checks(manager, messages);
        output.SetValueAsObject(AIMP_PLAYBACKQUEUEITEM_PROPID_PLAYLISTITEM, &playlist);
        check(plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &output) == TRUE, "Empty queue must remain active, not fall back to AIMP.");
        check(!output.item && !output.custom, "Empty queue must clear stale output and stop playback.");
        check(plugin.GetPrev(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, &output) == TRUE, "Previous without history must remain handled.");
        check(!output.item, "Missing history must return no item.");
        check(plugin.GetNext(&playlist, AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_BEGINNING, nullptr) == FALSE, "Null output.");
        check(plugin.Finalize() == S_OK && plugin.Finalize() == S_OK, "Finalization must be repeatable.");
        monitor_checks(manager, messages);
        startup_checks(manager, messages);
    }
};
}
int main(int argc, char** argv) {
    try {
        INITCOMMONCONTROLSEX controls{sizeof(controls), ICC_LISTVIEW_CLASSES};
        check(InitCommonControlsEx(&controls) != FALSE, "Initialize native report-view controls.");
        PluginChecks::run();
        check(argc == 2, "Expected plugin DLL path.");
        HMODULE library = LoadLibraryA(argv[1]);
        check(library != nullptr, "Could not load plugin DLL.");
        auto get_header = reinterpret_cast<TAIMPPluginGetHeaderProc>(GetProcAddress(library, "AIMPPluginGetHeader"));
        check(get_header != nullptr, "Missing undecorated plugin entry point.");
        IAIMPPlugin* header = nullptr;
        check(get_header(&header) == S_OK && header, "Could not create plugin header.");
        check(header->InfoGetCategories() == AIMP_PLUGIN_CATEGORY_ADDONS, "Plugin category.");
        auto extension = query<IAIMPExtensionPlaybackQueue>(header, IID_IAIMPExtensionPlaybackQueue);
        auto settings = query<IAIMPExternalSettingsDialog>(header, IID_IAIMPExternalSettingsDialog);
        auto hook = query<IAIMPMessageHook>(header, IID_IAIMPMessageHook);
        check(extension && settings && hook, "Missing plugin interfaces.");
        extension = {}; settings = {}; hook = {};
        check(header->Finalize() == S_OK, "DLL finalization.");
        check(header->Release() == 0, "Plugin reference leak.");
        check(FreeLibrary(library) != FALSE, "Could not unload plugin DLL.");
        std::cout << "Adapter queue contracts and DLL load/export/interfaces/unload passed.\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
