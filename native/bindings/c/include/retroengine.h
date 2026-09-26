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

#ifdef __cplusplus
}
#endif

#endif
