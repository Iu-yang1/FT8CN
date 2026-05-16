#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

/*
 * Android/host 裁剪阶段先用最小共享内存桩把官方 FT8/FT4 core 编译打通。
 * 这里不引入 Qt 的 QSharedMemory，只提供当前 decode 主链需要的符号。
 */

static unsigned char *g_shmem_buffer = NULL;
static int g_shmem_size = 0;
static char g_shmem_key[128] = {0};

bool shmem_create(int size) {
    if (size <= 0) {
        return false;
    }

    if (g_shmem_size != size) {
        free(g_shmem_buffer);
        g_shmem_buffer = (unsigned char *) calloc((size_t) size, 1u);
        if (g_shmem_buffer == NULL) {
            g_shmem_size = 0;
            return false;
        }
        g_shmem_size = size;
    }

    return true;
}

void shmem_setkey(const char *key) {
    if (key == NULL) {
        g_shmem_key[0] = '\0';
        return;
    }

    strncpy(g_shmem_key, key, sizeof(g_shmem_key) - 1u);
    g_shmem_key[sizeof(g_shmem_key) - 1u] = '\0';
}

bool shmem_attach(void) {
    return g_shmem_buffer != NULL;
}

void *shmem_address(void) {
    return g_shmem_buffer;
}

int shmem_size(void) {
    return g_shmem_size;
}

bool shmem_lock(void) {
    return true;
}

bool shmem_unlock(void) {
    return true;
}

bool shmem_detach(void) {
    return true;
}
