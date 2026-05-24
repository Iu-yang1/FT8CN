package com.bg7yoz.ft8cn.eme;

public final class MoonEphemeris {
    public final double azimuthDeg;
    public final double elevationDeg;
    public final double distanceKm;
    public final double rangeRateMps;
    public final long validTimeMillis;
    public final boolean available;

    public MoonEphemeris(double azimuthDeg,
                         double elevationDeg,
                         double distanceKm,
                         double rangeRateMps,
                         long validTimeMillis,
                         boolean available) {
        this.azimuthDeg = azimuthDeg;
        this.elevationDeg = elevationDeg;
        this.distanceKm = distanceKm;
        this.rangeRateMps = rangeRateMps;
        this.validTimeMillis = validTimeMillis;
        this.available = available;
    }

    public static MoonEphemeris unavailable(long validTimeMillis) {
        return new MoonEphemeris(Double.NaN, Double.NaN, Double.NaN, Double.NaN, validTimeMillis, false);
    }
}
