#ifndef RETROENGINE_H
#define RETROENGINE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RE_SHADER_DEFAULT 0
#define RE_SHADER_CRT 1
#define RE_SHADER_LCD 2
#define RE_SHADER_SHARP 3
#define RE_SHADER_UPSCALE_CUT 4
#define RE_SHADER_UPSCALE_CUT2 5
#define RE_SHADER_UPSCALE_CUT3 6
/* params: SMOOTH=0|1;GRID=0..1;SUBPIXEL=0..1;SCANLINES=0..1;BRIGHTNESS=0.5..1.5 */
#define RE_SHADER_RETRO 7

#define RE_SENSOR_ACCELEROMETER 1
#define RE_SENSOR_GYROSCOPE 2
#define RE_SENSOR_ILLUMINANCE 4

#define RE_NETPLAY_LOCAL_INPUT 0
#define RE_NETPLAY_STATE_HASH 1

typedef struct re_config {
    const char *core_path;
    const char *system_dir;
    const char *saves_dir;
    const char *language;
    float refresh_rate;
    int shader;
    /** Core options applied before the core starts (those marked "(Restart)" only take effect this way). */
    const char *const *variable_keys;
    const char *const *variable_values;
    int variable_count;
} re_config;

const char *re_last_error(void);

bool re_attach_layer(void *layer);
void re_detach_layer(void);

bool re_create(const re_config *config);
bool re_load_game(const char *path);
bool re_surface_created(void);
void re_surface_changed(int width, int height);
void re_resume(void);
void re_pause(void);
void re_destroy(void);

typedef void (*re_outgoing_fn)(void *context, int type, uint32_t frame, uint64_t value);
void re_frame(re_outgoing_fn outgoing, void *context);

void re_set_buttons(unsigned port, uint16_t mask);

void re_set_viewport(float x, float y, float width, float height);
float re_aspect_ratio(void);
void re_set_aspect_ratio_override(float ratio);
void re_set_variable(const char *key, const char *value);
bool re_jit_available(void);
size_t re_core_version(char *out, size_t capacity);

uint8_t *re_serialize(size_t *size);
bool re_unserialize(const uint8_t *data, size_t size);
uint8_t *re_serialize_sram(size_t *size);
bool re_unserialize_sram(const uint8_t *data, size_t size);

/* Consoles linked inside a retrolink core (console p on controller port p); max is 1 without one. */
int re_link_max_players(void);
bool re_link_set_players(int count);
void re_link_set_local(int player);
void re_link_set_grid(bool grid);
bool re_link_load_save(int player, const uint8_t *data, size_t size);
/* Console [player] goes on alone as console 0, with its state and save. */
bool re_link_keep(int player);

/* e-Reader: whether the running game scans cards, and queue one card's .raw dot code strip. */
bool re_ereader_supported(void);
bool re_ereader_scan(const uint8_t *data, size_t size);
void re_free(void *data);
void re_reset(void);

void re_redraw(void);
uint8_t *re_observe_video(size_t *size);
uint8_t *re_capture(int *width, int *height);

int re_disk_count(void);
int re_disk_current(void);
void re_disk_set(unsigned index);

void re_netplay_start(int local_port, int players, int input_delay, int hash_interval, bool rollback);
void re_netplay_stop(void);
void re_netplay_set_input(int port, uint32_t frame, uint16_t buttons);

typedef void (*re_netpacket_send_fn)(void *context, int flags, const void *data, size_t size,
                                     uint16_t client_id, bool broadcast);
void re_netpacket_set_transport(void *context, re_netpacket_send_fn send);
bool re_netpacket_start(uint16_t client_id);
void re_netpacket_receive(const void *data, size_t size, uint16_t client_id);
void re_netpacket_poll(void);
void re_netpacket_stop(void);
typedef void (*re_netpacket_poll_fn)(void *context);
void re_netpacket_set_poll(re_netpacket_poll_fn poll);
bool re_netpacket_connected(uint16_t client_id);
void re_netpacket_disconnected(uint16_t client_id);

/* Analog input for any port. source: 0 d-pad, 1 left stick, 2 right stick; axes in [-1, 1], +y down. */
void re_set_motion(unsigned port, int source, float x, float y);
/* Multitap on port 2 when the core offers one (up to four players); false restores a plain pad.
 * Returns whether the multitap is now active. */
bool re_set_multitap(bool enabled);

/*
 * Streaming. After re_frame, on the render thread: renders the frame again into an offscreen
 * width x height RGBA target and hands over the previous frame's pixels (top row first),
 * read back asynchronously so the GPU is never stalled. Valid only during the callback.
 */
typedef void (*re_video_fn)(void *context, const uint8_t *rgba, int width, int height);
void re_stream_frame(int width, int height, re_video_fn video, void *context);
void re_stream_stop(void);
/* Game audio as played: 48 kHz interleaved stereo, called on the audio thread. NULL removes it. */
typedef void (*re_audio_fn)(void *context, const int16_t *frames, size_t count);
void re_set_audio_tap(re_audio_fn audio, void *context);
/* While tapped: true plays silence on this device, so the sound is heard only where it streams to. Removing the
 * tap turns it off. */
void re_set_audio_tap_only(bool tap_only);

/* Speed: 1 normal, 0.1..0.99 slow motion, >1 fast-forward up to 100x, 0 as fast as possible. */
void re_set_speed(float speed);
/* Frames run per displayed frame lately (negative while rewinding). */
float re_effective_speed(void);

/* Run-ahead: show the frame `frames` ahead (0..6) to remove the games' built-in input lag; 0 turns it off. */
void re_set_run_ahead(unsigned frames);
/** How loud the game plays on this device, 0 (silent) to 1; a stream to another screen keeps its full volume. */
void re_set_audio_volume(float volume);
/** False paces frames by the clock instead of the display's refresh, even when both rates match. */
void re_set_vsync(bool enabled);
/** Whether a late frame is made up by running two and drawing the second (clock pacing only). */
void re_set_frame_skip(bool enabled);

/* Rewind history kept within budget_bytes; 0 turns it off. */
void re_set_rewind(size_t budget_bytes);
void re_set_rewinding(bool rewinding);
float re_rewind_seconds(void);

/* Sensors the core switched on (RE_SENSOR_* mask); readings by libretro id: accel x/y/z (m/s^2) 0-2,
   gyro x/y/z (rad/s) 3-5, illuminance (lux) 6. */
uint32_t re_sensors_requested(void);
void re_set_sensor(unsigned id, float value);

typedef void (*re_rumble_fn)(void *context, int port, float weak, float strong);
void re_set_rumble_enabled(bool enabled);
/* Calls rumble for every port whose motors changed since the last poll. */
void re_poll_rumble(re_rumble_fn rumble, void *context);

void re_cheat_reset(void);
void re_cheat_set(unsigned index, bool enabled, const char *code);

/* params: "KEY=VALUE;KEY=VALUE" (see RE_SHADER_RETRO), or NULL. */
void re_set_shader(int shader, const char *params);

/* Core options as a JSON array of {key, value, description}; the description is the core's
   "Label; value1|value2|..." string. Free with re_free. */
char *re_variables_json(void);

/* RetroAchievements (rcheevos rc_client inside the engine). Server calls and everything to show come out
   of re_ra_events as a JSON array (NULL when there is nothing); answer "http" events with re_ra_http_response. */
void re_ra_enable(const char *user_agent, bool hardcore, bool unofficial);
void re_ra_disable(void);
void re_ra_login(const char *username, const char *secret, bool is_token);
void re_ra_logout(void);
void re_ra_load_game(const char *path, uint32_t console_id);
void re_ra_set_hardcore(bool enabled);
bool re_ra_hardcore(void);
void re_ra_http_response(int64_t id, int status, const char *body, size_t length);
void re_ra_idle(void);
char *re_ra_list(void);
bool re_ra_can_pause(uint32_t *frames_remaining);
char *re_ra_events(void);

#ifdef __cplusplus
}
#endif

#endif
