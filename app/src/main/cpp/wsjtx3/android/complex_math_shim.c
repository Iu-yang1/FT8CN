#include <complex.h>
#include <math.h>

/*
 * Android NDK 某些 API 级别不会导出 cabs/cabsf/cabsl 符号，
 * 但官方 WSJT-X Fortran core 会通过编译器运行时引用它们。
 * 这里提供最小兼容实现，只补平台缺失符号，不改算法行为。
 */
#ifdef cabs
#undef cabs
#endif
double cabs(double _Complex value) {
    return hypot(creal(value), cimag(value));
}

#ifdef cabsf
#undef cabsf
#endif
float cabsf(float _Complex value) {
    return hypotf(crealf(value), cimagf(value));
}

#ifdef cabsl
#undef cabsl
#endif
long double cabsl(long double _Complex value) {
    return hypotl(creall(value), cimagl(value));
}
