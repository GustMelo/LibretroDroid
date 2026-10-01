#include "achievements.h"

#include <cstdio>
#include <cstring>

#include "rc_client.h"
#include "rc_consoles.h"
#include "rc_hash.h"
#include "rc_error.h"
#include "rc_libretro.h"

#include "core.h"
#include "environment.h"
#include "log.h"
#include "vfs/vfs.h"

namespace libretrodroid {

namespace json {
std::string quote(const std::string& text) {
    std::string out;
    out.reserve(text.size() + 2);
    out.push_back('"');
    for (unsigned char c : text) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char escaped[8];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", c);
                    out += escaped;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    out.push_back('"');
    return out;
}
}

namespace {

rc_libretro_memory_regions_t regions {};
bool regionsReady = false;
Core* memoryCore = nullptr;

void coreMemoryInfo(uint32_t id, rc_libretro_core_memory_info_t* info) {
    info->data = memoryCore ? static_cast<uint8_t*>(memoryCore->retro_get_memory_data(id)) : nullptr;
    info->size = memoryCore ? memoryCore->retro_get_memory_size(id) : 0;
}

// Hashing reads through the engine's VFS, so a disc opened from a file descriptor (Android's folder) hashes too.
void* fileOpen(const char* path) {
    return VFS::open(path, RETRO_VFS_FILE_ACCESS_READ, RETRO_VFS_FILE_ACCESS_HINT_NONE);
}

void fileSeek(void* handle, int64_t offset, int origin) {
    int position = origin == SEEK_END ? RETRO_VFS_SEEK_POSITION_END
        : origin == SEEK_CUR ? RETRO_VFS_SEEK_POSITION_CURRENT : RETRO_VFS_SEEK_POSITION_START;
    VFS::seek(static_cast<retro_vfs_file_handle*>(handle), offset, position);
}

int64_t fileTell(void* handle) { return VFS::tell(static_cast<retro_vfs_file_handle*>(handle)); }

size_t fileRead(void* handle, void* buffer, size_t size) {
    int64_t read = VFS::read(static_cast<retro_vfs_file_handle*>(handle), buffer, size);
    return read > 0 ? static_cast<size_t>(read) : 0;
}

void fileClose(void* handle) { VFS::close(static_cast<retro_vfs_file_handle*>(handle)); }

void hashLog(const char* message, const rc_hash_iterator_t*) { LOGI("rcheevos hash: %s", message); }

std::string achievementJson(const rc_client_achievement_t* achievement) {
    char url[256] = "";
    rc_client_achievement_get_image_url(achievement, achievement->unlocked ? RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED
        : RC_CLIENT_ACHIEVEMENT_STATE_ACTIVE, url, sizeof(url));
    std::string out = "{\"id\":" + std::to_string(achievement->id);
    out += ",\"title\":" + json::quote(achievement->title);
    out += ",\"description\":" + json::quote(achievement->description);
    out += ",\"points\":" + std::to_string(achievement->points);
    out += ",\"badge\":" + json::quote(url);
    out += ",\"unlocked\":" + std::to_string(achievement->unlocked);
    out += ",\"unlockTime\":" + std::to_string(static_cast<long long>(achievement->unlock_time));
    out += ",\"progress\":" + json::quote(achievement->measured_progress);
    out += ",\"percent\":" + std::to_string(achievement->measured_percent);
    out += ",\"rarity\":" + std::to_string(achievement->rarity);
    out += ",\"rarityHardcore\":" + std::to_string(achievement->rarity_hardcore);
    out += ",\"type\":" + std::to_string(achievement->type);
    out += ",\"bucket\":" + std::to_string(achievement->bucket);
    out += "}";
    return out;
}

}

void Achievements::enable(const std::string& userAgent, bool hardcore, bool unofficial) {
    if (client == nullptr) {
        client = rc_client_create(&readMemory, &serverCall);
        rc_client_set_userdata(client, this);
        rc_client_set_event_handler(client, &onEvent);
        rc_client_enable_logging(client, RC_CLIENT_LOG_LEVEL_INFO, &onLog);

        rc_hash_callbacks_t callbacks {};
        callbacks.verbose_message = &hashLog;
        callbacks.error_message = &hashLog;
        callbacks.filereader = { &fileOpen, &fileSeek, &fileTell, &fileRead, &fileClose };
        rc_hash_get_default_cdreader(&callbacks.cdreader);
        rc_client_set_hash_callbacks(client, &callbacks);
    }
    rc_client_set_unofficial_enabled(client, unofficial ? 1 : 0);
    rc_client_set_hardcore_enabled(client, hardcore ? 1 : 0);
    // rcheevos appends "rcheevos/x.y" and the integration clause to the product the frontend gives.
    char clause[64] = "";
    rc_client_get_user_agent_clause(client, clause, sizeof(clause));
    std::string agent = userAgent + " " + clause;
    emit("{\"type\":\"userAgent\",\"value\":" + json::quote(agent) + "}");
}

void Achievements::disable() {
    if (client == nullptr) return;
    unloadGame();
    rc_client_destroy(client);
    client = nullptr;
    // Calls still in flight answer into nothing: the frontend drops responses for unknown ids.
    pending.clear();
}

void Achievements::login(const std::string& username, const std::string& password) {
    if (client) rc_client_begin_login_with_password(client, username.c_str(), password.c_str(), &onLogin, this);
}

void Achievements::loginWithToken(const std::string& username, const std::string& token) {
    if (client) rc_client_begin_login_with_token(client, username.c_str(), token.c_str(), &onLogin, this);
}

void Achievements::logout() {
    if (client) rc_client_logout(client);
}

void Achievements::loadGame(Core* loadedCore, const std::string& path, uint32_t consoleId, const std::string& libraryName) {
    if (client == nullptr || loadedCore == nullptr) return;
    unloadGame();
    core = loadedCore;
    library = libraryName;
    if (!rc_libretro_is_system_allowed(library.c_str(), consoleId)) {
        emit("{\"type\":\"game\",\"ok\":false,\"error\":\"unsupported core\"}");
        return;
    }
    memoryCore = loadedCore;
    regionsReady = rc_libretro_memory_init(&regions, Environment::getInstance().getMemoryMap(), &coreMemoryInfo, consoleId) != 0;
    if (!regionsReady) LOGE("rcheevos: no memory exposed for console %u", consoleId);
    rc_client_begin_identify_and_load_game(client, consoleId, path.c_str(), nullptr, 0, &onGameLoaded, this);
}

void Achievements::unloadGame() {
    if (client) rc_client_unload_game(client);
    if (regionsReady) rc_libretro_memory_destroy(&regions);
    regionsReady = false;
    memoryCore = nullptr;
    core = nullptr;
}

void Achievements::setHardcore(bool enabled) {
    if (client == nullptr) return;
    rc_client_set_hardcore_enabled(client, enabled ? 1 : 0);
    if (enabled) enforceSettings();
    emit(std::string("{\"type\":\"hardcore\",\"enabled\":") + (hardcoreActive() ? "true" : "false") + "}");
}

bool Achievements::hardcoreActive() const {
    return client != nullptr && rc_client_get_hardcore_enabled(client) && rc_client_get_game_info(client) != nullptr;
}

void Achievements::doFrame() {
    if (client && core) rc_client_do_frame(client);
}

void Achievements::idle() {
    if (client) rc_client_idle(client);
}

void Achievements::reset() {
    if (client) rc_client_reset(client);
}

void Achievements::stateLoaded() {
    if (client && core) rc_client_deserialize_progress_sized(client, nullptr, 0);
}

bool Achievements::canPause(uint32_t* framesRemaining) {
    if (!client || !hardcoreActive()) return true;
    return rc_client_can_pause(client, framesRemaining) != 0;
}

bool Achievements::settingAllowed(const std::string& key, const std::string& value) const {
    if (!hardcoreActive()) return true;
    const rc_disallowed_setting_t* disallowed = rc_libretro_get_disallowed_settings(library.c_str());
    return disallowed == nullptr || rc_libretro_is_setting_allowed(disallowed, key.c_str(), value.c_str());
}

void Achievements::enforceSettings() {
    if (!hardcoreActive()) return;
    for (const auto& variable : Environment::getInstance().getVariables()) {
        if (settingAllowed(variable.key, variable.value)) continue;
        LOGI("rcheevos: %s=%s is not allowed in hardcore", variable.key.c_str(), variable.value.c_str());
        rc_client_set_hardcore_enabled(client, 0);
        emit("{\"type\":\"hardcoreBlocked\",\"setting\":" + json::quote(variable.key) + "}");
        return;
    }
}

void Achievements::httpResponse(int64_t id, int status, const std::string& body) {
    auto found = pending.find(id);
    if (found == pending.end()) return;
    PendingCall call = found->second;
    pending.erase(found);
    rc_api_server_response_t response {};
    response.body = body.c_str();
    response.body_length = body.size();
    // The frontend reports a request that never reached the server as -1 (retryable).
    response.http_status_code = status > 0 ? status : RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR;
    call.callback(&response, call.data);
}

std::string Achievements::achievementList() {
    if (client == nullptr || rc_client_get_game_info(client) == nullptr) return "[]";
    rc_client_achievement_list_t* list = rc_client_create_achievement_list(
        client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
    if (list == nullptr) return "[]";
    std::string out = "[";
    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const auto& bucket = list->buckets[b];
        if (b) out += ",";
        out += "{\"label\":" + json::quote(bucket.label) + ",\"bucket\":" + std::to_string(bucket.bucket_type) + ",\"achievements\":[";
        for (uint32_t a = 0; a < bucket.num_achievements; a++) {
            if (a) out += ",";
            out += achievementJson(bucket.achievements[a]);
        }
        out += "]}";
    }
    out += "]";
    rc_client_destroy_achievement_list(list);
    return out;
}

void Achievements::emit(std::string json) {
    std::lock_guard<std::mutex> lock(eventLock);
    events.push_back(std::move(json));
}

std::vector<std::string> Achievements::drainEvents() {
    std::lock_guard<std::mutex> lock(eventLock);
    std::vector<std::string> out;
    out.swap(events);
    return out;
}

bool Achievements::hasEvents() {
    std::lock_guard<std::mutex> lock(eventLock);
    return !events.empty();
}

uint32_t Achievements::readMemory(uint32_t address, uint8_t* buffer, uint32_t size, rc_client_t*) {
    return regionsReady ? rc_libretro_memory_read(&regions, address, buffer, size) : 0;
}

void Achievements::serverCall(const rc_api_request_t* request,
                              void (*callback)(const rc_api_server_response_t*, void*), void* data, rc_client_t* client) {
    auto* self = static_cast<Achievements*>(rc_client_get_userdata(client));
    int64_t id = self->nextCallId++;
    self->pending.emplace(id, PendingCall { callback, data });
    std::string out = "{\"type\":\"http\",\"id\":" + std::to_string(id) + ",\"url\":" + json::quote(request->url);
    if (request->post_data) out += ",\"post\":" + json::quote(request->post_data);
    if (request->content_type) out += ",\"contentType\":" + json::quote(request->content_type);
    out += "}";
    self->emit(std::move(out));
}

void Achievements::onLog(const char* message, const rc_client_t*) {
    LOGI("rcheevos: %s", message);
}

void Achievements::onLogin(int result, const char* error, rc_client_t* client, void* userdata) {
    auto* self = static_cast<Achievements*>(userdata);
    const rc_client_user_t* user = rc_client_get_user_info(client);
    if (result != RC_OK || user == nullptr) {
        self->emit("{\"type\":\"login\",\"ok\":false,\"code\":" + std::to_string(result) +
                   ",\"error\":" + json::quote(error ? error : rc_error_str(result)) + "}");
        return;
    }
    char avatar[256] = "";
    rc_client_user_get_image_url(user, avatar, sizeof(avatar));
    self->emit("{\"type\":\"login\",\"ok\":true,\"username\":" + json::quote(user->username) +
               ",\"displayName\":" + json::quote(user->display_name) + ",\"token\":" + json::quote(user->token) +
               ",\"score\":" + std::to_string(user->score) + ",\"softcoreScore\":" + std::to_string(user->score_softcore) +
               ",\"avatar\":" + json::quote(avatar) + "}");
}

void Achievements::onGameLoaded(int result, const char* error, rc_client_t* client, void* userdata) {
    auto* self = static_cast<Achievements*>(userdata);
    if (result != RC_OK) {
        self->emit("{\"type\":\"game\",\"ok\":false,\"code\":" + std::to_string(result) +
                   ",\"error\":" + json::quote(error ? error : rc_error_str(result)) + "}");
        return;
    }
    self->enforceSettings();
    self->emitGame();
}

void Achievements::emitGame() {
    const rc_client_game_t* game = rc_client_get_game_info(client);
    if (game == nullptr) return;
    rc_client_user_game_summary_t summary {};
    rc_client_get_user_game_summary(client, &summary);
    char badge[256] = "";
    rc_client_game_get_image_url(game, badge, sizeof(badge));
    emit("{\"type\":\"game\",\"ok\":true,\"id\":" + std::to_string(game->id) + ",\"title\":" + json::quote(game->title) +
         ",\"badge\":" + json::quote(badge) + ",\"total\":" + std::to_string(summary.num_core_achievements) +
         ",\"unlocked\":" + std::to_string(summary.num_unlocked_achievements) +
         ",\"unsupported\":" + std::to_string(summary.num_unsupported_achievements) +
         ",\"points\":" + std::to_string(summary.points_core) + ",\"pointsUnlocked\":" + std::to_string(summary.points_unlocked) +
         ",\"hardcore\":" + (hardcoreActive() ? "true" : "false") +
         ",\"richPresence\":" + (rc_client_has_rich_presence(client) ? "true" : "false") +
         ",\"leaderboards\":" + (rc_client_has_leaderboards(client) ? "true" : "false") + "}");
}

void Achievements::onEvent(const rc_client_event_t* event, rc_client_t* client) {
    auto* self = static_cast<Achievements*>(rc_client_get_userdata(client));
    auto leaderboard = [](const char* type, const rc_client_leaderboard_t* board) {
        return std::string("{\"type\":\"") + type + "\",\"id\":" + std::to_string(board->id) +
               ",\"title\":" + json::quote(board->title) + ",\"description\":" + json::quote(board->description) +
               ",\"value\":" + json::quote(board->tracker_value) + "}";
    };
    switch (event->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED:
            self->emit("{\"type\":\"unlocked\",\"achievement\":" + achievementJson(event->achievement) +
                       ",\"hardcore\":" + (self->hardcoreActive() ? "true" : "false") + "}");
            break;
        case RC_CLIENT_EVENT_GAME_COMPLETED: {
            const rc_client_game_t* game = rc_client_get_game_info(client);
            self->emit("{\"type\":\"mastered\",\"title\":" + json::quote(game ? game->title : "") +
                       ",\"hardcore\":" + (self->hardcoreActive() ? "true" : "false") + "}");
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_STARTED: self->emit(leaderboard("leaderboardStarted", event->leaderboard)); break;
        case RC_CLIENT_EVENT_LEADERBOARD_FAILED: self->emit(leaderboard("leaderboardFailed", event->leaderboard)); break;
        case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED: self->emit(leaderboard("leaderboardSubmitted", event->leaderboard)); break;
        case RC_CLIENT_EVENT_LEADERBOARD_SCOREBOARD: {
            const auto* board = event->leaderboard_scoreboard;
            self->emit("{\"type\":\"scoreboard\",\"id\":" + std::to_string(board->leaderboard_id) +
                       ",\"submitted\":" + json::quote(board->submitted_score) + ",\"best\":" + json::quote(board->best_score) +
                       ",\"rank\":" + std::to_string(board->new_rank) + ",\"entries\":" + std::to_string(board->num_entries) + "}");
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW:
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE:
            self->emit("{\"type\":\"tracker\",\"id\":" + std::to_string(event->leaderboard_tracker->id) +
                       ",\"value\":" + json::quote(event->leaderboard_tracker->display) + "}");
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE:
            self->emit("{\"type\":\"trackerHide\",\"id\":" + std::to_string(event->leaderboard_tracker->id) + "}");
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW:
            self->emit("{\"type\":\"challenge\",\"achievement\":" + achievementJson(event->achievement) + "}");
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE:
            self->emit("{\"type\":\"challengeHide\",\"id\":" + std::to_string(event->achievement->id) + "}");
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE:
            self->emit("{\"type\":\"progress\",\"achievement\":" + achievementJson(event->achievement) + "}");
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE:
            self->emit("{\"type\":\"progressHide\"}");
            break;
        case RC_CLIENT_EVENT_RESET:
            // Enabling hardcore restarts the console so nothing earlier (a loaded state, a cheat) carries over.
            if (self->resetHandler) self->resetHandler();
            rc_client_reset(client);
            break;
        case RC_CLIENT_EVENT_SERVER_ERROR:
            self->emit("{\"type\":\"serverError\",\"api\":" + json::quote(event->server_error->api) +
                       ",\"error\":" + json::quote(event->server_error->error_message) + "}");
            break;
        case RC_CLIENT_EVENT_DISCONNECTED: self->emit("{\"type\":\"disconnected\"}"); break;
        case RC_CLIENT_EVENT_RECONNECTED: self->emit("{\"type\":\"reconnected\"}"); break;
        default: break;
    }
}

}
