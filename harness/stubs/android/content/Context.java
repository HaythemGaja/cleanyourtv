package android.content;

/** Minimal test-only stub: lets the headless harness compile SreebPrefs/ForkState without Android. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;

    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
