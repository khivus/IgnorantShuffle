#include <windows.h>
#include <commdlg.h>
#include <commctrl.h>
#include <shellapi.h>
#include <shobjidl.h>
#include <apiPlugin.h>
#include <apiPlayer.h>
#include <apiMessages.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <deque>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <limits>
#include <locale>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <utility>
#include "weights.h"
#include "weight_storage.h"
#include "track_table.h"
#include "resource.h"
#include "monitor.h"
#include "logging.h"
#include "build_config.h"
#include "update_client.h"
#include "update_install.h"

namespace {
HINSTANCE module;
template<class T> class Ref {
    T* value_ = nullptr;
public:
    Ref() = default;
    Ref(const Ref& other) : value_(other.value_) { if (value_) value_->AddRef(); }
    Ref(Ref&& other) noexcept : value_(std::exchange(other.value_, nullptr)) {}
    ~Ref() { if (value_) value_->Release(); }
    Ref& operator=(Ref other) noexcept { std::swap(value_, other.value_); return *this; }
    T* get() const { return value_; }
    T* operator->() const { return value_; }
    explicit operator bool() const { return value_ != nullptr; }
    T** put() { if (value_) value_->Release(); value_ = nullptr; return &value_; }
    void** out() { return reinterpret_cast<void**>(put()); }
};
template<class T> Ref<T> query(IUnknown* source, REFIID iid) {
    Ref<T> result;
    if (source) source->QueryInterface(iid, result.out());
    return result;
}
Ref<IAIMPString> string_property(IAIMPPropertyList* source, int property) {
    Ref<IAIMPString> value;
    if (source) source->GetValueAsObject(property, IID_IAIMPString, value.out());
    return value;
}
std::wstring wide(IAIMPString* value) {
    return value ? std::wstring(value->GetData(), value->GetLength()) : std::wstring();
}
std::string utf8(const std::wstring& text) {
    if (text.empty()) return {};
    const auto length = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    if (!length) throw std::runtime_error("Invalid track filename.");
    std::string result(length, '\0');
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), result.data(), length, nullptr, nullptr);
    return result;
}
std::wstring from_utf8(const std::string& text) {
    if (text.empty()) return {};
    const auto length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr, 0);
    if (!length) throw std::runtime_error("Invalid UTF-8 in weights file.");
    std::wstring result(length, L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), result.data(), length);
    return result;
}
std::string track_key(IAIMPPlaylistItem* item) {
    return utf8(wide(string_property(item, AIMP_PLAYLISTITEM_PROPID_FILENAME).get()));
}
bool same_object(IUnknown* first, IUnknown* second) {
    const auto a = query<IUnknown>(first, IID_IUnknown);
    const auto b = query<IUnknown>(second, IID_IUnknown);
    return a && b && a.get() == b.get();
}

// The custom queue value identifies a playback occurrence, not just a filename.
// A repeat of the same track is a fresh occurrence with its own weighted draw.
const GUID iid_queue_token = {0x62aa2619, 0x77ae, 0x4570, {0xb4, 0xae, 0x1e, 0x06, 0x91, 0x9c, 0xaa, 0x01}};
struct FinitePass {
    unsigned long long generation = 0;
    Ref<IAIMPPlaylist> playlist;
    std::set<std::string> played;
};
struct Occurrence {
    unsigned long long id = 0;
    Ref<IAIMPPlaylistItem> item;
    Ref<IAIMPPlaylist> playlist;
    std::weak_ptr<Occurrence> previous;
    std::weak_ptr<Occurrence> next;
    ignorant::Selection decision;
    ignorant::Selection incoming_decision;
    ignorant::CooldownPool cooldown;
    bool decision_ready = false;
    bool started = false;
    bool planned = false;
    bool rerolled = false;
    std::shared_ptr<FinitePass> pass;
    std::shared_ptr<std::vector<std::string>> pass_path;
    std::size_t pass_position = 0;
    ignorant::FinitePool finite_pool;
    bool pass_end = false;
    int end_action = -1;
};
class QueueToken final : public IUnknown {
    std::atomic<ULONG> references_{1};
public:
    std::shared_ptr<Occurrence> value;
    bool history_navigation;
    explicit QueueToken(std::shared_ptr<Occurrence> occurrence, bool history = false)
        : value(std::move(occurrence)), history_navigation(history) {}
    HRESULT WINAPI QueryInterface(REFIID iid, void** output) override {
        if (!output) return E_POINTER;
        *output = nullptr;
        if (iid != IID_IUnknown && iid != iid_queue_token) return E_NOINTERFACE;
        *output = static_cast<IUnknown*>(this);
        AddRef();
        return S_OK;
    }
    ULONG WINAPI AddRef() override { return ++references_; }
    ULONG WINAPI Release() override { const auto count = --references_; if (!count) delete this; return count; }
};
std::shared_ptr<Occurrence> occurrence_from(IUnknown* current, bool* history_navigation = nullptr) {
    if (history_navigation) *history_navigation = false;
    auto token = query<IUnknown>(current, iid_queue_token);
    if (!token) {
        auto entry = query<IAIMPPlaybackQueueItem>(current, IID_IAIMPPlaybackQueueItem);
        if (entry) {
            // CUSTOM is documented as IUnknown. Ask that object for our private
            // interface separately; the host property getter need not forward it.
            Ref<IUnknown> custom;
            entry->GetValueAsObject(AIMP_PLAYBACKQUEUEITEM_PROPID_CUSTOM, IID_IUnknown, custom.out());
            token = query<IUnknown>(custom.get(), iid_queue_token);
        }
    }
    if (token) {
        const auto* context = static_cast<QueueToken*>(token.get());
        if (history_navigation) *history_navigation = context->history_navigation;
        return context->value;
    }
    return {};
}

class Plugin final : public IAIMPPlugin, public IAIMPExtensionPlaybackQueue, public IAIMPExternalSettingsDialog, public IAIMPMessageHook {
    friend struct PluginChecks;
    std::atomic<ULONG> references_{1};
    Ref<IAIMPCore> core_;
    Ref<IAIMPServicePlaylistManager> playlists_;
    Ref<IAIMPServiceMessageDispatcher> messages_;
    Ref<IAIMPServicePlaybackQueue2> queue_;
    Ref<IAIMPServicePlayer> player_;
    std::recursive_mutex mutex_;
    ignorant::Weights weights_;
    ignorant::WeightStorage storage_;
    std::filesystem::path music_folder_;
    bool shared_global_ = true, folder_local_ = false, playlist_local_ = false;
    ignorant::WeightScope edit_scope_ = ignorant::WeightScope::Global;
    std::wstring storage_error_;
    struct ContextCache { Ref<IAIMPPlaylist> playlist; int count; ignorant::WeightContext value; };
    std::deque<ContextCache> weight_contexts_;
    Ref<IAIMPPlaylist> editor_playlist_;
    HWND storage_dialog_ = nullptr;
    TrackTable track_table_;
    bool editor_selection_pending_ = false;
    std::filesystem::path weights_file_;
    std::filesystem::path preferences_file_;
    std::filesystem::path log_file_;
    ignorant::PluginLog log_;
    unsigned long long next_occurrence_id_ = 1;
    std::string last_display_;
    bool enabled_ = true;
    bool finite_ = false;
    unsigned long long finite_generation_ = 1;
    std::shared_ptr<FinitePass> active_pass_;
    bool pinned_ = true;
    bool side_panel_ = false;
    bool hooked_ = false;
    bool shutting_down_ = false;
    bool queue_refresh_pending_ = false;
    bool awaiting_stream_start_ = false;
    int cooldown_ = 0;
    std::deque<std::string> recent_tracks_;
    Ref<IAIMPPlaylistItem> prepared_track_;
    ignorant::CooldownPool cooldown_preview_;
    bool cooldown_preview_ready_ = false;
    std::wstring preferences_error_;
    MonitorWindow monitor_;
    ignorant::UpdateClient updater_;
    ignorant::UpdateLauncher update_launcher_;
    std::string update_repository_ = ignorant::default_update_repository;
    bool auto_updates_ = true, install_requested_ = false;
    ULONGLONG next_update_check_ = 0;
    HWND update_dialog_ = nullptr;
    std::string last_update_status_;
    std::mt19937_64 random_{std::random_device{}()};
    bool registered_ = false;
    HWND dialog_ = nullptr;
    std::vector<std::string> visible_tracks_;
    // Bounded occurrence history also keeps prefetched answers stable.
    std::deque<std::shared_ptr<Occurrence>> history_;
    std::shared_ptr<Occurrence> playing_;
    std::shared_ptr<Occurrence> audible_;
    std::shared_ptr<Occurrence> cursor_source_;
    std::shared_ptr<Occurrence> beginning_;

    static std::string describe_item(IUnknown* object) {
        try {
            std::ostringstream text;
            text << "object=" << static_cast<void*>(object);
            auto item = query<IAIMPPlaylistItem>(object, IID_IAIMPPlaylistItem);
            if (item) text << " track=\"" << track_key(item.get()) << '"';
            return text.str();
        } catch (...) { return "object=<unavailable>"; }
    }
    static std::string context_id(const std::shared_ptr<Occurrence>& occurrence) {
        return occurrence ? std::to_string(occurrence->id) : "none";
    }

    void save_preferences() {
        if (preferences_file_.empty()) return;
        bool success = WritePrivateProfileStringW(L"Monitor", L"Enabled", enabled_ ? L"1" : L"0", preferences_file_.c_str()) != FALSE;
        success = (WritePrivateProfileStringW(L"Updates", L"Automatic", auto_updates_ ? L"1" : L"0", preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Updates", L"Repository", from_utf8(update_repository_).c_str(), preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Monitor", L"Pinned", pinned_ ? L"1" : L"0", preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Monitor", L"Attached", side_panel_ ? L"1" : L"0", preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Monitor", L"CooldownTracks", std::to_wstring(cooldown_).c_str(), preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Shuffle", L"FinitePlaylist", finite_ ? L"1" : L"0", preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Shuffle", L"DefaultWeight", weight_text(weights_.fallback()).c_str(), preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Storage", L"MusicFolder", music_folder_.c_str(), preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Storage", L"SharedGlobal", shared_global_ ? L"1" : L"0", preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Storage", L"FolderLocal", folder_local_ ? L"1" : L"0", preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Storage", L"PlaylistLocal", playlist_local_ ? L"1" : L"0", preferences_file_.c_str()) != FALSE) && success;
        success = (WritePrivateProfileStringW(L"Storage", L"EditScope", std::to_wstring(static_cast<int>(edit_scope_)).c_str(), preferences_file_.c_str()) != FALSE) && success;
        RECT rectangle{};
        if (monitor_.handle()) {
            rectangle = monitor_.saved_bounds();
            success = (WritePrivateProfileStringW(L"Monitor", L"X", std::to_wstring(rectangle.left).c_str(), preferences_file_.c_str()) != FALSE) && success;
            success = (WritePrivateProfileStringW(L"Monitor", L"Y", std::to_wstring(rectangle.top).c_str(), preferences_file_.c_str()) != FALSE) && success;
            success = (WritePrivateProfileStringW(L"Monitor", L"Width", std::to_wstring(rectangle.right - rectangle.left).c_str(), preferences_file_.c_str()) != FALSE) && success;
            success = (WritePrivateProfileStringW(L"Monitor", L"Height", std::to_wstring(rectangle.bottom - rectangle.top).c_str(), preferences_file_.c_str()) != FALSE) && success;
        }
        preferences_error_ = success ? L"" : L"Could not save window preferences; changes apply to this session.";
        if (!success) log_.write("preferences.error", "Could not save preferences.ini");
    }
    void load_preferences() {
        if (preferences_file_.empty()) return;
        auto_updates_ = GetPrivateProfileIntW(L"Updates", L"Automatic", 1, preferences_file_.c_str()) != 0;
        wchar_t repository[512]{};
        GetPrivateProfileStringW(L"Updates", L"Repository", from_utf8(ignorant::default_update_repository).c_str(), repository, 512, preferences_file_.c_str());
        try {
            update_repository_ = ignorant::normalize_repository(utf8(repository));
            // Early placeholder builds may have saved an empty source. Let a
            // later published build supply its default without manual migration.
            if (update_repository_.empty()) update_repository_ = ignorant::normalize_repository(ignorant::default_update_repository);
        }
        catch (const std::exception& error) { update_repository_.clear(); log_.write("updates.source.error", error.what()); }
        enabled_ = GetPrivateProfileIntW(L"Monitor", L"Enabled", 1, preferences_file_.c_str()) != 0;
        pinned_ = GetPrivateProfileIntW(L"Monitor", L"Pinned", 1, preferences_file_.c_str()) != 0;
        side_panel_ = GetPrivateProfileIntW(L"Monitor", L"Attached", 0, preferences_file_.c_str()) != 0;
        cooldown_ = std::clamp(static_cast<int>(GetPrivateProfileIntW(L"Monitor", L"CooldownTracks", 0, preferences_file_.c_str())), 0, 10000);
        finite_ = GetPrivateProfileIntW(L"Shuffle", L"FinitePlaylist", 0, preferences_file_.c_str()) != 0;
        wchar_t fallback[512]{};
        GetPrivateProfileStringW(L"Shuffle", L"DefaultWeight", L"5", fallback, 512, preferences_file_.c_str());
        try { weights_.set_default(parse_custom_weight(fallback)); }
        catch (const std::exception& error) {
            weights_.set_default(ignorant::default_weight);
            preferences_error_ = L"Invalid default weight in preferences.ini; using 5. Change it in Weight storage.";
            log_.write("preferences.default_weight.error", error.what());
        }
        wchar_t folder[32768]{};
        GetPrivateProfileStringW(L"Storage", L"MusicFolder", L"", folder, 32768, preferences_file_.c_str());
        music_folder_ = folder;
        shared_global_ = GetPrivateProfileIntW(L"Storage", L"SharedGlobal", 1, preferences_file_.c_str()) != 0;
        folder_local_ = GetPrivateProfileIntW(L"Storage", L"FolderLocal", 0, preferences_file_.c_str()) != 0;
        playlist_local_ = GetPrivateProfileIntW(L"Storage", L"PlaylistLocal", 0, preferences_file_.c_str()) != 0;
        edit_scope_ = static_cast<ignorant::WeightScope>(std::clamp(static_cast<int>(GetPrivateProfileIntW(L"Storage", L"EditScope", 0, preferences_file_.c_str())), 0, 2));
    }
    static std::wstring scope_label(ignorant::WeightScope scope) {
        return scope == ignorant::WeightScope::Folder ? L"Folder" : scope == ignorant::WeightScope::Playlist ? L"AIMP playlist" : L"Global";
    }
    std::string playlist_name(IAIMPPlaylist* playlist) {
        auto properties = query<IAIMPPropertyList>(playlist, IID_IAIMPPropertyList);
        return utf8(wide(string_property(properties.get(), AIMP_PLAYLIST_PROPID_NAME).get()));
    }
    ignorant::WeightContext context_for(const Ref<IAIMPPlaylist>& playlist, const std::vector<std::string>* known_tracks = nullptr) {
        if (!storage_.enabled() || !playlist || (!storage_.folders_enabled() && !storage_.playlists_enabled())) return {};
        const auto name = playlist_name(playlist.get());
        const auto count = playlist->GetItemCount();
        if (!known_tracks) for (const auto& cached : weight_contexts_)
            if (same_object(cached.playlist.get(), playlist.get()) && cached.count == count && cached.value.playlist == name) return cached.value;
        std::vector<std::string> tracks;
        if (!known_tracks) for (int i = 0; i < count; ++i) {
            Ref<IAIMPPlaylistItem> item;
            if (SUCCEEDED(playlist->GetItem(i, IID_IAIMPPlaylistItem, item.out())) && item) {
                const auto key = track_key(item.get()); if (!key.empty()) tracks.push_back(key);
            }
        }
        auto value = storage_.context(known_tracks ? *known_tracks : tracks, name);
        for (auto it = weight_contexts_.begin(); it != weight_contexts_.end();) {
            if (same_object(it->playlist.get(), playlist.get())) it = weight_contexts_.erase(it); else ++it;
        }
        weight_contexts_.push_back({playlist, count, value});
        if (weight_contexts_.size() > 32) weight_contexts_.pop_front();
        return value;
    }
    ignorant::WeightScope scope_for(const ignorant::WeightContext& context) const {
        return storage_.available(edit_scope_, context) ? edit_scope_ : ignorant::WeightScope::Global;
    }
    ignorant::WeightValue effective_weight(const std::string& track, const ignorant::WeightContext& context,
        ignorant::WeightScope through = ignorant::WeightScope::Playlist) {
        if (!storage_error_.empty()) throw std::runtime_error(utf8(storage_error_));
        try { return storage_.resolve(weights_, track, context, through); }
        catch (const std::exception& error) {
            storage_error_ = from_utf8(error.what());
            log_.write("storage.error", error.what()); request_monitor_update(); throw;
        }
    }
    void set_storage(const std::filesystem::path& folder, bool global, bool local_folder, bool local_playlist) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        ignorant::WeightStorage next;
        // Shared global is preferred by default; until a root is chosen, keep
        // using profile weights. Explicit saved opt-outs remain respected.
        next.configure(folder, global && !folder.empty(), local_folder, local_playlist);
        storage_ = std::move(next); music_folder_ = folder;
        shared_global_ = global; folder_local_ = local_folder; playlist_local_ = local_playlist;
        storage_error_.clear(); weight_contexts_.clear();
        save_preferences(); changed();
        refresh_cooldown_preview(); update_monitor();
        log_.write("storage.configure", "global=" + std::to_string(global) + " folder=" + std::to_string(local_folder) + " playlist=" + std::to_string(local_playlist));
    }
    void set_track_weight(const std::string& track, const ignorant::WeightContext& context, int value) {
        if (value < 0 || value > 10) throw std::runtime_error("Enter a weight from 0 to 10.");
        const auto scope = scope_for(context);
        if (storage_.target(scope, track, context)) {
            storage_.set(scope, track, context, value);
            changed(); refresh_cooldown_preview();
        } else {
            auto next = weights_; next.set_from_editor(track, value); commit(std::move(next));
        }
        log_.write("weights.current", "scope=" + std::to_string(static_cast<int>(scope)) + " weight=" + std::to_string(value) + " track=" + track);
    }
    void reset_finite_progress() { active_pass_.reset(); ++finite_generation_; }
    void set_finite(bool enabled) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        if (finite_ == enabled) return;
        finite_ = enabled; reset_finite_progress();
        save_preferences(); changed(); refresh_cooldown_preview(); update_monitor();
        log_.write("shuffle.finite", "enabled=" + std::to_string(enabled));
    }
    std::shared_ptr<FinitePass> new_pass(const Ref<IAIMPPlaylist>& playlist) {
        auto pass = std::make_shared<FinitePass>();
        pass->generation = finite_generation_; pass->playlist = playlist; return pass;
    }
    void ensure_pass(const std::shared_ptr<Occurrence>& source, const Ref<IAIMPPlaylist>& playlist) {
        if (!source->pass || source->pass->generation != finite_generation_ || !same_object(source->pass->playlist.get(), playlist.get())) {
            source->pass = source->item && active_pass_ && active_pass_->generation == finite_generation_ && same_object(active_pass_->playlist.get(), playlist.get())
                ? active_pass_ : new_pass(playlist);
            source->pass_path.reset();
        }
        if (!source->pass_path) {
            source->pass_path = std::make_shared<std::vector<std::string>>();
            if (source->item) source->pass_path->push_back(track_key(source->item.get()));
            source->pass_position = source->pass_path->size();
        }
    }
    std::vector<std::string> projected_pass(const std::shared_ptr<Occurrence>& source) {
        std::vector<std::string> played(source->pass->played.begin(), source->pass->played.end());
        // Keep the projected prefix independently of bounded occurrence history.
        // AIMP may probe more than 256 future items before any stream starts.
        played.insert(played.end(), source->pass_path->begin(), source->pass_path->begin() + source->pass_position);
        return played;
    }
    void commit_finite_start(const Ref<IAIMPPlaylistItem>& item) {
        if (!finite_ || !enabled_ || !shuffle_enabled() || !item) return;
        const auto playlist = playlist_for(item.get());
        if (!playlist) return;
        if (audible_) { ensure_pass(audible_, playlist); active_pass_ = audible_->pass; }
        else if (!active_pass_ || !same_object(active_pass_->playlist.get(), playlist.get())) active_pass_ = new_pass(playlist);
        const auto key = track_key(item.get());
        if (!key.empty()) active_pass_->played.insert(key);
        log_.write("shuffle.finite.played", "count=" + std::to_string(active_pass_->played.size()) + " track=" + key);
    }
    void set_cooldown(int tracks) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        if (tracks < 0 || tracks > 10000) throw std::runtime_error("Cooldown must be from 0 to 10000 tracks.");
        cooldown_ = tracks;
        save_preferences();
        refresh_cooldown_preview();
        log_.write("cooldown.setting", "tracks=" + std::to_string(cooldown_));
        changed();
        update_monitor();
    }
    // Only confirmed stream starts advance the real listening history. Future
    // queue requests project their pending predecessors without committing them.
    std::vector<std::string> projected_recent(const std::shared_ptr<Occurrence>& source) {
        std::vector<std::string> recent(recent_tracks_.begin(), recent_tracks_.end()), pending;
        auto node = source;
        for (std::size_t depth = 0; node && node != audible_ && depth < 256; ++depth) {
            if (!node->item || !node->planned) break;
            pending.push_back(track_key(node->item.get()));
            if (node->started) break; // A prepared/manual/Previous playback boundary.
            node = node->previous.lock();
        }
        recent.insert(recent.end(), pending.rbegin(), pending.rend());
        return recent;
    }
    void refresh_cooldown_preview() {
        if (finite_) { cooldown_preview_ready_ = false; return; }
        auto current = current_track();
        auto playlist = playlist_for(current.get());
        cooldown_preview_ready_ = false;
        if (!playlist) return;
        std::vector<std::string> keys;
        std::vector<double> weights;
        for (int i = 0; i < playlist->GetItemCount(); ++i) {
            Ref<IAIMPPlaylistItem> item;
            if (FAILED(playlist->GetItem(i, IID_IAIMPPlaylistItem, item.out())) || !item) continue;
            const auto key = track_key(item.get());
            if (key.empty()) continue;
            keys.push_back(key);
        }
        const auto context = context_for(playlist, &keys);
        for (const auto& key : keys) weights.push_back(effective_weight(key, context).weight);
        cooldown_preview_ = ignorant::cooldown_pool(keys, weights, projected_recent(audible_), cooldown_);
        cooldown_preview_ready_ = true;
    }
    std::wstring cooldown_status(const ignorant::CooldownPool* pool) const {
        if (finite_) return L"Cooldown paused - each positive-weight filename plays once per pass.";
        if (!cooldown_) return L"Cooldown off - tracks can repeat immediately.";
        if (!pool) return L"Cooldown: " + std::to_wstring(cooldown_) + L" tracks. Waiting for a playlist.";
        if (pool->fallback) return L"Playlist too short: " + std::to_wstring(pool->distinct_positive) +
            L" positive-weight tracks; need " + std::to_wstring(cooldown_ + 1) + L". Using weighted shuffle without cooldown.";
        if (!pool->distinct_positive) return L"No positive-weight tracks. Weight 0 stays excluded.";
        return L"Cooldown: " + std::to_wstring(cooldown_) + L" other tracks before a repeat. Cooling down: " + std::to_wstring(pool->blocked) + L".";
    }
    void set_enabled(bool enabled) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        if (enabled_ == enabled) return;
        enabled_ = enabled;
        reset_finite_progress();
        log_.write("plugin.toggle", enabled ? "enabled=1" : "enabled=0");
        history_.clear(); playing_.reset(); audible_.reset(); cursor_source_.reset(); beginning_.reset();
        queue_refresh_pending_ = false;
        awaiting_stream_start_ = false;
        save_preferences();
        changed();
        update_monitor();
    }
    void request_monitor_update() {
        if (monitor_.handle()) PostMessageW(monitor_.handle(), WM_APP + 1, 0, 0);
    }
    static std::wstring track_label(IAIMPPlaylistItem* item) {
        auto label = wide(string_property(item, AIMP_PLAYLISTITEM_PROPID_DISPLAYTEXT).get());
        if (label.empty()) label = wide(string_property(item, AIMP_PLAYLISTITEM_PROPID_FILENAME).get());
        return label.empty() ? L"Unknown track" : label;
    }
    Ref<IAIMPPlaylistItem> current_track() {
        Ref<IAIMPPlaylistItem> current;
        if (player_) {
            if (player_->GetState() != AIMP_PLAYER_STATE_STOPPED) player_->GetPlaylistItem(current.put());
        } else if (audible_) current = audible_->item;
        else if (playing_) current = playing_->item;
        return current;
    }
    void apply_current_weight(int weight) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        auto current = current_track();
        if (!current) throw std::runtime_error("No track is currently playing.");
        const auto key = track_key(current.get());
        const auto context = context_for(playlist_for(current.get()));
        if (storage_.target(scope_for(context), key, context) || weights_.get(key) != weight) set_track_weight(key, context, weight);
        update_monitor();
    }
    void reroll_next() {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        auto current = current_track();
        if (!current || !enabled_ || !shuffle_enabled() || awaiting_stream_start_) return;
        auto playlist = playlist_for(current.get());
        if (!playlist) return;
        auto source = source_for(current.get(), AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM, playlist);
        cursor_source_ = source;
        auto previous_choice = source->next.lock();
        log_.write("queue.reroll.request", "source=" + context_id(source) + " previous_choice=" + context_id(previous_choice));
        // Prepare the replacement before notifying AIMP, so its first rebuild
        // request sees this same choice instead of drawing a second one.
        changed(false, false);
        choose_next(source, playlist, previous_choice ? previous_choice->item : Ref<IAIMPPlaylistItem>{}, true);
        if (queue_) queue_->NotifyChanged(static_cast<IAIMPExtensionPlaybackQueue*>(this));
        request_monitor_update();
        update_monitor();
    }
    MonitorState monitor_state() {
        MonitorState state;
        state.enabled = enabled_;
        state.pinned = pinned_;
        state.attached = side_panel_;
        state.finite = finite_;
        state.cooldown = cooldown_;
        state.cooldown_status = cooldown_status(cooldown_preview_ready_ ? &cooldown_preview_ : nullptr);
        const auto shuffle = shuffle_enabled();
        state.mode = !enabled_ ? L"Off - AIMP controls track order." : shuffle ? L"Active - weighted shuffle chooses the next track." : L"On, waiting - turn AIMP Shuffle on to activate.";
        if (finite_ && enabled_ && shuffle) state.mode = L"Finite playlist - higher weights are more likely to play earlier.";
        state.notice = preferences_error_.empty() ? L"AIMP's manual queue and repeat settings can take priority." : preferences_error_;
        if (!log_.error().empty()) state.notice = L"Logging unavailable: " + from_utf8(log_.error());
        auto current = current_track();
        state.can_set_weight = static_cast<bool>(current);
        state.can_reroll = enabled_ && shuffle && current && !awaiting_stream_start_;
        state.current_track = current ? track_label(current.get()) : L"Nothing playing";
        if (current) state.current_key = wide(string_property(current.get(), AIMP_PLAYLISTITEM_PROPID_FILENAME).get());
        std::wostringstream weight;
        if (current) {
            try {
                const auto key = track_key(current.get());
                const auto context = context_for(playlist_for(current.get()));
                const auto scope = scope_for(context);
                const auto actual = effective_weight(key, context);
                const auto editing = effective_weight(key, context, scope);
                state.weight_scopes = {0};
                if (storage_.available(ignorant::WeightScope::Folder, context)) state.weight_scopes.push_back(1);
                if (storage_.available(ignorant::WeightScope::Playlist, context)) state.weight_scopes.push_back(2);
                state.weight_scope = static_cast<int>(scope);
                state.can_clear_override = scope != ignorant::WeightScope::Global && storage_.has_override(scope, key, context);
                weight << L"Current weight: " << std::setprecision(8) << actual.weight;
                if (storage_.enabled()) weight << L" (" << scope_label(actual.scope) << L")";
                if (editing.weight <= 10 && editing.weight == std::floor(editing.weight)) state.selected_weight = static_cast<int>(editing.weight);
                if (actual.weight == 0) weight << L" (excluded)";
                else if (actual.weight > 10) weight << L" (custom)";
                if (scope != actual.scope || editing.weight != actual.weight)
                    weight << L"\r\nEditing " << scope_label(scope) << L": " << editing.weight;
            } catch (const std::exception&) { weight << L"Weight unavailable"; state.can_set_weight = false; }
        } else weight << L"Current weight: -";
        state.current_weight = weight.str();
        if (!storage_error_.empty()) {
            state.notice = L"Shared weights error: " + storage_error_;
            state.next_track = L"Weighted selection stopped - check Weight storage";
            state.decision = L"Fix the shared file or disable shared storage, then Apply / reload.";
            state.can_reroll = false; return state;
        }
        state.next_track = L"Waiting for AIMP to request a track";
        state.decision = L"Each chance is proportional to eligible weight.\r\nWeight 0 is always excluded.";
        if (!enabled_ || !shuffle) {
            state.next_track = L"AIMP controls the next track";
            state.decision = !enabled_ ? L"Enable weighted shuffle to use track weights." : L"Weighted decisions begin when AIMP Shuffle is on.";
            return state;
        }
        // Read the decision for the currently playing occurrence, never the last
        // callback (which may be a second track being prefetched by AIMP).
        auto source = cursor_source_ && current && same_object(cursor_source_->item.get(), current.get()) ? cursor_source_ : nullptr;
        if (!source && audible_ && current && same_object(audible_->item.get(), current.get())) source = audible_;
        if (!source && playing_ && current && same_object(playing_->item.get(), current.get())) source = playing_;
        if (!source && current) {
            for (auto it = history_.rbegin(); it != history_.rend(); ++it)
                if ((*it)->started && same_object((*it)->item.get(), current.get())) { source = *it; break; }
        }
        if (!current) {
            if (beginning_ && beginning_->decision_ready) source = beginning_;
            else if (playing_ && playing_->decision_ready && !playing_->decision.index) source = playing_;
        }
        if (!source || !source->decision_ready) return state;
        state.cooldown_status = cooldown_status(&source->cooldown);
        const auto& decision = source->decision;
        auto next = source->next.lock();
        std::wostringstream explanation;
        if (finite_) {
            explanation << L"Already played: " << source->finite_pool.played << L" / " << source->finite_pool.positive
                << L" | Remaining: " << source->finite_pool.positive - source->finite_pool.played << L"\r\n";
            if (source->pass_end) {
                if (next) explanation << (source->end_action == 1 ? L"Playlist complete - repeating with a fresh weighted pass.\r\n" : L"Playlist complete - moving to the next playlist.\r\n");
                else explanation << (source->end_action == 2 ? L"Playlist complete - AIMP is set to stop.\r\n" : L"Playlist complete - no eligible tracks to continue.\r\n");
            }
        }
        if (!decision.index) {
            state.next_track = finite_ && source->pass_end ? L"Playlist complete - playback will stop" : L"No eligible tracks - playback will stop";
            if (finite_) state.can_reroll = false;
            explanation << L"Eligible: 0 | Weight 0 excluded: " << decision.excluded;
            explanation << L"\r\nCooling down: " << source->cooldown.blocked;
        } else if (next) {
            state.next_track = track_label(next->item.get());
            if (source->rerolled) explanation << L"Reroll | ";
            explanation << L"Weight: " << std::setprecision(8) << decision.weight << L" | Chance: " << std::fixed << std::setprecision(2) << decision.probability * 100 << L"%\r\n";
            explanation << L"Eligible: " << decision.eligible << L" | Weight 0 excluded: " << decision.excluded << L"\r\n";
            explanation << L"Cooling down: " << source->cooldown.blocked << L"\r\n";
            explanation << L"Roll " << decision.draw * 100 << L"% fell in [" << decision.lower * 100 << L"%, " << decision.upper * 100 << L"%).";
        } else return state;
        state.decision = explanation.str();
        return state;
    }
    void update_monitor() {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        if (!shutting_down_ && monitor_.handle()) {
            auto state = monitor_state();
            const auto update = updater_.state();
            state.update_status = update.phase == ignorant::UpdatePhase::Available && update.release
                ? L"Update " + from_utf8(update.release->tag) + L" available"
                : update_status(update);
            const auto summary = "playing=" + context_id(playing_) + " current=\"" + utf8(state.current_track) +
                "\" next=\"" + utf8(state.next_track) + "\" cooldown=\"" + utf8(state.cooldown_status) + "\" detail=\"" + utf8(state.decision) + '"';
            if (summary != last_display_) { log_.write("display.change", summary); last_display_ = summary; }
            HWND host = nullptr;
            if (side_panel_ && messages_) messages_->Send(AIMP_MSG_PROPERTY_HWND, AIMP_MPH_MAINFORM, &host);
            if (side_panel_ && (!host || !IsWindow(host)) && storage_error_.empty()) state.notice = L"Waiting for AIMP's main window; the panel is floating for now.";
            monitor_.set_attachment(side_panel_, host);
            monitor_.update(state);
        }
    }
    void flush_queue_refresh() {
        if (!queue_refresh_pending_ || shutting_down_) return;
        queue_refresh_pending_ = false;
        log_.write("queue.refresh.flush", "playing=" + context_id(playing_));
        // New lookahead may already have been calculated after OnSelect. Clear
        // the stale branches without throwing away that fresh decision/display.
        changed(true);
    }
    void check_updates() {
        if (updater_.check(update_repository_)) log_.write("updates.check", "repository=" + update_repository_);
        next_update_check_ = GetTickCount64() + 24ULL * 60 * 60 * 1000;
    }
    std::wstring update_status(const ignorant::UpdateState& state) const {
        if (update_repository_.empty()) return L"Release source not configured";
        if (state.phase == ignorant::UpdatePhase::Unconfigured)
            return auto_updates_ ? L"Waiting to check for updates..." : L"Automatic update checks are off. Use Check now.";
        return from_utf8(state.message);
    }
    void update_tick() {
        if (shutting_down_) return;
        auto state = updater_.state();
        if (auto_updates_ && !update_repository_.empty() && !state.busy() && !install_requested_ && GetTickCount64() >= next_update_check_) {
            check_updates(); state = updater_.state();
        }
        const auto status = utf8(update_status(state));
        if (status != last_update_status_) {
            last_update_status_ = status; log_.write("updates.state", status);
        }
        if (install_requested_ && (state.phase == ignorant::UpdatePhase::Ready || state.phase == ignorant::UpdatePhase::HandedOff)) {
            install_requested_ = false;
            try {
                if (!state.release) throw std::runtime_error("No verified release is ready to install.");
                ignorant::verify_update_package(state.package, *state.release);
                HWND host = nullptr;
                if (messages_) messages_->Send(AIMP_MSG_PROPERTY_HWND, AIMP_MPH_MAINFORM, &host);
                const auto command = ignorant::install_update_package(host, state.package, update_launcher_);
                updater_.handed_off();
                log_.write("updates.install.request", "executable=" + command.executable.u8string() + " package=" + state.package.u8string());
            } catch (const std::exception& error) {
                updater_.install_error(error.what()); log_.write("updates.install.error", error.what());
                MessageBoxA(update_dialog_ ? update_dialog_ : monitor_.handle(), error.what(), "IgnorantShuffle update", MB_OK | MB_ICONERROR);
            }
            state = updater_.state();
        }
        if (state.phase == ignorant::UpdatePhase::Error) install_requested_ = false;
        if (update_dialog_) {
            auto text = update_status(state);
            if (state.phase == ignorant::UpdatePhase::Downloading && state.release)
                text += L"\r\n" + std::to_wstring(state.received / 1024) + L" / " + std::to_wstring(state.release->size / 1024) + L" KB";
            wchar_t previous[2048]{}; GetDlgItemTextW(update_dialog_, IDC_UPDATE_STATUS, previous, 2048);
            if (text != previous) SetDlgItemTextW(update_dialog_, IDC_UPDATE_STATUS, text.c_str());
            EnableWindow(GetDlgItem(update_dialog_, IDC_UPDATE_SAVE), !state.busy());
            EnableWindow(GetDlgItem(update_dialog_, IDC_UPDATE_REPOSITORY), !state.busy());
            EnableWindow(GetDlgItem(update_dialog_, IDC_UPDATE_CHECK), !state.busy() && !update_repository_.empty());
            EnableWindow(GetDlgItem(update_dialog_, IDC_UPDATE_INSTALL), !state.busy() && state.release && state.release->installable());
            const auto install_label = state.phase == ignorant::UpdatePhase::HandedOff || state.phase == ignorant::UpdatePhase::Ready ? L"Open installer again" : L"Download and install";
            wchar_t previous_label[64]{}; GetDlgItemTextW(update_dialog_, IDC_UPDATE_INSTALL, previous_label, 64);
            if (std::wstring(previous_label) != install_label) SetDlgItemTextW(update_dialog_, IDC_UPDATE_INSTALL, install_label);
            EnableWindow(GetDlgItem(update_dialog_, IDC_UPDATE_FOLDER), !state.package.empty());
            EnableWindow(GetDlgItem(update_dialog_, IDC_UPDATE_RELEASE), state.release.has_value());
        }
    }
    static INT_PTR CALLBACK updates_procedure(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
        auto* self = reinterpret_cast<Plugin*>(GetWindowLongPtrW(window, DWLP_USER));
        try {
            if (message == WM_INITDIALOG) {
                self = reinterpret_cast<Plugin*>(lparam); SetWindowLongPtrW(window, DWLP_USER, lparam);
                self->update_dialog_ = window;
                SetDlgItemTextW(window, IDC_UPDATE_VERSION, (L"Windows " + from_utf8(ignorant::build_version) + L" / Core " + from_utf8(ignorant::core_version)).c_str());
                SetDlgItemTextW(window, IDC_UPDATE_REPOSITORY, from_utf8(self->update_repository_).c_str());
                SendDlgItemMessageW(window, IDC_UPDATE_REPOSITORY, EM_SETLIMITTEXT, 500, 0);
                CheckDlgButton(window, IDC_UPDATE_AUTO, self->auto_updates_ ? BST_CHECKED : BST_UNCHECKED);
                SetTimer(window, 1, 250, nullptr); self->update_tick(); return TRUE;
            }
            if (!self) return FALSE;
            if (message == WM_TIMER) { self->update_tick(); return TRUE; }
            if (message == WM_COMMAND) {
                const auto id = LOWORD(wparam);
                if (id == IDC_UPDATE_AUTO) {
                    self->auto_updates_ = IsDlgButtonChecked(window, id) == BST_CHECKED; self->save_preferences();
                } else if (id == IDC_UPDATE_SAVE && !self->updater_.state().busy()) {
                    wchar_t value[512]{}; GetDlgItemTextW(window, IDC_UPDATE_REPOSITORY, value, 512);
                    self->update_repository_ = ignorant::normalize_repository(utf8(value));
                    SetDlgItemTextW(window, IDC_UPDATE_REPOSITORY, from_utf8(self->update_repository_).c_str());
                    self->save_preferences(); self->check_updates();
                } else if (id == IDC_UPDATE_CHECK) self->check_updates();
                else if (id == IDC_UPDATE_INSTALL) {
                    const auto state = self->updater_.state();
                    if (state.phase == ignorant::UpdatePhase::Ready || state.phase == ignorant::UpdatePhase::HandedOff) self->install_requested_ = true;
                    else if (!self->weights_file_.empty()) self->install_requested_ = self->updater_.download(self->weights_file_.parent_path() / L"Updates");
                } else if (id == IDC_UPDATE_FOLDER) {
                    const auto state = self->updater_.state();
                    if (!state.package.empty() && reinterpret_cast<INT_PTR>(ShellExecuteW(window, L"open", state.package.parent_path().c_str(), nullptr, nullptr, SW_SHOWNORMAL)) <= 32)
                        throw std::runtime_error("Could not open the saved package folder.");
                } else if (id == IDC_UPDATE_RELEASE) {
                    const auto state = self->updater_.state();
                    if (state.release && reinterpret_cast<INT_PTR>(ShellExecuteW(window, L"open", from_utf8(state.release->page_url).c_str(), nullptr, nullptr, SW_SHOWNORMAL)) <= 32)
                        throw std::runtime_error("Could not open the release page.");
                } else if (id == IDOK || id == IDCANCEL) {
                    if (self->install_requested_) { self->install_requested_ = false; self->updater_.cancel(); }
                    EndDialog(window, id); return TRUE;
                }
                self->update_tick(); return TRUE;
            }
            if (message == WM_CLOSE) { SendMessageW(window, WM_COMMAND, IDCANCEL, 0); return TRUE; }
            if (message == WM_DESTROY) { KillTimer(window, 1); self->update_dialog_ = nullptr; return TRUE; }
        } catch (const std::exception& error) {
            if (self) self->log_.write("updates.ui.error", error.what());
            MessageBoxA(window, error.what(), "IgnorantShuffle updates", MB_OK | MB_ICONERROR);
        }
        return FALSE;
    }
    void show_updates() {
        if (update_dialog_) { SetForegroundWindow(update_dialog_); return; }
        if (DialogBoxParamW(module, MAKEINTRESOURCEW(IDD_UPDATES), monitor_.handle(), updates_procedure, reinterpret_cast<LPARAM>(this)) == -1)
            throw std::runtime_error("Could not open update settings.");
        update_monitor();
    }
    void open_monitor(bool activate = false) {
        if (shutting_down_) return;
        if (!monitor_.handle()) {
            monitor_.on_tick = [this] { flush_queue_refresh(); update_tick(); update_monitor(); };
            monitor_.on_updates = [this] { show_updates(); };
            monitor_.on_enabled = [this](bool enabled) { set_enabled(enabled); };
            monitor_.on_pinned = [this](bool pinned) { pinned_ = pinned; save_preferences(); update_monitor(); };
            monitor_.on_attached = [this](bool attached) {
                side_panel_ = attached;
                log_.write("monitor.attach", "enabled=" + std::to_string(attached));
                update_monitor(); save_preferences();
            };
            monitor_.on_weights = [this] { Show(monitor_.handle()); };
            monitor_.on_move = [this] { save_preferences(); };
            monitor_.on_logs = [this] {
                if (log_file_.empty()) return;
                const auto result = reinterpret_cast<INT_PTR>(ShellExecuteW(monitor_.handle(), L"open", log_file_.parent_path().c_str(), nullptr, nullptr, SW_SHOWNORMAL));
                if (result <= 32) { log_.write("logs.open.error", "code=" + std::to_string(result)); throw std::runtime_error("Could not open the plugin log folder."); }
            };
            monitor_.on_error = [this](const std::string& error) { log_.write("monitor.error", error); };
            monitor_.on_reroll = [this] { reroll_next(); };
            monitor_.on_weight = [this](int weight) { apply_current_weight(weight); };
            monitor_.on_cooldown = [this](int tracks) { set_cooldown(tracks); };
            monitor_.on_finite = [this](bool enabled) { set_finite(enabled); };
            monitor_.on_scope = [this](int scope) {
                std::lock_guard<std::recursive_mutex> lock(mutex_);
                edit_scope_ = static_cast<ignorant::WeightScope>(std::clamp(scope, 0, 2));
                save_preferences(); update_monitor();
            };
            monitor_.on_clear_override = [this] {
                std::lock_guard<std::recursive_mutex> lock(mutex_);
                const auto current = current_track(); if (!current) return;
                const auto context = context_for(playlist_for(current.get()));
                const auto scope = scope_for(context); if (scope == ignorant::WeightScope::Global) return;
                storage_.clear(scope, track_key(current.get()), context);
                changed(); refresh_cooldown_preview(); update_monitor();
            };
            if (!monitor_.create(module)) throw std::runtime_error("Could not create the IgnorantShuffle floating window.");
            if (!preferences_file_.empty()) {
                RECT bounds{}; GetWindowRect(monitor_.handle(), &bounds);
                const auto width = static_cast<int>(GetPrivateProfileIntW(L"Monitor", L"Width", bounds.right - bounds.left, preferences_file_.c_str()));
                const auto height = static_cast<int>(GetPrivateProfileIntW(L"Monitor", L"Height", bounds.bottom - bounds.top, preferences_file_.c_str()));
                monitor_.restore_size(width, height);
                const auto x = static_cast<int>(GetPrivateProfileIntW(L"Monitor", L"X", 80, preferences_file_.c_str()));
                const auto y = static_cast<int>(GetPrivateProfileIntW(L"Monitor", L"Y", 80, preferences_file_.c_str()));
                monitor_.restore_position(x, y);
            }
        }
        update_monitor();
        monitor_.show(activate);
    }

    bool shuffle_enabled() {
        BOOL enabled = FALSE;
        return messages_ && SUCCEEDED(messages_->Send(AIMP_MSG_PROPERTY_SHUFFLE, AIMP_MSG_PROPVALUE_GET, &enabled)) && enabled;
    }
    Ref<IAIMPPlaylist> playlist_for(IUnknown* current) {
        const auto occurrence = occurrence_from(current);
        if (occurrence) current = occurrence->item.get();
        auto playlist = query<IAIMPPlaylist>(current, IID_IAIMPPlaylist);
        if (playlist) return playlist;
        auto item = query<IAIMPPlaylistItem>(current, IID_IAIMPPlaylistItem);
        if (!item) {
            auto entry = query<IAIMPPlaybackQueueItem>(current, IID_IAIMPPlaybackQueueItem);
            if (entry) entry->GetValueAsObject(AIMP_PLAYBACKQUEUEITEM_PROPID_PLAYLISTITEM, IID_IAIMPPlaylistItem, item.out());
        }
        // The official SDK exposes the item's owning playlist directly.
        if (item) {
            item->GetValueAsObject(AIMP_PLAYLISTITEM_PROPID_PLAYLIST, IID_IAIMPPlaylist, playlist.out());
            if (playlist) {
                int index = -1;
                item->GetValueAsInt32(AIMP_PLAYLISTITEM_PROPID_INDEX, &index);
                Ref<IAIMPPlaylistItem> member;
                if (index >= 0 && SUCCEEDED(playlist->GetItem(index, IID_IAIMPPlaylistItem, member.out())) && same_object(member.get(), item.get()))
                    return playlist;
            }
            return {}; // Removed source item: do not switch to an unrelated playlist.
        }
        playlists_->GetPlayingPlaylist(playlist.put());
        if (!playlist) playlists_->GetActivePlaylist(playlist.put());
        return playlist;
    }
    std::shared_ptr<Occurrence> source_for(IUnknown* current, LongWord flags, const Ref<IAIMPPlaylist>& playlist) {
        Ref<IAIMPPlaylistItem> item;
        if (flags == AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_ITEM)
            item = query<IAIMPPlaylistItem>(current, IID_IAIMPPlaylistItem);
        else if (flags == AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR) {
            auto properties = query<IAIMPPropertyList>(playlist.get(), IID_IAIMPPropertyList);
            int cursor = -1;
            if (properties) properties->GetValueAsInt32(AIMP_PLAYLIST_PROPID_PLAYBACKCURSOR, &cursor);
            // PlaybackCursor indexes AIMP's internal queue, not the visible
            // playlist. See SDK PlaylistManagerTerms: match PlaybackQueueIndex.
            if (cursor >= 0) {
                for (int index = 0; index < playlist->GetItemCount(); ++index) {
                    Ref<IAIMPPlaylistItem> candidate;
                    if (FAILED(playlist->GetItem(index, IID_IAIMPPlaylistItem, candidate.out())) || !candidate) continue;
                    int queue_index = -1;
                    if (SUCCEEDED(candidate->GetValueAsInt32(AIMP_PLAYLISTITEM_PROPID_PLAYBACKQUEUEINDEX, &queue_index)) && queue_index == cursor) {
                        item = std::move(candidate);
                        break;
                    }
                }
            }
            int row = -1;
            if (item) item->GetValueAsInt32(AIMP_PLAYLISTITEM_PROPID_INDEX, &row);
            log_.write("queue.cursor.resolve", "cursor=" + std::to_string(cursor) + " playlist_row=" + std::to_string(row) + ' ' + describe_item(item.get()));
        }
        if (!item) {
            if (!beginning_ || !same_object(beginning_->playlist.get(), playlist.get()))
                beginning_ = remember({}, playlist);
            return beginning_;
        }
        if (flags == AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR && awaiting_stream_start_ && audible_ && same_object(audible_->item.get(), item.get())) return audible_;
        if (playing_ && same_object(playing_->item.get(), item.get())) return playing_;
        if (flags == AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR && audible_ && same_object(audible_->item.get(), item.get())) return audible_;
        // A reroll/weight edit can leave abandoned unplayed nodes in history.
        // Prefer the live plan over those older nodes when AIMP probes ahead.
        for (const auto& root : {cursor_source_, playing_, beginning_}) {
            auto pending = root ? root->next.lock() : nullptr;
            for (int depth = 0; pending && !pending->started && depth < 256; ++depth) {
                if (same_object(pending->item.get(), item.get())) return pending;
                pending = pending->next.lock();
            }
        }
        for (auto it = history_.begin(); it != history_.end(); ++it)
            // A lookahead request must not resume a cached successor belonging
            // to a past playback of this same playlist item.
            if (!(*it)->started && same_object((*it)->item.get(), item.get())) return *it;
        return remember(std::move(item), playlist);
    }
    void changed(bool preserve_current = false, bool notify_host = true) {
        std::vector<std::shared_ptr<Occurrence>> retained;
        if (preserve_current && playing_) {
            retained.push_back(playing_);
            auto next = playing_->next.lock();
            while (next && !next->started && retained.size() <= 256) {
                retained.push_back(next);
                next = next->next.lock();
            }
        }
        log_.write("queue.invalidate", "playing=" + context_id(playing_) + " contexts=" + std::to_string(history_.size()) + " retained=" + std::to_string(retained.size()));
        for (auto& occurrence : history_) {
            if (std::find(retained.begin(), retained.end(), occurrence) != retained.end()) continue;
            occurrence->next.reset(); occurrence->decision_ready = false;
        }
        if (notify_host && queue_) queue_->NotifyChanged(static_cast<IAIMPExtensionPlaybackQueue*>(this));
        request_monitor_update();
    }
    HRESULT publish(const std::shared_ptr<Occurrence>& occurrence, IAIMPPlaybackQueueItem* output, bool history_navigation = false) {
        Ref<IUnknown> token;
        *token.put() = new QueueToken(occurrence, history_navigation);
        auto result = output->SetValueAsObject(AIMP_PLAYBACKQUEUEITEM_PROPID_PLAYLISTITEM, occurrence->item.get());
        if (FAILED(result)) { log_.write("queue.publish.error", "item result=" + std::to_string(result)); return result; }
        result = output->SetValueAsObject(AIMP_PLAYBACKQUEUEITEM_PROPID_CUSTOM, token.get());
        if (FAILED(result)) log_.write("queue.publish.error", "custom result=" + std::to_string(result));
        return result;
    }
    // apiPlayer.h says HRESULT, but the Delphi definition and help specify LongBool:
    // 0 disables our queue; any nonzero result handles it, including an empty queue.
    HRESULT handled(const std::shared_ptr<Occurrence>& occurrence, IAIMPPlaybackQueueItem* output, bool history_navigation = false) {
        if (!occurrence || FAILED(publish(occurrence, output, history_navigation))) {
            output->SetValueAsObject(AIMP_PLAYBACKQUEUEITEM_PROPID_PLAYLISTITEM, nullptr);
            output->SetValueAsObject(AIMP_PLAYBACKQUEUEITEM_PROPID_CUSTOM, nullptr);
        }
        return TRUE;
    }
    std::shared_ptr<Occurrence> remember(Ref<IAIMPPlaylistItem> item, Ref<IAIMPPlaylist> playlist) {
        auto result = std::make_shared<Occurrence>();
        result->id = next_occurrence_id_++;
        result->item = std::move(item);
        result->playlist = std::move(playlist);
        history_.push_back(result);
        if (history_.size() > 256) history_.pop_front();
        return result;
    }
    bool playlist_has_positive(const Ref<IAIMPPlaylist>& playlist) {
        std::vector<std::string> keys;
        for (int i = 0; i < playlist->GetItemCount(); ++i) {
            Ref<IAIMPPlaylistItem> item;
            if (SUCCEEDED(playlist->GetItem(i, IID_IAIMPPlaylistItem, item.out())) && item) {
                const auto key = track_key(item.get()); if (!key.empty()) keys.push_back(key);
            }
        }
        const auto context = context_for(playlist, &keys);
        for (const auto& key : keys) if (effective_weight(key, context).weight > 0) return true;
        return false;
    }
    std::shared_ptr<Occurrence> finite_end(const std::shared_ptr<Occurrence>& source, const Ref<IAIMPPlaylist>& playlist,
        const Ref<IAIMPPlaylistItem>& avoid, bool reroll) {
        // A handled null queue item means unconditional stop in the SDK. Read
        // AIMP's end action explicitly; never delegate to its unweighted queue.
        int action = 2;
        if (messages_ && FAILED(messages_->Send(AIMP_MSG_PROPERTY_ACTION_ON_END_OF_PLAYLIST, AIMP_MSG_PROPVALUE_GET, &action))) action = 2;
        if (action < 0 || action > 2) action = 2;
        source->pass_end = true;
        Ref<IAIMPPlaylist> target;
        if (action == 1 && source->finite_pool.positive) target = playlist;
        else if (action == 0) {
            const auto count = playlists_->GetLoadedPlaylistCount();
            int current_index = -1;
            for (int i = 0; i < count; ++i) {
                Ref<IAIMPPlaylist> candidate;
                if (SUCCEEDED(playlists_->GetLoadedPlaylist(i, candidate.put())) && same_object(candidate.get(), playlist.get())) { current_index = i; break; }
            }
            // Follow loaded playlist order, wrapping to earlier tabs. Do not
            // replay this same list when no other eligible playlist exists.
            for (int step = 1; current_index >= 0 && step < count; ++step) {
                Ref<IAIMPPlaylist> candidate;
                if (SUCCEEDED(playlists_->GetLoadedPlaylist((current_index + step) % count, candidate.put())) && candidate && playlist_has_positive(candidate)) { target = std::move(candidate); break; }
            }
        }
        const auto cached = source->next.lock();
        if (!reroll && target && cached && source->end_action == action && cached->pass && cached->pass != source->pass &&
            same_object(cached->playlist.get(), target.get()) && playlist_for(cached->item.get()) &&
            effective_weight(track_key(cached->item.get()), context_for(target)).weight > 0) {
            source->decision = cached->incoming_decision; return cached;
        }
        source->end_action = action; source->next.reset();
        log_.write("shuffle.finite.end", "source=" + context_id(source) + " action=" + std::to_string(action) + " target=" + std::to_string(static_cast<bool>(target)));
        if (!target) return {};
        auto root = remember({}, target); root->pass = new_pass(target);
        auto next = choose_next(root, target, avoid, reroll, false);
        if (next) {
            next->previous = source; source->next = next; source->decision = root->decision;
        }
        return next;
    }
    std::shared_ptr<Occurrence> choose_next(const std::shared_ptr<Occurrence>& source, const Ref<IAIMPPlaylist>& playlist,
        const Ref<IAIMPPlaylistItem>& avoid = {}, bool reroll = false, bool follow_end = true) {
        std::vector<Ref<IAIMPPlaylistItem>> items;
        std::vector<double> probabilities;
        std::vector<std::string> keys;
        for (int i = 0; i < playlist->GetItemCount(); ++i) {
            Ref<IAIMPPlaylistItem> item;
            if (FAILED(playlist->GetItem(i, IID_IAIMPPlaylistItem, item.out())) || !item) continue;
            // AIMP checks PLAYINGSWITCH itself; SDK forbids filtering it here.
            const auto key = track_key(item.get());
            if (key.empty()) continue;
            items.push_back(std::move(item));
            keys.push_back(key);
        }
        const auto context = context_for(playlist, &keys);
        for (const auto& key : keys) probabilities.push_back(effective_weight(key, context).weight);
        ignorant::CooldownPool pool;
        if (finite_) {
            ensure_pass(source, playlist);
            // A delayed cursor probe can still refer to the preceding audible
            // source after its successor committed. Preserve that known answer.
            const auto committed_next = source->next.lock();
            if (!reroll && source->decision_ready && committed_next && committed_next->started && committed_next->pass == source->pass) {
                for (std::size_t i = 0; i < items.size(); ++i)
                    if (probabilities[i] > 0 && same_object(items[i].get(), committed_next->item.get())) return committed_next;
            }
            source->finite_pool = ignorant::finite_pool(keys, probabilities, projected_pass(source));
            pool.indices = source->finite_pool.indices; pool.distinct_positive = source->finite_pool.positive;
        } else pool = ignorant::cooldown_pool(keys, probabilities, projected_recent(source), cooldown_);
        source->pass_end = false;
        const bool same_pool = source->cooldown.indices == pool.indices;
        cooldown_preview_ = pool;
        cooldown_preview_ready_ = true;
        source->cooldown = std::move(pool);
        // Compact in place; the retained zero entries explain exclusions in UI.
        std::size_t kept = 0;
        for (const auto index : source->cooldown.indices) {
            if (kept != index) { items[kept] = std::move(items[index]); keys[kept] = std::move(keys[index]); probabilities[kept] = probabilities[index]; }
            ++kept;
        }
        items.resize(kept); keys.resize(kept); probabilities.resize(kept);
        if (avoid) {
            const auto old_key = track_key(avoid.get());
            bool alternative = false;
            for (std::size_t i = 0; i < items.size(); ++i) if (probabilities[i] > 0 && keys[i] != old_key) alternative = true;
            if (alternative) {
                for (std::size_t i = items.size(); i-- > 0;) {
                    if (keys[i] != old_key) continue;
                    items.erase(items.begin() + i); keys.erase(keys.begin() + i); probabilities.erase(probabilities.begin() + i);
                }
            }
        }
        auto cached = source->next.lock();
        if (cached && !reroll && same_pool) {
            for (std::size_t index = 0; index < items.size(); ++index) {
                if (probabilities[index] > 0 && same_object(items[index].get(), cached->item.get())) {
                    log_.write("queue.next.cached", "source=" + context_id(source) + " next=" + context_id(cached));
                    return cached;
                }
            }
        }
        const auto unit = finite_ && source->finite_pool.positive == source->finite_pool.played ? 0.0 : std::generate_canonical<double, 53>(random_);
        const auto decision = ignorant::choose_details(probabilities, unit);
        source->decision = decision;
        source->decision_ready = true;
        source->rerolled = reroll;
        request_monitor_update();
        if (!decision.index) {
            if (finite_ && follow_end) return finite_end(source, playlist, avoid, reroll);
            log_.write("queue.next.stop", "source=" + context_id(source) + " reason=no_positive_weights excluded=" + std::to_string(decision.excluded));
            source->next.reset(); return {};
        }
        auto next = remember(items[*decision.index], playlist);
        next->pass = finite_ ? source->pass : nullptr;
        if (finite_) {
            next->pass_path = source->pass_path;
            if (next->pass_path->size() != source->pass_position)
                next->pass_path = std::make_shared<std::vector<std::string>>(source->pass_path->begin(), source->pass_path->begin() + source->pass_position);
            next->pass_path->push_back(keys[*decision.index]);
            next->pass_position = source->pass_position + 1;
        }
        next->incoming_decision = decision;
        next->planned = true;
        source->next = next; next->previous = source;
        std::ostringstream details;
        details << std::setprecision(17) << "source=" << source->id << " next=" << next->id << " weight=" << decision.weight << " probability=" << decision.probability
            << " roll=" << decision.draw << " interval=[" << decision.lower << ',' << decision.upper << ") eligible=" << decision.eligible << " excluded=" << decision.excluded
            << " reroll=" << reroll << " cooldown=" << cooldown_ << " cooling=" << source->cooldown.blocked
            << " cooldown_capacity=" << source->cooldown.distinct_positive << " cooldown_fallback=" << source->cooldown.fallback << ' ' << describe_item(next->item.get());
        log_.write("queue.next.draw", details.str());
        return next;
    }
    void commit(ignorant::Weights next) {
        // Validate UTF-8 before persisting or changing in-memory state.
        for (const auto& [track, weight] : next.entries()) { (void)weight; from_utf8(track); }
        next.save(weights_file_);
        weights_ = std::move(next);
        refresh_cooldown_preview();
        log_.write("weights.saved", "entries=" + std::to_string(weights_.entries().size()));
        changed();
    }
    std::vector<int> selected_rows(HWND window) const {
        (void)window;
        auto rows = track_table_.selected();
        rows.erase(std::remove_if(rows.begin(), rows.end(), [this](int row) { return row < 0 || static_cast<std::size_t>(row) >= visible_tracks_.size(); }), rows.end());
        return rows;
    }
    std::vector<std::string> selected_tracks(HWND window) const {
        std::vector<std::string> tracks;
        for (const auto row : selected_rows(window)) tracks.push_back(visible_tracks_[row]);
        return tracks;
    }
    static double parse_custom_weight(const std::wstring& text) {
        std::istringstream input(utf8(text)); input.imbue(std::locale::classic());
        double value;
        if (!(input >> value) || !(input >> std::ws).eof() || !std::isfinite(value) || value < 0)
            throw std::runtime_error("Enter a finite, nonnegative weight, for example 2.5 or 25. Zero excludes selected tracks.");
        return value;
    }
    static std::wstring weight_text(double value) {
        std::wostringstream text; text.imbue(std::locale::classic());
        text << std::setprecision(std::numeric_limits<double>::max_digits10) << value;
        return text.str();
    }
    void apply_default_weight(HWND window) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        weights_.set_default(parse_custom_weight(control_text(window, IDC_DEFAULT_WEIGHT)));
        save_preferences(); changed(); refresh_cooldown_preview();
        if (dialog_) refresh(dialog_);
        update_monitor();
        log_.write("weights.default", "weight=" + utf8(weight_text(weights_.fallback())));
        storage_status(window, L"Default weight applied: " + weight_text(weights_.fallback()) + L". Explicit track weights kept."
            + (preferences_error_.empty() ? L"" : L"\r\n" + preferences_error_));
    }
    void apply_editor_weight(HWND window, double value) {
        if (!std::isfinite(value) || value < 0) throw std::runtime_error("Weight must be finite and nonnegative.");
        const auto tracks = selected_tracks(window);
        if (tracks.empty()) throw std::runtime_error("Select one or more tracks first.");
        const auto context = context_for(editor_playlist_);
        const auto scope = scope_for(context);
        std::vector<std::string> profile, shared;
        for (const auto& track : tracks)
            (storage_.target(scope, track, context) ? shared : profile).push_back(track);
        auto next_profile = weights_;
        for (const auto& track : profile) next_profile.set(track, value);
        bool profile_saved = false;
        try {
            if (!profile.empty()) {
                next_profile.save(weights_file_); weights_ = std::move(next_profile); profile_saved = true;
            }
            if (!shared.empty()) storage_.set_many(scope, shared, context, value);
        } catch (const std::exception& error) {
            // A global selection spanning the music root and outside it has two
            // files. Report a completed profile save if the shared save fails.
            if (profile_saved) {
                changed();
                try { update_monitor(); refresh(window); }
                catch (const std::exception& display_error) { log_.write("weights.bulk.refresh.error", display_error.what()); }
                throw std::runtime_error("Profile weights were saved, but shared weights were not: " + std::string(error.what()));
            }
            throw;
        }
        changed(); refresh_cooldown_preview(); update_monitor(); refresh(window);
        std::wostringstream status;
        status << L"Saved weight " << std::setprecision(8) << value << L" to " << tracks.size() << L" selected entries | Editing " << scope_label(scope);
        SetDlgItemTextW(window, IDC_STATUS, status.str().c_str());
        std::ostringstream details;
        details << std::setprecision(17) << "count=" << tracks.size() << " weight=" << value << " scope=" << static_cast<int>(scope);
        log_.write("weights.bulk", details.str());
    }
    static LRESULT CALLBACK list_proc(HWND list, UINT message, WPARAM wparam, LPARAM lparam, UINT_PTR id, DWORD_PTR) {
        if (message == WM_KEYDOWN && wparam == 'A' && GetKeyState(VK_CONTROL) < 0) {
            SendMessageW(GetParent(list), WM_COMMAND, MAKEWPARAM(IDC_SELECT_ALL, BN_CLICKED), 0); return 0;
        }
        if (message == WM_CHAR && wparam == 1) return 0;
        if (message == WM_NCDESTROY) RemoveWindowSubclass(list, list_proc, id);
        return DefSubclassProc(list, message, wparam, lparam);
    }
    void refresh(HWND window) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        const auto old_rows = selected_rows(window);
        const auto old_tracks = visible_tracks_;
        const auto list = GetDlgItem(window, IDC_TRACKS);
        const auto top = ListView_GetTopIndex(list);
        const auto caret = ListView_GetNextItem(list, -1, LVNI_FOCUSED);
        const auto anchor = ListView_GetSelectionMark(list);
        Ref<IAIMPPlaylist> playlist;
        if (playlists_) playlists_->GetActivePlaylist(playlist.put());
        const bool preserve = same_object(editor_playlist_.get(), playlist.get());
        std::vector<std::string> tracks;
        std::vector<TrackTableRow> rows;
        if (playlist) {
            for (int i = 0; i < playlist->GetItemCount(); ++i) {
                Ref<IAIMPPlaylistItem> item;
                if (FAILED(playlist->GetItem(i, IID_IAIMPPlaylistItem, item.out())) || !item) continue;
                const auto key = track_key(item.get());
                if (key.empty()) continue;
                tracks.push_back(key);
                auto label = track_label(item.get());
                const auto path = from_utf8(key);
                if (label == path && key.find("://") == std::string::npos) label = std::filesystem::path(path).filename().wstring();
                rows.push_back({std::move(label), path, {}, 0, 0});
            }
        }
        const auto context = context_for(playlist, &tracks);
        const auto editing_scope = scope_for(context);
        for (std::size_t i = 0; i < tracks.size(); ++i) {
            const auto value = effective_weight(tracks[i], context);
            rows[i].source = scope_label(value.scope);
            rows[i].weight = value.weight;
            rows[i].editing_weight = effective_weight(tracks[i], context, editing_scope).weight;
        }
        track_table_.sort(rows);
        tracks.clear();
        for (const auto& row : rows) tracks.push_back(utf8(row.path));
        SendDlgItemMessageW(window, IDC_WEIGHT_SCOPE, CB_RESETCONTENT, 0, 0);
        for (const auto scope : {ignorant::WeightScope::Global, ignorant::WeightScope::Folder, ignorant::WeightScope::Playlist}) {
            if (!storage_.available(scope, context)) continue;
            const auto label = scope_label(scope);
            const auto index = SendDlgItemMessageW(window, IDC_WEIGHT_SCOPE, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label.c_str()));
            SendDlgItemMessageW(window, IDC_WEIGHT_SCOPE, CB_SETITEMDATA, index, static_cast<int>(scope));
            if (scope == scope_for(context)) SendDlgItemMessageW(window, IDC_WEIGHT_SCOPE, CB_SETCURSEL, index, 0);
        }
        std::vector<int> selected;
        std::vector<int> remapped(old_tracks.size(), -1);
        if (preserve) {
            std::map<std::string, std::deque<int>> positions;
            for (std::size_t row = 0; row < tracks.size(); ++row) positions[tracks[row]].push_back(static_cast<int>(row));
            for (std::size_t old = 0; old < old_tracks.size(); ++old) {
                auto& available = positions[old_tracks[old]];
                if (!available.empty()) { remapped[old] = available.front(); available.pop_front(); }
            }
            for (const auto old : old_rows) if (remapped[old] >= 0) selected.push_back(remapped[old]);
        }
        const auto remap = [&](int old) { return old >= 0 && static_cast<std::size_t>(old) < remapped.size() ? remapped[old] : -1; };
        editor_playlist_ = playlist;
        visible_tracks_ = std::move(tracks);
        const bool show_edit = storage_.available(ignorant::WeightScope::Folder, context) || storage_.available(ignorant::WeightScope::Playlist, context);
        track_table_.replace(std::move(rows), selected, remap(caret), remap(anchor), std::max(0, remap(top)),
            show_edit, L"Edit " + scope_label(editing_scope));
        editor_selection_pending_ = false;
        selection(window);
    }
    void selection(HWND window) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        const auto tracks = selected_tracks(window);
        for (int value = 0; value <= 10; ++value) EnableWindow(GetDlgItem(window, IDC_EDITOR_WEIGHT_0 + value), !tracks.empty());
        EnableWindow(GetDlgItem(window, IDC_APPLY_WEIGHT), !tracks.empty());
        EnableWindow(GetDlgItem(window, IDC_SELECT_ALL), !visible_tracks_.empty());
        EnableWindow(GetDlgItem(window, IDC_RESET_LOCAL), FALSE);
        if (tracks.empty()) {
            SetDlgItemTextW(window, IDC_STATUS, L"No tracks selected. Use Ctrl/Shift-click or Select all, then click a weight."); return;
        }
        const auto context = context_for(editor_playlist_);
        const auto scope = scope_for(context);
        const double weight = effective_weight(tracks.front(), context, scope).weight;
        bool mixed = false, can_inherit = false;
        for (const auto& track : tracks) {
            mixed = mixed || effective_weight(track, context, scope).weight != weight;
            if (scope != ignorant::WeightScope::Global) can_inherit = can_inherit || storage_.has_override(scope, track, context);
        }
        std::wostringstream status;
        status << tracks.size() << L" selected | Editing " << scope_label(scope) << L" | Weight: ";
        if (mixed) status << L"mixed"; else status << std::setprecision(8) << weight;
        if (GetFocus() != GetDlgItem(window, IDC_WEIGHT)) {
            std::wostringstream custom;
            if (!mixed) custom << std::setprecision(std::numeric_limits<double>::max_digits10) << weight;
            SetDlgItemTextW(window, IDC_WEIGHT, custom.str().c_str());
        }
        SetDlgItemTextW(window, IDC_STATUS, status.str().c_str());
        EnableWindow(GetDlgItem(window, IDC_RESET_LOCAL), can_inherit);
    }
    static std::wstring control_text(HWND window, int id) {
        const auto child = GetDlgItem(window, id);
        std::vector<wchar_t> text(GetWindowTextLengthW(child) + 1);
        GetWindowTextW(child, text.data(), static_cast<int>(text.size()));
        return text.data();
    }
    struct StorageView { ignorant::WeightContext context; std::vector<std::string> tracks; };
    StorageView storage_view() {
        auto current = current_track();
        auto playlist = editor_playlist_ ? editor_playlist_ : playlist_for(current.get());
        StorageView view;
        if (playlist) for (int i = 0; i < playlist->GetItemCount(); ++i) {
            Ref<IAIMPPlaylistItem> item;
            if (SUCCEEDED(playlist->GetItem(i, IID_IAIMPPlaylistItem, item.out())) && item) {
                auto key = track_key(item.get()); if (!key.empty()) view.tracks.push_back(std::move(key));
            }
        }
        view.context = context_for(playlist, &view.tracks); return view;
    }
    void fill_storage_scopes(HWND window) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        const auto context = storage_view().context;
        SendDlgItemMessageW(window, IDC_WEIGHT_SCOPE, CB_RESETCONTENT, 0, 0);
        for (const auto scope : {ignorant::WeightScope::Global, ignorant::WeightScope::Folder, ignorant::WeightScope::Playlist}) {
            if (!storage_.available(scope, context)) continue;
            const auto label = scope_label(scope);
            const auto index = SendDlgItemMessageW(window, IDC_WEIGHT_SCOPE, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label.c_str()));
            SendDlgItemMessageW(window, IDC_WEIGHT_SCOPE, CB_SETITEMDATA, index, static_cast<int>(scope));
            if (scope == scope_for(context)) SendDlgItemMessageW(window, IDC_WEIGHT_SCOPE, CB_SETCURSEL, index, 0);
        }
    }
    void transfer_file(const std::filesystem::path& path, bool save, ignorant::WeightScope scope, const StorageView& view) {
        if (save) {
            storage_.snapshot(scope, weights_, view.tracks, view.context).save(path);
        } else {
            std::ifstream input(path, std::ios::binary);
            if (!input) throw std::runtime_error("Could not open import file.");
            const auto key = (music_folder_ / "import-target").u8string();
            if (storage_.target(scope, key, view.context)) {
                storage_.import(scope, key, view.context, input); changed(); refresh_cooldown_preview();
            } else {
                auto next = weights_; next.import(input); commit(std::move(next));
            }
            update_monitor();
            if (dialog_) refresh(dialog_);
        }
    }
    void storage_transfer(HWND window, bool save) {
        StorageView view; ignorant::WeightScope scope;
        {
            std::lock_guard<std::recursive_mutex> lock(mutex_);
            view = storage_view(); scope = scope_for(view.context);
        }
        // File dialogs must not hold the playback queue lock.
        const auto path = choose_file(window, save);
        if (path.empty()) return;
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        transfer_file(path, save, scope, view);
        storage_status(window, (save ? L"Exported " : L"Imported into ") + scope_label(scope) + L" scope: " + path.wstring());
    }
    void storage_status(HWND window, const std::wstring& prefix = L"") {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        const auto context = storage_view().context;
        std::wstring status = prefix;
        if (!status.empty()) status += L"\r\n";
        if (!storage_error_.empty()) status += L"Error: " + storage_error_ + L"\r\n";
        status += L"Import/export scope: " + scope_label(scope_for(context)) + L" (applied settings)\r\n";
        status += L"Global: " + (shared_global_ && !music_folder_.empty() ? music_folder_ / L"IgnorantShuffle.weights.tsv" : weights_file_).wstring();
        if (shared_global_ && music_folder_.empty()) status += L"\r\nChoose the synchronized music root to activate shared weights; using profile for now.";
        for (const auto scope : {ignorant::WeightScope::Folder, ignorant::WeightScope::Playlist}) {
            status += L"\r\n" + scope_label(scope) + L": ";
            if (storage_.available(scope, context)) status += storage_.target(scope, "", context)->wstring();
            else status += L"disabled or no list inside the music folder";
        }
        SetDlgItemTextW(window, IDC_STORAGE_STATUS, status.c_str());
    }
    void apply_storage_dialog(HWND window) {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        set_storage(control_text(window, IDC_MUSIC_FOLDER),
            IsDlgButtonChecked(window, IDC_SHARED_GLOBAL) == BST_CHECKED,
            IsDlgButtonChecked(window, IDC_FOLDER_LOCAL) == BST_CHECKED,
            IsDlgButtonChecked(window, IDC_PLAYLIST_LOCAL) == BST_CHECKED);
        if (dialog_) refresh(dialog_);
        fill_storage_scopes(window);
        storage_status(window, L"Settings applied; shared files reloaded.");
    }
    void browse_music_folder(HWND window) {
        const auto initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
        struct ComGuard { HRESULT result; ~ComGuard() { if (SUCCEEDED(result)) CoUninitialize(); } } guard{initialized};
        Ref<IFileDialog> browser;
        if (FAILED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_IFileDialog, browser.out())))
            throw std::runtime_error("Could not open the music-folder chooser. Enter its full path instead.");
        browser->SetOptions(FOS_PICKFOLDERS | FOS_PATHMUSTEXIST | FOS_FORCEFILESYSTEM | FOS_DONTADDTORECENT);
        browser->SetTitle(L"Choose the root folder you share between devices");
        if (FAILED(browser->Show(window))) return;
        Ref<IShellItem> selected;
        if (FAILED(browser->GetResult(selected.put()))) return;
        PWSTR folder = nullptr;
        if (SUCCEEDED(selected->GetDisplayName(SIGDN_FILESYSPATH, &folder))) {
            SetDlgItemTextW(window, IDC_MUSIC_FOLDER, folder); CoTaskMemFree(folder);
        }
    }
    static INT_PTR CALLBACK storage_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
        auto* self = reinterpret_cast<Plugin*>(GetWindowLongPtrW(window, DWLP_USER));
        try {
            if (message == WM_INITDIALOG) {
                self = reinterpret_cast<Plugin*>(lparam);
                SetWindowLongPtrW(window, DWLP_USER, lparam); self->storage_dialog_ = window;
                SetDlgItemTextW(window, IDC_MUSIC_FOLDER, self->music_folder_.c_str());
                SetDlgItemTextW(window, IDC_DEFAULT_WEIGHT, weight_text(self->weights_.fallback()).c_str());
                CheckDlgButton(window, IDC_SHARED_GLOBAL, self->shared_global_ ? BST_CHECKED : BST_UNCHECKED);
                CheckDlgButton(window, IDC_FOLDER_LOCAL, self->folder_local_ ? BST_CHECKED : BST_UNCHECKED);
                CheckDlgButton(window, IDC_PLAYLIST_LOCAL, self->playlist_local_ ? BST_CHECKED : BST_UNCHECKED);
                EnableWindow(GetDlgItem(window, IDC_COPY_PROFILE), self->shared_global_ && !self->music_folder_.empty());
                self->fill_storage_scopes(window);
                self->storage_status(window); return TRUE;
            }
            if (!self) return FALSE;
            if (message == WM_COMMAND) {
                const auto id = LOWORD(wparam);
                if (id == IDOK || id == IDCANCEL) EndDialog(window, 0);
                else if (id == IDC_SHARED_GLOBAL || (id == IDC_MUSIC_FOLDER && HIWORD(wparam) == EN_CHANGE))
                    EnableWindow(GetDlgItem(window, IDC_COPY_PROFILE), IsDlgButtonChecked(window, IDC_SHARED_GLOBAL) == BST_CHECKED && !control_text(window, IDC_MUSIC_FOLDER).empty());
                else if (id == IDC_BROWSE_MUSIC) self->browse_music_folder(window);
                else if (id == IDC_APPLY_STORAGE) self->apply_storage_dialog(window);
                else if (id == IDC_APPLY_DEFAULT_WEIGHT) self->apply_default_weight(window);
                else if (id == IDC_IMPORT || id == IDC_EXPORT) self->storage_transfer(window, id == IDC_EXPORT);
                else if (id == IDC_WEIGHT_SCOPE && HIWORD(wparam) == CBN_SELCHANGE) {
                    std::lock_guard<std::recursive_mutex> lock(self->mutex_);
                    const auto index = SendDlgItemMessageW(window, id, CB_GETCURSEL, 0, 0);
                    if (index >= 0) {
                        self->edit_scope_ = static_cast<ignorant::WeightScope>(SendDlgItemMessageW(window, id, CB_GETITEMDATA, index, 0));
                        self->save_preferences();
                        if (self->dialog_) self->refresh(self->dialog_);
                        self->update_monitor(); self->storage_status(window);
                    }
                }
                else if (id == IDC_COPY_PROFILE) {
                    std::lock_guard<std::recursive_mutex> lock(self->mutex_);
                    self->apply_storage_dialog(window);
                    const auto copied = self->storage_.copy_profile(self->weights_);
                    self->changed(); self->refresh_cooldown_preview(); self->update_monitor();
                    if (self->dialog_) self->refresh(self->dialog_);
                    self->storage_status(window, L"Copied " + std::to_wstring(copied) + L" missing global weights; existing shared values kept.");
                }
                return TRUE;
            }
            if (message == WM_CLOSE) { EndDialog(window, 0); return TRUE; }
            if (message == WM_DESTROY) self->storage_dialog_ = nullptr;
        } catch (const std::exception& error) {
            if (self) self->log_.write("storage.settings.error", error.what());
            MessageBoxA(window, error.what(), "IgnorantShuffle - Weight storage", MB_OK | MB_ICONERROR);
        }
        return FALSE;
    }
    std::filesystem::path choose_file(HWND window, bool save) {
        wchar_t filename[32768] = L"";
        OPENFILENAMEW options{};
        options.lStructSize = sizeof(options);
        options.hwndOwner = window;
        options.lpstrFilter = L"Track weights (*.tsv)\0*.tsv\0All files\0*.*\0";
        options.lpstrFile = filename;
        options.nMaxFile = static_cast<DWORD>(std::size(filename));
        options.lpstrDefExt = L"tsv";
        options.Flags = OFN_EXPLORER | OFN_NOCHANGEDIR | OFN_PATHMUSTEXIST | (save ? OFN_OVERWRITEPROMPT : OFN_FILEMUSTEXIST);
        return (save ? GetSaveFileNameW(&options) : GetOpenFileNameW(&options)) ? filename : std::filesystem::path{};
    }
    void command(HWND window, int id, int notification) {
        // Keep the options dialog and folder picker outside the queue lock.
        if (id == IDC_STORAGE) {
            DialogBoxParamW(module, MAKEINTRESOURCEW(IDD_STORAGE), window, storage_proc, reinterpret_cast<LPARAM>(this)); return;
        }
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        if (id == IDOK && GetFocus() == GetDlgItem(window, IDC_WEIGHT)) {
            apply_editor_weight(window, parse_custom_weight(control_text(window, IDC_WEIGHT))); return;
        }
        if (id == IDOK || id == IDCANCEL) { EndDialog(window, 0); return; }
        if (id == IDC_REFRESH) refresh(window);
        if (id == IDC_SELECT_ALL) {
            track_table_.select_all(); selection(window);
        }
        if (id == IDC_WEIGHT_SCOPE && notification == CBN_SELCHANGE) {
            const auto index = SendDlgItemMessageW(window, id, CB_GETCURSEL, 0, 0);
            if (index >= 0) {
                edit_scope_ = static_cast<ignorant::WeightScope>(SendDlgItemMessageW(window, id, CB_GETITEMDATA, index, 0));
                save_preferences(); refresh(window);
                update_monitor();
            }
        }
        if (id == IDC_RESET_LOCAL) {
            const auto tracks = selected_tracks(window);
            const auto context = context_for(editor_playlist_);
            const auto scope = scope_for(context);
            if (!tracks.empty() && scope != ignorant::WeightScope::Global) {
                storage_.clear_many(scope, tracks, context);
                changed(); refresh_cooldown_preview(); refresh(window);
                update_monitor();
            }
        }
        if (id == IDC_APPLY_WEIGHT) apply_editor_weight(window, parse_custom_weight(control_text(window, IDC_WEIGHT)));
        if (id >= IDC_EDITOR_WEIGHT_0 && id <= IDC_EDITOR_WEIGHT_10) apply_editor_weight(window, id - IDC_EDITOR_WEIGHT_0);
    }
    static INT_PTR CALLBACK dialog_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
        auto* self = reinterpret_cast<Plugin*>(GetWindowLongPtrW(window, DWLP_USER));
        try {
            if (message == WM_INITDIALOG) {
                self = reinterpret_cast<Plugin*>(lparam);
                SetWindowLongPtrW(window, DWLP_USER, lparam);
                self->dialog_ = window;
                self->editor_selection_pending_ = false;
                self->track_table_.attach(GetDlgItem(window, IDC_TRACKS));
                SetWindowSubclass(GetDlgItem(window, IDC_TRACKS), list_proc, 1, 0);
                SendDlgItemMessageW(window, IDC_WEIGHT, EM_SETCUEBANNER, TRUE, reinterpret_cast<LPARAM>(L"e.g. 2.5 or 25"));
                self->refresh(window);
                return TRUE;
            }
            if (self && message == WM_COMMAND) { self->command(window, LOWORD(wparam), HIWORD(wparam)); return TRUE; }
            if (self && message == WM_APP + 3) {
                self->editor_selection_pending_ = false; self->selection(window); return TRUE;
            }
            if (self && message == WM_NOTIFY) {
                auto* notification = reinterpret_cast<NMHDR*>(lparam);
                if (notification->idFrom == IDC_TRACKS) {
                    if (notification->code == LVN_COLUMNCLICK) {
                        self->track_table_.sort_by(reinterpret_cast<NMLISTVIEW*>(lparam)->iSubItem);
                        self->refresh(window); return TRUE;
                    }
                    if (notification->code == LVN_ITEMCHANGED && !self->track_table_.updating()) {
                        const auto* change = reinterpret_cast<NMLISTVIEW*>(lparam);
                        if ((change->uChanged & LVIF_STATE) && ((change->uNewState ^ change->uOldState) & LVIS_SELECTED) && !self->editor_selection_pending_) {
                            self->editor_selection_pending_ = true; PostMessageW(window, WM_APP + 3, 0, 0);
                        }
                    }
                    SetWindowLongPtrW(window, DWLP_MSGRESULT, self->track_table_.notify(notification)); return TRUE;
                }
            }
            if (message == WM_CLOSE) { EndDialog(window, 0); return TRUE; }
            if (self && message == WM_DESTROY) { self->track_table_.detach(); self->dialog_ = nullptr; }
        } catch (const std::exception& error) {
            if (self) self->log_.write("settings.error", error.what());
            MessageBoxA(window, error.what(), "IgnorantShuffle", MB_OK | MB_ICONERROR);
        } catch (...) {
            if (self) self->log_.write("settings.error", "unknown exception");
            MessageBoxW(window, L"Unexpected plugin error.", L"IgnorantShuffle", MB_OK | MB_ICONERROR);
        }
        return FALSE;
    }
public:
    explicit Plugin(ignorant::UpdateTransport transport = {}, ignorant::UpdateLauncher launcher = {})
        : updater_(ignorant::build_version, std::move(transport)), update_launcher_(std::move(launcher)) {}
    HRESULT WINAPI QueryInterface(REFIID iid, void** object) override {
        if (!object) return E_POINTER;
        *object = nullptr;
        if (iid == IID_IUnknown) *object = static_cast<IAIMPPlugin*>(this);
        else if (iid == IID_IAIMPExtensionPlaybackQueue) *object = static_cast<IAIMPExtensionPlaybackQueue*>(this);
        else if (iid == IID_IAIMPExternalSettingsDialog) *object = static_cast<IAIMPExternalSettingsDialog*>(this);
        else if (iid == IID_IAIMPMessageHook) *object = static_cast<IAIMPMessageHook*>(this);
        else return E_NOINTERFACE;
        AddRef();
        return S_OK;
    }
    ULONG WINAPI AddRef() override { return ++references_; }
    ULONG WINAPI Release() override {
        const auto count = --references_;
        if (!count) delete this;
        return count;
    }
    TChar* WINAPI InfoGet(int index) override {
        switch (index) {
        case AIMP_PLUGIN_INFO_NAME: return const_cast<TChar*>(L"IgnorantShuffle " IGNORANT_SHUFFLE_WINDOWS_VERSION_W);
        case AIMP_PLUGIN_INFO_AUTHOR: return const_cast<TChar*>(L"IgnorantShuffle contributors");
        case AIMP_PLUGIN_INFO_SHORT_DESCRIPTION: return const_cast<TChar*>(L"Weighted shuffle: 0 excludes a track; larger weights play more often.");
        case AIMP_PLUGIN_INFO_FULL_DESCRIPTION: return const_cast<TChar*>(L"Uses AIMP's Shuffle switch. Configure track weights, optional track cooldown, or finite weighted playlists that follow AIMP's end-of-playlist action. Custom weights may exceed 10. The default weight is configurable in Weight storage (initially 5).");
        default: return nullptr;
        }
    }
    LongWord WINAPI InfoGetCategories() override { return AIMP_PLUGIN_CATEGORY_ADDONS; }
    HRESULT WINAPI Initialize(IAIMPCore* core) override {
        if (!core) return E_POINTER;
        try {
            shutting_down_ = false;
            core->AddRef(); *core_.put() = core;
            playlists_ = query<IAIMPServicePlaylistManager>(core, IID_IAIMPServicePlaylistManager);
            messages_ = query<IAIMPServiceMessageDispatcher>(core, IID_IAIMPServiceMessageDispatcher);
            queue_ = query<IAIMPServicePlaybackQueue2>(core, IID_IAIMPServicePlaybackQueue2);
            player_ = query<IAIMPServicePlayer>(core, IID_IAIMPServicePlayer);
            if (!playlists_ || !messages_ || !queue_) { Finalize(); return E_NOINTERFACE; }
            Ref<IAIMPString> profile;
            if (FAILED(core->GetPath(AIMP_CORE_PATH_PROFILE, profile.put())) || !profile) { Finalize(); return E_FAIL; }
            weights_file_ = std::filesystem::path(wide(profile.get())) / L"IgnorantShuffle" / L"weights.tsv";
            std::filesystem::create_directories(weights_file_.parent_path());
            log_file_ = weights_file_.parent_path() / L"plugin.log";
            log_.start(log_file_);
            log_.write("plugin.start", "version=" + std::string(ignorant::build_version) + " core=" + ignorant::core_version + " arch=" + std::string(sizeof(void*) == 8 ? "x64" : "x86") + " pid=" + std::to_string(GetCurrentProcessId()));
            preferences_file_ = weights_file_.parent_path() / L"preferences.ini";
            load_preferences();
            weights_.load(weights_file_);
            for (const auto& entry : weights_.entries()) from_utf8(entry.first);
            try { storage_.configure(music_folder_, shared_global_ && !music_folder_.empty(), folder_local_, playlist_local_); }
            catch (const std::exception& error) { storage_error_ = from_utf8(error.what()); log_.write("storage.error", error.what()); }
            // Loading the plugin during playback must also cool down the track
            // already playing, even though we missed its stream-start event.
            if (auto current = current_track()) {
                const auto key = track_key(current.get());
                if (!key.empty()) recent_tracks_.push_back(key);
                commit_finite_start(current);
            }
            if (storage_error_.empty()) refresh_cooldown_preview();
            log_.write("weights.loaded", "entries=" + std::to_string(weights_.entries().size()) + " enabled=" + std::to_string(enabled_));
            const auto result = core->RegisterExtension(IID_IAIMPServicePlaybackQueue, static_cast<IAIMPExtensionPlaybackQueue*>(this));
            if (FAILED(result)) { Finalize(); return result; }
            registered_ = true;
            if (FAILED(messages_->Hook(static_cast<IAIMPMessageHook*>(this)))) { Finalize(); return E_FAIL; }
            hooked_ = true;
            changed();
            next_update_check_ = 0;
            update_tick();
            open_monitor();
            return S_OK;
        } catch (const std::exception& error) {
            log_.write("plugin.initialize.error", error.what());
            OutputDebugStringA(error.what());
            Finalize();
            return E_FAIL;
        } catch (...) { log_.write("plugin.initialize.error", "unknown exception"); Finalize(); return E_FAIL; }
    }
    HRESULT WINAPI Finalize() override {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        shutting_down_ = true;
        install_requested_ = false; updater_.stop();
        if (hooked_ && messages_) messages_->Unhook(static_cast<IAIMPMessageHook*>(this));
        hooked_ = false;
        monitor_.destroy();
        if (update_dialog_) EndDialog(update_dialog_, 0);
        if (dialog_) EndDialog(dialog_, 0);
        if (storage_dialog_) EndDialog(storage_dialog_, 0);
        if (registered_ && core_) core_->UnregisterExtension(static_cast<IAIMPExtensionPlaybackQueue*>(this));
        registered_ = false;
        history_.clear();
        reset_finite_progress();
        weight_contexts_.clear(); editor_playlist_ = {};
        playing_.reset(); audible_.reset(); cursor_source_.reset(); beginning_.reset();
        recent_tracks_.clear(); prepared_track_ = {}; cooldown_preview_ready_ = false;
        queue_refresh_pending_ = false;
        awaiting_stream_start_ = false;
        player_ = {}; queue_ = {}; messages_ = {}; playlists_ = {}; core_ = {};
        log_.write("plugin.stop");
        log_.stop();
        return S_OK;
    }
    void WINAPI SystemNotification(int, IUnknown*) override {}
    void WINAPI CoreMessage(LongWord message, int parameter, void*, HRESULT*) override {
        // Observe only; AIMP must continue receiving its own messages.
        try {
            std::lock_guard<std::recursive_mutex> lock(mutex_);
            if (message == AIMP_MSG_EVENT_STREAM_START || message == AIMP_MSG_EVENT_STREAM_END ||
                message == AIMP_MSG_EVENT_PLAYER_STATE || message == AIMP_MSG_EVENT_PLAYBACK_QUEUE ||
                message == AIMP_MSG_EVENT_LOADED || message == AIMP_MSG_EVENT_TERMINATING ||
                (message == AIMP_MSG_EVENT_PROPERTY_VALUE && parameter == AIMP_MSG_PROPERTY_SHUFFLE))
                log_.write("aimp.event", "message=" + std::to_string(message) + " parameter=" + std::to_string(parameter) + " playing=" + context_id(playing_));
            if (message == AIMP_MSG_EVENT_TERMINATING) { shutting_down_ = true; monitor_.destroy(); return; }
            if (message == AIMP_MSG_EVENT_LOADED) { open_monitor(); return; }
            if (message == AIMP_MSG_EVENT_STREAM_START) {
                auto started = prepared_track_ ? prepared_track_ : current_track();
                if (started) {
                    const auto key = track_key(started.get());
                    if (!key.empty()) {
                        recent_tracks_.push_back(key);
                        if (recent_tracks_.size() > 10000) recent_tracks_.pop_front();
                        log_.write("cooldown.played", "history=" + std::to_string(recent_tracks_.size()) + " " + describe_item(started.get()));
                    }
                }
                prepared_track_ = {};
                audible_ = playing_ && started && same_object(playing_->item.get(), started.get()) ? playing_ : nullptr;
                commit_finite_start(started);
                awaiting_stream_start_ = false;
                log_.write("playback.commit", "context=" + context_id(audible_));
                flush_queue_refresh();
            }
            if (message == AIMP_MSG_EVENT_PROPERTY_VALUE && parameter == AIMP_MSG_PROPERTY_SHUFFLE) { reset_finite_progress(); changed(); }
            if (message == AIMP_MSG_EVENT_PROPERTY_VALUE && parameter == AIMP_MSG_PROPERTY_ACTION_ON_END_OF_PLAYLIST && finite_) changed();
            if (message == AIMP_MSG_EVENT_PLAYER_STATE && finite_ && player_ && player_->GetState() == AIMP_PLAYER_STATE_STOPPED && !awaiting_stream_start_) {
                reset_finite_progress();
                history_.clear(); playing_.reset(); audible_.reset(); cursor_source_.reset(); beginning_.reset();
                queue_refresh_pending_ = false;
            }
            if (message == AIMP_MSG_EVENT_PLAYBACK_QUEUE) weight_contexts_.clear();
            if (message == AIMP_MSG_EVENT_STREAM_START || message == AIMP_MSG_EVENT_PLAYER_STATE ||
                message == AIMP_MSG_EVENT_PLAYING_FILE_INFO || message == AIMP_MSG_EVENT_PLAYBACK_QUEUE ||
                message == AIMP_MSG_EVENT_PROPERTY_VALUE) request_monitor_update();
        } catch (...) { log_.write("aimp.event.error", "floating-window update failed"); OutputDebugStringW(L"IgnorantShuffle: could not update floating window.\n"); }
    }
    void WINAPI Show(HWND parent) override {
        if (dialog_) { SetForegroundWindow(dialog_); return; }
        if (!core_) return;
        INITCOMMONCONTROLSEX controls{sizeof(controls), ICC_LISTVIEW_CLASSES};
        if (!InitCommonControlsEx(&controls)) return;
        open_monitor();
        DialogBoxParamW(module, MAKEINTRESOURCEW(IDD_SETTINGS), parent, dialog_proc, reinterpret_cast<LPARAM>(this));
    }
    HRESULT WINAPI GetNext(IUnknown* current, LongWord flags, IAIMPPlaybackQueueItem* output) override {
        if (!output) return FALSE;
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        const auto active = enabled_ && playlists_ && shuffle_enabled();
        log_.write("queue.next.request", "active=" + std::to_string(active) + " flags=" + std::to_string(flags) + " playing=" + context_id(playing_) + ' ' + describe_item(current));
        if (!active) return FALSE;
        try {
            auto playlist = playlist_for(current);
            if (!playlist) { log_.write("queue.next.stop", "reason=source_playlist_unavailable"); return handled({}, output); }
            auto source = source_for(current, flags, playlist);
            if (flags == AIMP_PLAYBACKQUEUE_FLAGS_START_FROM_CURSOR) cursor_source_ = source;
            return handled(choose_next(source, playlist), output);
        } catch (const std::exception& error) { log_.write("queue.next.error", error.what()); return handled({}, output); }
        catch (...) { log_.write("queue.next.error", "unknown exception"); return handled({}, output); }
    }
    HRESULT WINAPI GetPrev(IUnknown* current, LongWord flags, IAIMPPlaybackQueueItem* output) override {
        if (!output) return FALSE;
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        log_.write("queue.previous.request", "flags=" + std::to_string(flags) + " playing=" + context_id(playing_) + ' ' + describe_item(current));
        if (!enabled_ || !playlists_ || !shuffle_enabled()) return FALSE;
        try {
            auto playlist = playlist_for(current);
            if (!playlist) return handled({}, output);
            auto source = source_for(current, flags, playlist);
            auto previous = source ? source->previous.lock() : nullptr;
            if (!previous || !previous->item) return handled({}, output);
            if (effective_weight(track_key(previous->item.get()), context_for(previous->playlist)).weight <= 0) return handled({}, output);
            // Re-resolve membership so deleted items cannot be returned from history.
            if (!playlist_for(previous->item.get())) return handled({}, output);
            log_.write("queue.previous.choice", "source=" + context_id(source) + " previous=" + context_id(previous));
            return handled(previous, output, true);
        } catch (...) { log_.write("queue.previous.error", "could not resolve history"); return handled({}, output); }
    }
    void WINAPI OnSelect(IAIMPPlaylistItem* item, IAIMPPlaybackQueueItem* output) override {
        // Manual selections remain available, including tracks with zero weight.
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        if (!output || !item || !playlists_) return;
        try {
            prepared_track_ = query<IAIMPPlaylistItem>(item, IID_IAIMPPlaylistItem);
            if (!enabled_ || !shuffle_enabled()) { request_monitor_update(); return; }
            auto track = query<IAIMPPlaylistItem>(item, IID_IAIMPPlaylistItem);
            auto playlist = playlist_for(item);
            bool history_navigation = false;
            auto selected = occurrence_from(output, &history_navigation);
            const auto previous = playing_;
            log_.write("queue.select", "previous=" + context_id(previous) + " supplied=" + context_id(selected) + " started=" + std::to_string(selected && selected->started) + " history=" + std::to_string(history_navigation) + ' ' + describe_item(item));
            // AIMP notifies once when preparing a track and again just before
            // STREAM_START. The second notification is the same playback, not
            // an automatic repeat. Do not consume the next node or erase it.
            if (awaiting_stream_start_ && playing_ && (!selected || selected == playing_) && !history_navigation && same_object(playing_->item.get(), item)) {
                log_.write("queue.select.duplicate", "context=" + context_id(playing_) + " awaiting_stream_start=1");
                if (!selected) publish(playing_, output);
                request_monitor_update();
                return;
            }
            // Some host paths omit CUSTOM at the playback boundary. Only adopt
            // the expected, unplayed successor, never a completed history entry.
            // Its outgoing decision may already have been prefetched by AIMP.
            auto expected = previous ? previous->next.lock() : beginning_ ? beginning_->next.lock() : nullptr;
            if ((!expected || expected->started || !same_object(expected->item.get(), item)) && cursor_source_) {
                auto cursor_next = cursor_source_->next.lock();
                if (cursor_next && !cursor_next->started && same_object(cursor_next->item.get(), item)) expected = cursor_next;
            }
            if ((!selected || selected->started || !same_object(selected->item.get(), item)) && !history_navigation &&
                expected && !expected->started && same_object(expected->item.get(), item)) {
                selected = expected;
                log_.write("queue.select.recovered", "context=" + context_id(selected) + " reason=expected_pending_successor");
            }
            if (selected && (!selected->started || history_navigation) && same_object(selected->item.get(), item)) playing_ = selected;
            else if (playlist) {
                playing_ = remember(std::move(track), std::move(playlist));
                if (previous && same_object(previous->playlist.get(), playing_->playlist.get())) playing_->previous = previous;
                // Lookahead may alias two occurrences of one IAIMPPlaylistItem.
                // If an already played context comes back, discard the host's
                // remaining cached chain after OnSelect has returned.
                if (selected && selected->started) queue_refresh_pending_ = true;
            }
            if (playing_) {
                if (finite_) ensure_pass(playing_, playing_->playlist);
                playing_->started = true; playing_->planned = true;
            }
            awaiting_stream_start_ = true;
            // A Previous request authorizes one history navigation, not unlimited
            // reuse if AIMP later repeats this queue entry automatically.
            if (playing_) publish(playing_, output);
            log_.write("queue.select.active", "context=" + context_id(playing_) + " decision_ready=" + std::to_string(playing_ && playing_->decision_ready) + " refresh_pending=" + std::to_string(queue_refresh_pending_));
            beginning_.reset();
            request_monitor_update();
        } catch (const std::exception& error) { log_.write("queue.select.error", error.what()); }
        catch (...) { log_.write("queue.select.error", "unknown exception"); OutputDebugStringW(L"IgnorantShuffle: could not remember manual selection.\n"); }
    }
};
}

extern "C" HRESULT WINAPI AIMPPluginGetHeader(IAIMPPlugin** header) {
    if (!header) return E_POINTER;
    *header = nullptr;
    try { *header = new Plugin; return S_OK; }
    catch (...) { return E_OUTOFMEMORY; }
}
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) module = instance;
    return TRUE;
}
