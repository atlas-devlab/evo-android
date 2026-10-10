package com.elia.evoconti;

import android.graphics.Color;

final class CreatureCatalog {
    static final int SPECIES_COUNT = 30;
    static final int STAGES = 8;

    private static final String[] NAMES = {
            "Lumino", "Brumak", "Terralux", "Nibiru", "Ferrofiore",
            "Velocrin", "Marello", "Cindara", "Quartzino", "Nebulisk",
            "Smeraldoon", "Pulsarix", "Glimporo", "Vaporyn", "Radicor",
            "Solkiri", "Auronox", "Fulgorino", "Muschial", "Noctavia",
            "Cristalbo", "Pyroset", "Venturo", "Fluxel", "Orbial",
            "Tundrillo", "Lavandae", "Metallix", "Astrugo", "Eonvale"
    };

    private static final int[] BASE_COLORS = {
            0xFF2E7D6F, 0xFFE16A3D, 0xFF3B67B8, 0xFFD9A441, 0xFF7A9E3F,
            0xFFB24C63, 0xFF2D8DB8, 0xFFC46B2D, 0xFF6B5FB5, 0xFF3A8F65,
            0xFF2F7E9D, 0xFFD08D34, 0xFF9457A6, 0xFF417E55, 0xFFB85C38,
            0xFF5967C4, 0xFFBF5B84, 0xFF438B8B, 0xFF88943D, 0xFF4F5D75,
            0xFF6C8EAD, 0xFFB44B3B, 0xFF3E7B59, 0xFF8C63A9, 0xFFC08B3E,
            0xFF407C9C, 0xFF9D5A8A, 0xFF5C6F7B, 0xFF7757B7, 0xFF5C8C6E
    };

    private CreatureCatalog() {
    }

    static String name(int species) {
        return NAMES[Math.floorMod(species, SPECIES_COUNT)];
    }

    static int baseColor(int species) {
        return BASE_COLORS[Math.floorMod(species, SPECIES_COUNT)];
    }

    static int accentColor(int species, int stage) {
        int base = baseColor(species);
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        hsv[0] = (hsv[0] + 22f + stage * 9f) % 360f;
        hsv[1] = Math.max(0.35f, hsv[1] - 0.1f);
        hsv[2] = Math.min(1f, hsv[2] + 0.18f);
        return Color.HSVToColor(hsv);
    }

    static int chooseSpecies(long seed, int months) {
        int rarityOffset;
        int span;
        if (months >= 120) {
            rarityOffset = 22;
            span = 8;
        } else if (months >= 36) {
            rarityOffset = 14;
            span = 12;
        } else if (months >= 12) {
            rarityOffset = 7;
            span = 15;
        } else {
            rarityOffset = 0;
            span = 18;
        }
        return Math.floorMod((int) seed, span) + rarityOffset;
    }

    static String stageLabel(int stage) {
        int s = Math.max(0, Math.min(STAGES - 1, stage));
        return "Stadio " + (s + 1) + "/8";
    }
}
