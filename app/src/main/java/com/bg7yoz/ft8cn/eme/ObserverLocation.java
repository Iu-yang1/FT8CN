package com.bg7yoz.ft8cn.eme;

import androidx.annotation.Nullable;

import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.google.android.gms.maps.model.LatLng;

public final class ObserverLocation {
    public final String grid;
    public final double latitudeDeg;
    public final double longitudeDeg;
    public final double altitudeMeters;

    public ObserverLocation(String grid,
                            double latitudeDeg,
                            double longitudeDeg,
                            double altitudeMeters) {
        this.grid = grid == null ? "" : grid;
        this.latitudeDeg = latitudeDeg;
        this.longitudeDeg = longitudeDeg;
        this.altitudeMeters = altitudeMeters;
    }

    @Nullable
    public static ObserverLocation fromGrid(String grid) {
        LatLng latLng = MaidenheadGrid.gridToLatLng(grid);
        if (latLng == null) {
            return null;
        }
        return new ObserverLocation(grid, latLng.latitude, latLng.longitude, 0.0);
    }
}
