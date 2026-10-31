package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The arrow-code entry dialog, ported from Sreeb's
 * ArrowPinDialog to the fork's UI style. Dots only, no
 * digits anywhere: the user presses the remote's arrows,
 * BACK deletes (BACK on empty cancels), and OK submits.
 *
 * Two modes:
 * - unlock: the dialog auto-submits exactly when the
 *   entered count reaches the stored pattern length
 *   (Sreeb v3.3); a wrong full sequence clears and stays
 *   open for retry.
 * - setup: enter once, then again to confirm; a mismatch
 *   restarts the flow (Sreeb's SetupFlow).
 *
 * Callbacks run on the UI thread. Nothing here writes
 * prefs — the caller stores the sequence on success.
 */
final class ArrowDialog {
    private ArrowDialog() {}

    /** Unlock variant that hands the entered sequence to the caller for comparison. */
    interface UnlockSeqCallback {
        void onSubmitted(String sequence);
        void onCancel();
    }

    static void showUnlockSeq(Activity activity, String title, int storedLength, UnlockSeqCallback cb) {
        ArrowLock.Entry entry = new ArrowLock.Entry();
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        content.setFocusable(true);
        final TextView dots = new TextView(activity);
        dots.setTextSize(28);
        dots.setTextColor(0xFFFFFFFF);
        content.addView(dots);
        final TextView hint = new TextView(activity);
        hint.setTextSize(14);
        hint.setTextColor(0xFFACB8C1);
        hint.setText(R.string.arrow_hint);
        content.addView(hint);
        Runnable render = () -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < entry.entered(); i++) b.append("\u25CF ");
            if (b.length() == 0) b.append(activity.getString(R.string.arrow_empty));
            dots.setText(b.toString());
        };
        render.run();
        final AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setCancelable(false)
                .create();
        d.setOnKeyListener((dlg, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            ArrowLock.Dir dir = null;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) dir = ArrowLock.Dir.UP;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dir = ArrowLock.Dir.DOWN;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) dir = ArrowLock.Dir.LEFT;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) dir = ArrowLock.Dir.RIGHT;
            if (dir != null) {
                entry.append(dir);
                render.run();
                if (ArrowLock.shouldAutoSubmit(entry.entered(), storedLength)) {
                    String seq = ArrowLock.encode(entry.steps());
                    d.dismiss();
                    cb.onSubmitted(seq);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (entry.submit() == ArrowLock.EntryEvent.SUBMITTED) {
                    String seq = entry.submittedSequence();
                    d.dismiss();
                    cb.onSubmitted(seq);
                } else {
                    hint.setText(R.string.arrow_too_short);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (entry.pressBack() == ArrowLock.EntryEvent.CANCELLED) {
                    d.dismiss();
                    cb.onCancel();
                } else {
                    render.run();
                }
                return true;
            }
            return false;
        });
        d.show();
        content.requestFocus();
    }

    interface SetupCallback {
        void onDone(String sequence);
        void onCancel();
    }

    static void showSetup(Activity activity, String title, SetupCallback cb) {
        ArrowLock.SetupFlow flow = new ArrowLock.SetupFlow();
        ArrowLock.Entry entry = new ArrowLock.Entry();
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        content.setFocusable(true);
        final TextView dots = new TextView(activity);
        dots.setTextSize(28);
        dots.setTextColor(0xFFFFFFFF);
        content.addView(dots);
        final TextView hint = new TextView(activity);
        hint.setTextSize(14);
        hint.setTextColor(0xFFACB8C1);
        hint.setText(R.string.arrow_hint);
        content.addView(hint);
        Runnable render = () -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < entry.entered(); i++) b.append("\u25CF ");
            if (b.length() == 0) b.append(activity.getString(R.string.arrow_empty));
            dots.setText(b.toString());
        };
        render.run();
        final AlertDialog d = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setCancelable(false)
                .create();
        d.setOnKeyListener((dlg, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            ArrowLock.Dir dir = null;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) dir = ArrowLock.Dir.UP;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dir = ArrowLock.Dir.DOWN;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) dir = ArrowLock.Dir.LEFT;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) dir = ArrowLock.Dir.RIGHT;
            if (dir != null) {
                entry.append(dir);
                render.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (entry.submit() != ArrowLock.EntryEvent.SUBMITTED) {
                    hint.setText(R.string.arrow_too_short);
                    return true;
                }
                String seq = entry.submittedSequence();
                ArrowLock.SetupResult r = flow.submit(seq);
                if (r == ArrowLock.SetupResult.DONE) {
                    d.dismiss();
                    cb.onDone(seq);
                } else if (r == ArrowLock.SetupResult.NEED_CONFIRM) {
                    hint.setText(R.string.arrow_confirm);
                    render.run();
                } else {
                    hint.setText(R.string.arrow_mismatch);
                    render.run();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (entry.pressBack() == ArrowLock.EntryEvent.CANCELLED) {
                    d.dismiss();
                    cb.onCancel();
                } else {
                    render.run();
                }
                return true;
            }
            return false;
        });
        d.show();
        content.requestFocus();
    }
}
