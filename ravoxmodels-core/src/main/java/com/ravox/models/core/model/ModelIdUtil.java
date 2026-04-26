package com.ravox.models.core.model;

import java.util.Locale;
import java.util.Set;

public final class ModelIdUtil {
    private ModelIdUtil() {
    }

    public static String sanitize(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (allowed) {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String out = sb.toString();
        while (out.startsWith("_")) {
            out = out.substring(1);
        }
        while (out.endsWith("_")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.isEmpty()) {
            return "model";
        }
        return out;
    }

    public static String unique(String baseId, Set<String> existingIds) {
        if (!existingIds.contains(baseId)) {
            return baseId;
        }
        int i = 2;
        while (existingIds.contains(baseId + "_" + i)) {
            i++;
        }
        return baseId + "_" + i;
    }
}
