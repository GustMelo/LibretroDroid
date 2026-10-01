#ifndef LIBRETRODROID_ACHIEVEMENTS_H
#define LIBRETRODROID_ACHIEVEMENTS_H

#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

struct rc_client_t;
struct rc_api_server_response_t;
struct rc_api_request_t;
struct rc_client_event_t;

namespace libretrodroid {

class Core;

/**
 * RetroAchievements through rcheevos' rc_client, inside the engine so both platforms evaluate achievements on the
 * same frames from the same memory.
 *
 * The frontend owns the network: every server call comes out of [drainEvents] as an "http" event and goes back in
 * through [httpResponse] (any thread). Everything else the player sees also leaves as JSON events: login, game
 * loaded, unlocks, leaderboards, indicators, connection state. Calls that touch the runtime must hold the engine's
 * core lock, so they never race the emulation thread; [drainEvents] has its own lock.
 *
 * Hardcore mode follows RetroAchievements' rules: while it is active the engine refuses state loads, rewind, slow
 * motion and cheats, and core options on rcheevos' disallowed list.
 */
class Achievements {
public:
    static Achievements& getInstance() {
        static Achievements instance;
        return instance;
    }
    Achievements(Achievements const&) = delete;
    void operator=(Achievements const&) = delete;

    /** Starts the runtime; [userAgent] is "<product>/<version> (<system>)", rcheevos appends its own clause. */
    void enable(const std::string& userAgent, bool hardcore, bool unofficial);
    void disable();
    bool enabled() const { return client != nullptr; }

    void login(const std::string& username, const std::string& password);
    void loginWithToken(const std::string& username, const std::string& token);
    void logout();

    /** After the game is loaded: identifies it by [path] (local or VFS) for [consoleId] (RC_CONSOLE_*). */
    void loadGame(Core* core, const std::string& path, uint32_t consoleId, const std::string& library);
    void unloadGame();

    void setHardcore(bool enabled);
    /** Hardcore and a game with achievements: the engine's restrictions apply. */
    bool hardcoreActive() const;

    void doFrame();
    void idle();
    void reset();
    /** A save state replaced the console's memory: achievements start over from waiting. */
    void stateLoaded();

    /** False while the player paused too recently in hardcore; [framesRemaining] says for how long. */
    bool canPause(uint32_t* framesRemaining);

    /** Whether hardcore allows [key] = [value] for the loaded core. */
    bool settingAllowed(const std::string& key, const std::string& value) const;

    void httpResponse(int64_t id, int status, const std::string& body);

    /** All achievements of the loaded game grouped as rcheevos buckets, as JSON. */
    std::string achievementList();

    /** Pending events as JSON objects, oldest first. Thread-safe. */
    std::vector<std::string> drainEvents();
    bool hasEvents();

    /** Run by the engine when hardcore needs the console restarted (RC_CLIENT_EVENT_RESET). */
    void setResetHandler(std::function<void()> handler) { resetHandler = std::move(handler); }

private:
    Achievements() = default;

    ::rc_client_t* client = nullptr;
    Core* core = nullptr;
    std::string library;
    void* memory = nullptr;
    std::function<void()> resetHandler;

    struct PendingCall {
        void (*callback)(const ::rc_api_server_response_t*, void*);
        void* data;
    };
    std::unordered_map<int64_t, PendingCall> pending;
    int64_t nextCallId = 1;

    std::mutex eventLock;
    std::vector<std::string> events;
    void emit(std::string json);

    static uint32_t readMemory(uint32_t address, uint8_t* buffer, uint32_t size, ::rc_client_t* client);
    static void serverCall(const ::rc_api_request_t* request,
                           void (*callback)(const ::rc_api_server_response_t*, void*), void* data, ::rc_client_t* client);
    static void onEvent(const ::rc_client_event_t* event, ::rc_client_t* client);
    static void onLog(const char* message, const ::rc_client_t* client);
    static void onLogin(int result, const char* error, ::rc_client_t* client, void* userdata);
    static void onGameLoaded(int result, const char* error, ::rc_client_t* client, void* userdata);

    void emitGame();
    void enforceSettings();
};

namespace json {
std::string quote(const std::string& text);
inline std::string quote(const char* text) { return quote(std::string(text ? text : "")); }
}

}

#endif
