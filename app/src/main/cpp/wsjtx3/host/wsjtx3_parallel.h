#ifndef FT8CN_WSJTX3_PARALLEL_H
#define FT8CN_WSJTX3_PARALLEL_H

#ifdef __cplusplus
extern "C" {
#endif

int wsjtx3_select_ft8_sync_threads(const long long* capacities,
                                   int capacity_count,
                                   int online_processors);

int wsjtx3_ft8_sync_thread_count(void);

#ifdef __cplusplus
}
#endif

#endif
