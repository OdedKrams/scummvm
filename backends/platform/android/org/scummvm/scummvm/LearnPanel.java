package org.scummvm.scummvm;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
	private TextView _hintBtn;
	private TextView _hintCard;
	private JSONObject _hints;
	private String _room = "";
	private final Set<String> _seen = new HashSet<>();
	private final Set<String> _hintShown = new HashSet<>();
	private final Runnable _tick = this::tickHint;
	private boolean _enabled;
	private boolean _holding = false;
	private boolean _clearPending = false;
	// what the subtitle bar shows now: nothing, a spoken line, or a hover label
	private static final int SHOW_NONE = 0, SHOW_LINE = 1, SHOW_HOVER = 2;
	private int _showKind = SHOW_NONE;
	private String _hoverEn = null;
	private TextToSpeech _tts;
	private boolean _ttsReady = false;
	private final Runnable _speakHover = () -> {
		if (_hoverEn != null && KidTouch.sFingerDown && _ttsReady)
			_tts.speak(_hoverEn, TextToSpeech.QUEUE_FLUSH, null, "scummlearn-object");
	};
	private final Runnable _autoHide = this::hideIfNotHeld;

	public LearnPanel(Activity activity, FrameLayout root, PauseCallback pause, InputSender input) {
		_activity = activity;
		_root = root;
		_pause = pause;
		_input = input;
		_enabled = getPrefs().getBoolean("subtitles_he", true);
		loadDict();
		loadHints();
		createSubtitle();
		createHintCard();
		createButtons();
		tickHint();

		_tts = new TextToSpeech(activity.getApplicationContext(), status -> {
			if (status == TextToSpeech.SUCCESS) {
				_tts.setLanguage(Locale.US);
				_tts.setSpeechRate(0.85f);
				_ttsReady = true;
			}
		});
		// Object names show only while the finger is on the screen.
		KidTouch.sOnFingerUp = () -> _ui.post(() -> {
			if (_showKind == SHOW_HOVER && _hoverEn != null)
				hide();
		});
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

	private String readRaw(int id) throws Exception {
		try (InputStream in = _activity.getResources().openRawResource(id)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
			return out.toString("UTF-8");
		}
	}

	private void loadHints() {
		try {
			_hints = new JSONObject(readRaw(R.raw.learn_hints));
		} catch (Exception e) {
			_hints = null;
		}
		Set<String> seen = getPrefs().getStringSet("seen_lines", null);
		if (seen != null)
			_seen.addAll(seen);
		Set<String> shown = getPrefs().getStringSet("hints_shown", null);
		if (shown != null)
			_hintShown.addAll(shown);
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

	private void createHintCard() {
		_hintCard = new TextView(_activity);
		_hintCard.setTextColor(0xFF1A1A1A);
		_hintCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
		_hintCard.setGravity(Gravity.CENTER);
		_hintCard.setTextDirection(View.TEXT_DIRECTION_RTL);
		_hintCard.setLineSpacing(0, 1.15f);
		_hintCard.setPadding(dp(24), dp(20), dp(24), dp(20));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xFFFFF4C2);
		bg.setCornerRadius(dp(18));
		bg.setStroke(dp(3), 0xFFE0A800);
		_hintCard.setBackground(bg);
		_hintCard.setVisibility(View.GONE);
		_hintCard.setOnClickListener(v -> {
			_hintCard.setVisibility(View.GONE);
			_pause.setPaused(false);
		});
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
		lp.setMargins(dp(110), dp(40), dp(110), dp(40));
		_root.addView(_hintCard, lp);
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

		_hintBtn = makeButton("💡", 0xCCE0A800);
		_hintBtn.setOnClickListener(v -> onHintPressed());

		_toggle = makeButton("עב", 0xCC2E6BE6);
		_toggle.setOnClickListener(v -> setEnabled(!_enabled));
		updateToggle();

		TextView inv = makeButton("🎒", 0xCC7A4E1D);
		inv.setOnClickListener(v -> _input.rightClick());

		// '.' skips the current line of dialogue in SCUMM games
		TextView skip = makeButton("⏩", 0xCC3C8C3C);
		skip.setOnClickListener(v -> _input.pressKey(56 /* KEYCODE_PERIOD */, '.'));

		int size = dp(60);
		for (TextView b : new TextView[]{_hintBtn, _toggle, inv, skip}) {
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
		_showKind = SHOW_NONE;
		_hoverEn = null;
		_ui.removeCallbacks(_autoHide);
		_ui.removeCallbacks(_speakHover);
		_subtitle.setVisibility(View.GONE);
	}

	private void hideIfNotHeld() {
		if (_holding)
			_clearPending = true;
		else
			hide();
	}

	// ---- hints ----------------------------------------------------------------

	private long hintReadyAt() {
		return getPrefs().getLong("hint_ready_at", 0);
	}

	private void tickHint() {
		_ui.removeCallbacks(_tick);
		if (_hintBtn == null)
			return;
		long left = hintReadyAt() - System.currentTimeMillis();
		if (left <= 0) {
			_hintBtn.setText("💡");
			_hintBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
			_hintBtn.setAlpha(1f);
		} else {
			long sec = (left + 999) / 1000;
			_hintBtn.setText(String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60));
			_hintBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
			_hintBtn.setAlpha(0.5f);
			_ui.postDelayed(_tick, 1000);
		}
	}

	private boolean isDone(JSONObject step) {
		JSONArray done = step.optJSONArray("done_if");
		if (done == null)
			return false;
		for (int i = 0; i < done.length(); i++)
			if (_seen.contains(norm(done.optString(i))))
				return true;
		return false;
	}

	/** First step of the current room that the child hasn't finished yet. */
	private String pickHint() {
		if (_hints == null)
			return null;
		JSONObject rooms = _hints.optJSONObject("rooms");
		JSONArray steps = rooms != null ? rooms.optJSONArray(_room) : null;
		if (steps == null)
			steps = _hints.optJSONArray("general");
		if (steps == null || steps.length() == 0)
			return null;
		for (int i = 0; i < steps.length(); i++) {
			JSONObject step = steps.optJSONObject(i);
			if (step == null || isDone(step))
				continue;
			String key = _room + ":" + i;
			boolean last = i == steps.length() - 1;
			// A step with no completion marker only shows once, then we move on.
			if (!step.has("done_if") && _hintShown.contains(key) && !last)
				continue;
			_hintShown.add(key);
			getPrefs().edit().putStringSet("hints_shown", new HashSet<>(_hintShown)).apply();
			return step.optString("he");
		}
		JSONObject lastStep = steps.optJSONObject(steps.length() - 1);
		return lastStep != null ? lastStep.optString("he") : null;
	}

	private void onHintPressed() {
		if (System.currentTimeMillis() < hintReadyAt())
			return; // still charging
		String hint = pickHint();
		if (hint == null)
			hint = "אין עדיין רמז למקום הזה. נסה להסתכל (Look at) ולדבר (Talk to) עם כל מה שאפשר.";
		_hintCard.setText(hint + "\n\n(נגיעה כדי לסגור)");
		_hintCard.setVisibility(View.VISIBLE);
		_hintCard.bringToFront();
		_pause.setPaused(true);
		long cooldown = (_hints != null ? _hints.optLong("cooldown_sec", 180) : 180) * 1000;
		getPrefs().edit().putLong("hint_ready_at", System.currentTimeMillis() + cooldown).apply();
		tickHint();
	}

	// ---- events from the game -------------------------------------------------

	/** Called on the native thread whenever the game shows or ends a line of text. */
	public void addLine(final String json) {
		_ui.post(() -> {
			try {
				JSONObject o = new JSONObject(json);
				String kind = o.optString("kind");
				if ("clear".equals(kind)) {
					if (_showKind == SHOW_LINE)
						hideIfNotHeld();
					return;
				}
				if ("choice_end".equals(kind) || "object_end".equals(kind)) {
					if (_showKind == SHOW_HOVER)
						hideIfNotHeld();
					return;
				}
				if ("room".equals(kind)) {
					_room = o.optString("text");
					return;
				}
				String text = norm(o.optString("text"));
				boolean line = "dialog".equals(kind) || "video".equals(kind);
				if (line && _seen.add(text))
					getPrefs().edit().putStringSet("seen_lines", new HashSet<>(_seen)).apply();
				if (!_enabled || _holding)
					return; // while held, keep the current subtitle
				String he = _lines.get(text);

				if (line) {
					if (he == null) {
						hide();
						return;
					}
					show(he, "video".equals(kind) ? VIDEO_SHOW_MS : MAX_SHOW_MS);
					_showKind = SHOW_LINE;
					return;
				}

				boolean choice = "choice".equals(kind);
				boolean object = "object".equals(kind);
				if (!choice && !object)
					return;
				if (_showKind == SHOW_LINE)
					return; // never cover a line that is being spoken
				if (object && !KidTouch.sFingerDown)
					return;
				if (he == null) {
					if (_showKind == SHOW_HOVER)
						hide();
					return;
				}
				if (object) {
					// Object name: English + Hebrew, and say it in English.
					show(text + "\n" + he, MAX_SHOW_MS);
					_hoverEn = text;
					_ui.removeCallbacks(_speakHover);
					_ui.postDelayed(_speakHover, 350);
				} else {
					show(he, MAX_SHOW_MS);
					_hoverEn = null;
				}
				_showKind = SHOW_HOVER;
			} catch (Exception ignored) {
			}
		});
	}

	public void destroy() {
		_ui.removeCallbacksAndMessages(null);
		KidTouch.sOnFingerUp = null;
		if (_tts != null) {
			_tts.shutdown();
			_tts = null;
		}
	}
}
