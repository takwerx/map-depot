package com.atakmap.android.mapdepot;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

/**
 * "12 mi NE": how far a sheet is from the map, in the units the operator has
 * already told ATAK they want.
 *
 * ATAK keeps that in {@code rab_rng_units_pref}, and the stored value is the
 * {@link Span} type constant -- {@code 0} is ENGLISH, {@code 1} METRIC, {@code 2}
 * NM. Zero is not metric; assuming the obvious ordering showed kilometers to an
 * operator whose ATAK was set to miles, in another plugin. Read on every call:
 * it can change in ATAK's settings while the pane is open.
 */
public final class Distance {

    private Distance() {
    }

    private static final String[] COMPASS = {
            "N", "NE", "E", "SE", "S", "SW", "W", "NW" };

    /** Where the map is looking, or null before there is a map. */
    public static GeoPoint mapCenter() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv == null)
                return null;
            final GeoPointMetaData c = mv.getCenterPoint();
            return c == null ? null : c.get();
        } catch (RuntimeException notThisBuild) {
            return null;
        }
    }

    public static double meters(GeoPoint from, double lat, double lon) {
        return GeoCalculations.distanceTo(from, new GeoPoint(lat, lon));
    }

    /** Bearing from the map to the sheet, degrees clockwise from north. */
    public static double bearing(GeoPoint from, double lat, double lon) {
        return GeoCalculations.bearingTo(from, new GeoPoint(lat, lon));
    }

    public static String format(double meters, double bearing) {
        final int eighth = (int) Math.round(((bearing % 360) + 360) % 360 / 45.0) % 8;
        return format(meters) + " " + COMPASS[eighth];
    }

    public static String format(double meters) {
        try {
            return SpanUtilities.formatType(type(), meters, Span.METER);
        } catch (RuntimeException e) {
            return Math.round(meters) + " m";
        }
    }

    private static int type() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                final SharedPreferences p = PreferenceManager
                        .getDefaultSharedPreferences(mv.getContext());
                return Integer.parseInt(p.getString("rab_rng_units_pref",
                        String.valueOf(Span.ENGLISH)));
            }
        } catch (RuntimeException e) {
            // A malformed preference must not stop the list drawing.
        }
        return Span.ENGLISH;
    }
}
