#ifndef RETROENGINE_H
#define RETROENGINE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RE_SHADER_DEFAULT 0
#define RE_SHADER_SHARP 3

#define RE_NETPLAY_LOCAL_INPUT 0
#define RE_NETPLAY_STATE_HASH 1

typedef struct re_config {
    const char *core_path;
    const char *system_dir;
    const char *saves_dir;
    const char *language;
    float refresh_rate;
    int shader;
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
void re_free(void *data);
void re_reset(void);

void re_redraw(void);
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

#ifdef __cplusplus
}
#endif

#endif
