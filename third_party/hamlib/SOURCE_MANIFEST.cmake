# Hamlib is fetched from the pinned Git commit recorded in UPSTREAM.md.
set(FT8CN_HAMLIB_UPSTREAM_COMMIT
    "c7fb0fa1482ee836e57fa0247773ad4d4c2dd54e")

# These LGPL library trees are compiled by scripts/build-hamlib-android.ps1.
set(FT8CN_HAMLIB_LIBRARY_SOURCE_ROOTS
    include/hamlib
    lib
    security
    src
    rigs
    rotators
    amplifiers)

# GPL command-line programs are build-system side effects only and never enter the APK.
set(FT8CN_HAMLIB_EXCLUDED_DISTRIBUTION_ROOTS
    tests
    simulators
    bindings
    c++)

set(FT8CN_HAMLIB_CONFIGURE_OPTIONS
    --disable-static
    --enable-shared
    --without-libusb
    --without-readline
    --without-cxx-binding)
