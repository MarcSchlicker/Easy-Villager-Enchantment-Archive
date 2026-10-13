package dev.marc.easyvillagerslibrary.merchant;

import java.util.Locale;

/** Shared search ordering, independent of client-only Minecraft classes. */
public final class RollTargetSearch {
    private RollTargetSearch() { }

    public static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }

    public static boolean matches(String query, String searchText) {
        String needle = normalize(query);
        return !needle.isEmpty() && normalize(searchText).contains(needle);
    }

    public static boolean exactName(String query, String name) {
        return !normalize(query).isEmpty() && normalize(query).equals(normalize(name));
    }

    public static int priority(String query, String name) {
        String needle = normalize(query), candidate = normalize(name);
        if (candidate.equals(needle)) return 0;
        return candidate.startsWith(needle) ? 1 : 2;
    }
}
