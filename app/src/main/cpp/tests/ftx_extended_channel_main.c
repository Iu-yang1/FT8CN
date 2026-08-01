#include <stdio.h>
#include <stdlib.h>

int ftx_run_channel_extended_selftests(int noise_slots_per_mode, int snr_trials);

static int read_positive_environment(const char *name, int fallback) {
    const char *value = getenv(name);
    char *end = NULL;
    long parsed;
    if (value == NULL || value[0] == '\0') {
        return fallback;
    }
    parsed = strtol(value, &end, 10);
    if (end == value || *end != '\0' || parsed < 1 || parsed > 1000000) {
        return fallback;
    }
    return (int) parsed;
}

int main(void) {
    const int noise_slots = read_positive_environment(
            "FT8CN_EXTENDED_NOISE_SLOTS", 100);
    const int snr_trials = read_positive_environment(
            "FT8CN_EXTENDED_SNR_TRIALS", 10);
    const int status = ftx_run_channel_extended_selftests(noise_slots, snr_trials);
    printf("EXTENDED_CHANNEL status=%s noise_slots_per_mode=%d snr_trials=%d\n",
           status == 0 ? "PASS" : "FAIL", noise_slots, snr_trials);
    return status == 0 ? 0 : 1;
}
