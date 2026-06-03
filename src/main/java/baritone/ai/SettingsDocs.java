/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * On-demand documentation for every Baritone setting, generated from the Settings.java javadocs by
 * {@code scripts/extract_settings_docs.py} and shipped as {@code baritone_settings_docs.json}.
 * <p>
 * Loaded lazily and only surfaced through the settings tools (list_settings / get_setting / tune)
 * so the docs never sit in the model's context during normal goals. Parsed manually with JsonParser
 * (no reflection) so ProGuard obfuscation cannot break it.
 */
public final class SettingsDocs {

    private static volatile Map<String, String> docs;

    private SettingsDocs() {
    }

    /**
     * @return the description for a setting (case-insensitive name), or "" if unknown/undocumented.
     */
    public static String describe(String settingName) {
        if (settingName == null) {
            return "";
        }
        return load().getOrDefault(settingName.trim().toLowerCase(Locale.ROOT), "");
    }

    /**
     * @return true if the setting's name OR its documentation contains the filter (case-insensitive).
     */
    public static boolean matches(String settingName, String lowerCaseFilter) {
        if (settingName == null || lowerCaseFilter == null || lowerCaseFilter.isEmpty()) {
            return false;
        }
        if (settingName.toLowerCase(Locale.ROOT).contains(lowerCaseFilter)) {
            return true;
        }
        return describe(settingName).toLowerCase(Locale.ROOT).contains(lowerCaseFilter);
    }

    static Map<String, String> load() {
        Map<String, String> local = docs;
        if (local == null) {
            synchronized (SettingsDocs.class) {
                local = docs;
                if (local == null) {
                    docs = local = readResource();
                }
            }
        }
        return local;
    }

    private static Map<String, String> readResource() {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = SettingsDocs.class.getResourceAsStream("/baritone_settings_docs.json")) {
            if (in == null) {
                return Collections.emptyMap();
            }
            JsonObject obj = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
            }
        } catch (Exception ignored) {
            // missing or malformed resource must never break the agent; tools just lose the doc text
            return Collections.emptyMap();
        }
        return out;
    }
}
