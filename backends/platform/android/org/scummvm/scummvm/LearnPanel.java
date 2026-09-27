package org.scummvm.scummvm;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * ScummLearn overlay for kids.
 *
 * - A Hebrew subtitle bar at the bottom of the screen shows the translation of the
 *   line currently spoken in the game (lines arrive from C++ via JNI::learnLine).
 *   It disappears when the game ends the line ("clear"), unless the child is holding
 *   a finger on it: then the game pauses and the subtitle stays until release.
 * - A column of big buttons on the left: Hebrew subtitles on/off, inventory, skip line.
 *
 * Translations come from res/raw/learn_dict.json, bundled in the APK (works offline).
 */
public class LearnPanel {
	public interface PauseCallback {
		void setPaused(boolean paused);
	}

	public interface InputSender {
		void rightClick();
		void pressKey(int androidKeyCode, int unicode);
	}

	private static final String PREFS = "scummlearn";
	private static final long MAX_SHOW_MS = 12000;    // safety net if no "clear" arrives
	private static final long VIDEO_SHOW_MS = 4000;   // cutscene lines have no "clear"

	private final Activity _activity;
	private final FrameLayout _root;
	private final PauseCallback _pause;
	private final InputSender _input;
	private final Handler _ui = new Handler(Looper.getMainLooper());
	private final Map<String, String> _lines = new HashMap<>();

	private TextView _subtitle;
	private TextView _toggle;
	private boolean _enabled;
	private boolean _holding = false;
	private boolean _clearPending = false;
	private final Runnable _autoHide = this::hideIfNotHeld;

	public LearnPanel(Activity activity, FrameLayout root, PauseCallback pause, InputSender input) {
		_activity = activity;
		_root = root;
		_pause = pause;
		_input = input;
		_enabled = getPrefs().getBoolean("subtitles_he", true);
		loadDict();
		createSubtitle();
		createButtons();
	}

	private SharedPreferences getPrefs() {
		return _activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
	}

	private int dp(float v) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
			_activity.getResources().getDisplayMetrics());
	}

	private static String norm(String s) {
		return s.replaceAll("\\s+", " ").trim();
	}

	private void loadDict() {
		try (InputStream in = _activity.getResources().openRawResource(R.raw.learn_dict)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
			JSONObject lines = new JSONObject(out.toString("UTF-8")).getJSONObject("lines");
			for (Iterator<String> it = lines.keys(); it.hasNext(); ) {
				String en = it.next();
				_lines.put(norm(en), lines.getString(en));
			}
		} catch (Exception e) {
			// No dictionary: subtitles simply stay hidden.
		}
	}

	// ---- UI -----------------------------------------------------------------

	private void createSubtitle() {
		_subtitle = new TextView(_activity);
		_subtitle.setTextColor(Color.WHITE);
		_subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
		_subtitle.setGravity(Gravity.CENTER);
		_subtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
		_subtitle.setPadding(dp(18), dp(10), dp(18), dp(10));
		_subtitle.setShadowLayer(4, 0, 2, Color.BLACK);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xB0000000);
		bg.setCornerRadius(dp(14));
		_subtitle.setBackground(bg);
		_subtitle.setVisibility(View.GONE);

		// Touch and hold keeps the subtitle on screen (and pauses the game).
		_subtitle.setOnTouchListener((v, e) -> {
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					_holding = true;
					_ui.removeCallbacks(_autoHide);
					bg.setColor(0xE0102040);
					_pause.setPaused(true);
					return true;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					_holding = false;
					bg.setColor(0xB0000000);
					_pause.setPaused(false);
					if (_clearPending)
						hide();
					return true;
			}
			return true;
		});

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
			Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		lp.setMargins(dp(96), 0, dp(96), dp(18));
		_root.addView(_subtitle, lp);
	}

	private TextView makeButton(String label, int color) {
		TextView b = new TextView(_activity);
		b.setText(label);
		b.setTextColor(Color.WHITE);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		b.setGravity(Gravity.CENTER);
		GradientDrawable bg = new GradientDrawable();
		bg.setShape(GradientDrawable.OVAL);
		bg.setColor(color);
		bg.setStroke(dp(2), Color.WHITE);
		b.setBackground(bg);
		return b;
	}

	private void createButtons() {
		LinearLayout col = new LinearLayout(_activity);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setGravity(Gravity.CENTER_HORIZONTAL);

		_toggle = makeButton("עב", 0xCC2E6BE6);
		_toggle.setOnClickListener(v -> setEnabled(!_enabled));
		updateToggle();

		TextView inv = makeButton("🎒", 0xCC7A4E1D);
		inv.setOnClickListener(v -> _input.rightClick());

		// '.' skips the current line of dialogue in SCUMM games
		TextView skip = makeButton("⏩", 0xCC3C8C3C);
		skip.setOnClickListener(v -> _input.pressKey(56 /* KEYCODE_PERIOD */, '.'));

		int size = dp(60);
		for (TextView b : new TextView[]{_toggle, inv, skip}) {
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
			lp.setMargins(0, dp(8), 0, dp(8));
			col.addView(b, lp);
		}

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
			Gravity.START | Gravity.CENTER_VERTICAL);
		lp.setMargins(dp(12), 0, 0, 0);
		_root.addView(col, lp);
		col.bringToFront();
	}

	private void updateToggle() {
		_toggle.setAlpha(_enabled ? 1f : 0.45f);
		_toggle.setText(_enabled ? "עב" : "EN");
	}

	private void setEnabled(boolean on) {
		_enabled = on;
		getPrefs().edit().putBoolean("subtitles_he", on).apply();
		updateToggle();
		if (!on)
			hide();
	}

	private void show(String he, long maxMs) {
		_clearPending = false;
		_subtitle.setText(he);
		_subtitle.setVisibility(View.VISIBLE);
		_subtitle.bringToFront();
		_ui.removeCallbacks(_autoHide);
		_ui.postDelayed(_autoHide, maxMs);
	}

	private void hide() {
		_clearPending = false;
		_ui.removeCallbacks(_autoHide);
		_subtitle.setVisibility(View.GONE);
	}

	private void hideIfNotHeld() {
		if (_holding)
			_clearPending = true;
		else
			hide();
	}

	// ---- events from the game -------------------------------------------------

	/** Called on the native thread whenever the game shows or ends a line of text. */
	public void addLine(final String json) {
		_ui.post(() -> {
			try {
				JSONObject o = new JSONObject(json);
				String kind = o.optString("kind");
				if ("clear".equals(kind)) {
					hideIfNotHeld();
					return;
				}
				if (!_enabled || _holding)
					return; // while held, keep the current subtitle
				if (!"dialog".equals(kind) && !"video".equals(kind))
					return;
				String he = _lines.get(norm(o.optString("text")));
				if (he == null) {
					hide();
					return;
				}
				show(he, "video".equals(kind) ? VIDEO_SHOW_MS : MAX_SHOW_MS);
			} catch (Exception ignored) {
			}
		});
	}

	public void destroy() {
		_ui.removeCallbacksAndMessages(null);
	}
}
