package com.elia.evoconti;

import android.location.Location;

import java.util.Calendar;
import java.util.Locale;

final class AvatarContextResolver {
    static final String PLACE_HOME = "home";
    static final String PLACE_NATURE = "nature";
    static final String PLACE_WORK = "work";
    static final String PLACE_SHOP = "shop";
    static final String PLACE_CITY = "city";

    Result resolve(EvoDb db, Calendar now, Location location) {
        EvoDb.AvatarPlace place = null;
        boolean fresh = location != null && Math.abs(System.currentTimeMillis() - location.getTime()) <= 30L * 60L * 1000L;
        if (fresh) place = db.nearestAvatarPlace(location.getLatitude(), location.getLongitude(), 2000);
        String placeType = place == null ? "" : place.placeType;
        boolean movingOutside = fresh && location.hasAccuracy() && location.getAccuracy() <= 35f &&
                location.hasSpeed() && location.getSpeed() >= 0.6f;
        Result result = resolve(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), placeType, movingOutside);
        if (place != null) {
            result.placeLabel = place.label;
            result.locationSource = "GPS locale";
        } else if (movingOutside) {
            result.placeLabel = "In movimento";
            result.locationSource = "GPS locale";
        } else {
            result.placeLabel = "Luogo non impostato";
            result.locationSource = fresh ? "GPS senza etichetta" : "Solo orario";
        }
        return result;
    }

    Result resolve(int hour, int minute, String placeType, boolean movingOutside) {
        Result result = new Result();
        String type = placeType == null ? "" : placeType.toLowerCase(Locale.ITALY);
        if (PLACE_NATURE.equals(type)) result.environment = CreatureView.ENV_NATURE;
        else if (PLACE_SHOP.equals(type)) result.environment = CreatureView.ENV_SHOP;
        else if (PLACE_CITY.equals(type) || movingOutside) result.environment = CreatureView.ENV_CITY;
        else result.environment = CreatureView.ENV_HOME;

        if (hour >= 22 || hour < 7) {
            result.activity = CreatureView.SCENE_SLEEP;
            result.activityLabel = "Sta dormendo";
        } else if ((hour >= 12 && hour < 15) || (hour >= 19 && hour < 22)) {
            result.activity = CreatureView.SCENE_EAT;
            result.activityLabel = result.environment == CreatureView.ENV_NATURE ? "Picnic all'aperto" : "Pausa insieme";
        } else {
            result.activity = CreatureView.SCENE_IDLE;
            result.activityLabel = result.environment == CreatureView.ENV_NATURE ? "Esplora la natura" :
                    result.environment == CreatureView.ENV_SHOP ? "Ti accompagna nella spesa" :
                            result.environment == CreatureView.ENV_CITY ? "Cammina con te" : "E' sveglio";
        }
        return result;
    }

    static final class Result {
        int activity = CreatureView.SCENE_IDLE;
        int environment = CreatureView.ENV_HOME;
        String activityLabel = "E' sveglio";
        String placeLabel = "Luogo non impostato";
        String locationSource = "Solo orario";

        String status() {
            return activityLabel + " | " + placeLabel;
        }
    }
}
