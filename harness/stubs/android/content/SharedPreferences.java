package android.content;

import java.util.Map;
import java.util.Set;

/** Minimal test-only stub: the subset of SharedPreferences the fork's prefs readers use. */
public interface SharedPreferences {
    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> values);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        void apply();
        boolean commit();
    }

    String getString(String key, String defValue);
    Set<String> getStringSet(String key, Set<String> defValues);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    boolean getBoolean(String key, boolean defValue);
    Map<String, ?> getAll();
    Editor edit();
}
