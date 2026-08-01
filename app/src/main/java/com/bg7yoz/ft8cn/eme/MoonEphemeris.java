package com.bg7yoz.ft8cn.eme;

public final class MoonEphemeris {
    private static final String SOURCE_LABEL = "FT8CN 低精度月面显示模型";
    private static final double J2000 = 2451545.0;
    private static final double UNIX_EPOCH_JD = 2440587.5;
    private static final double EARTH_RADIUS_KM = 6378.137;
    private static final long RANGE_RATE_DELTA_MILLIS = 60_000L;

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

    public static String getSourceLabel() {
        return SOURCE_LABEL;
    }

    /**
     * 当前模型只用于方位显示；完成 WSJT-X/JPL golden oracle 前禁止驱动自动 CAT。
     */
    public static boolean isAutomaticCatQualified() {
        return false;
    }

    public static MoonEphemeris calculate(ObserverLocation observerLocation, long validTimeMillis) {
        if (observerLocation == null
                || !Double.isFinite(observerLocation.latitudeDeg)
                || !Double.isFinite(observerLocation.longitudeDeg)) {
            return unavailable(validTimeMillis);
        }

        MoonPosition position = calculatePosition(observerLocation, validTimeMillis);
        MoonPosition before = calculatePosition(observerLocation, validTimeMillis - RANGE_RATE_DELTA_MILLIS);
        MoonPosition after = calculatePosition(observerLocation, validTimeMillis + RANGE_RATE_DELTA_MILLIS);
        double rangeRateMps = ((after.topocentricDistanceKm - before.topocentricDistanceKm) * 1000.0)
                / (2.0 * RANGE_RATE_DELTA_MILLIS / 1000.0);
        return new MoonEphemeris(
                position.azimuthDeg,
                position.elevationDeg,
                position.topocentricDistanceKm,
                rangeRateMps,
                validTimeMillis,
                true);
    }

    private static MoonPosition calculatePosition(ObserverLocation observerLocation, long timeMillis) {
        double jd = timeMillis / 86_400_000.0 + UNIX_EPOCH_JD;
        double daysSinceJ2000 = jd - J2000;

        // Compact low-precision lunar model. It is enough for display-only EME
        // diagnostics, but not a replacement for a full ephemeris used by CAT control.
        double meanLongitudeDeg = normalizeDeg(218.316 + 13.176396 * daysSinceJ2000);
        double meanAnomalyDeg = normalizeDeg(134.963 + 13.064993 * daysSinceJ2000);
        double argumentLatitudeDeg = normalizeDeg(93.272 + 13.229350 * daysSinceJ2000);
        double eclipticLongitudeDeg = meanLongitudeDeg + 6.289 * sinDeg(meanAnomalyDeg);
        double eclipticLatitudeDeg = 5.128 * sinDeg(argumentLatitudeDeg);
        double geocentricDistanceKm = 385001.0 - 20905.0 * cosDeg(meanAnomalyDeg);
        double obliquityDeg = 23.4397;

        double lonRad = Math.toRadians(eclipticLongitudeDeg);
        double latRad = Math.toRadians(eclipticLatitudeDeg);
        double obliquityRad = Math.toRadians(obliquityDeg);

        double sinDec = Math.sin(latRad) * Math.cos(obliquityRad)
                + Math.cos(latRad) * Math.sin(obliquityRad) * Math.sin(lonRad);
        double decRad = Math.asin(clamp(sinDec, -1.0, 1.0));
        double raRad = Math.atan2(
                Math.sin(lonRad) * Math.cos(obliquityRad)
                        - Math.tan(latRad) * Math.sin(obliquityRad),
                Math.cos(lonRad));
        raRad = normalizeRad(raRad);

        double observerLatRad = Math.toRadians(observerLocation.latitudeDeg);
        double localSiderealDeg = normalizeDeg(
                280.46061837
                        + 360.98564736629 * daysSinceJ2000
                        + observerLocation.longitudeDeg);
        double hourAngleRad = normalizeSignedRad(Math.toRadians(localSiderealDeg) - raRad);

        double sinAlt = Math.sin(observerLatRad) * Math.sin(decRad)
                + Math.cos(observerLatRad) * Math.cos(decRad) * Math.cos(hourAngleRad);
        double elevationRad = Math.asin(clamp(sinAlt, -1.0, 1.0));
        double azimuthRad = Math.atan2(
                Math.sin(hourAngleRad),
                Math.cos(hourAngleRad) * Math.sin(observerLatRad)
                        - Math.tan(decRad) * Math.cos(observerLatRad));
        double azimuthDeg = normalizeDeg(Math.toDegrees(azimuthRad) + 180.0);

        double moonX = geocentricDistanceKm * Math.cos(decRad) * Math.cos(raRad);
        double moonY = geocentricDistanceKm * Math.cos(decRad) * Math.sin(raRad);
        double moonZ = geocentricDistanceKm * Math.sin(decRad);
        double observerRadiusKm = EARTH_RADIUS_KM + observerLocation.altitudeMeters / 1000.0;
        double thetaRad = Math.toRadians(localSiderealDeg);
        double observerX = observerRadiusKm * Math.cos(observerLatRad) * Math.cos(thetaRad);
        double observerY = observerRadiusKm * Math.cos(observerLatRad) * Math.sin(thetaRad);
        double observerZ = observerRadiusKm * Math.sin(observerLatRad);
        double rangeKm = distanceKm(moonX - observerX, moonY - observerY, moonZ - observerZ);

        return new MoonPosition(azimuthDeg, Math.toDegrees(elevationRad), rangeKm);
    }

    private static double sinDeg(double degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    private static double cosDeg(double degrees) {
        return Math.cos(Math.toRadians(degrees));
    }

    private static double distanceKm(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeDeg(double degrees) {
        double result = degrees % 360.0;
        return result < 0.0 ? result + 360.0 : result;
    }

    private static double normalizeRad(double radians) {
        double result = radians % (2.0 * Math.PI);
        return result < 0.0 ? result + 2.0 * Math.PI : result;
    }

    private static double normalizeSignedRad(double radians) {
        double result = normalizeRad(radians);
        return result > Math.PI ? result - 2.0 * Math.PI : result;
    }

    private static final class MoonPosition {
        final double azimuthDeg;
        final double elevationDeg;
        final double topocentricDistanceKm;

        MoonPosition(double azimuthDeg, double elevationDeg, double topocentricDistanceKm) {
            this.azimuthDeg = azimuthDeg;
            this.elevationDeg = elevationDeg;
            this.topocentricDistanceKm = topocentricDistanceKm;
        }
    }
}
