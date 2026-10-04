package org.scummvm.scummvm;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
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
 * Translations and hints come from the game's own learn pack (learn_pack.json in the game
 * folder, sent by the engine when the game starts), or from the built-in pack for the
 * Curse of Monkey Island demo (res/raw/learn_dict.json + learn_hints.json). All offline.
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
	private final Map<String, String> _ids = new HashMap<>();
	private final Map<String, String> _words = new HashMap<>();
	/** Current game (config domain); progress is kept per game. */
	private String _game = "";

	private TextView _subtitle;
	private TextView _toggle;
	private TextView _hintBtn;
	private TextView _hintCard;
	private LinearLayout _hintBox;      // the hint card + the "already did it" button
	private TextView _skipBtn;
	private static final int SKIP_COST = 3;
	/** Every hint the child has seen, oldest first (kept with each save), and its viewer. */
	private final java.util.List<String> _hintLog = new java.util.ArrayList<>();
	private LinearLayout _logPanel;
	private LinearLayout _logList;
	/** Steps the child marked as done by hand (their Hebrew text; kept with each save). */
	private final Set<String> _manualDone = new HashSet<>();
	private LinearLayout _choicesPanel;
	private LinearLayout _choicesList;
	private String _choicesKey = "";
	private JSONObject _hints;
	private String _room = "";
	private final Set<String> _seen = new HashSet<>();
	private final Set<String> _hintShown = new HashSet<>();
	/** Everything the hero has ever carried in this game (lower case): hints use it as progress. */
	private final Set<String> _had = new HashSet<>();
	/** What the hero carries right now, and what is in the room right now (lower case). */
	private final Set<String> _invNow = new HashSet<>();
	private final Set<String> _roomNow = new HashSet<>();
	private boolean _roomKnown = false;
	/** The last hint shown: showing it again is free. */
	private String _lastHint = null;
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
		if (_hoverEn != null && KidTouch.sFingerDown && _ttsReady) {
			_tts.speak(_hoverEn, TextToSpeech.QUEUE_FLUSH, null, "scummlearn-object");
			reward("O:" + _hoverEn.toLowerCase(Locale.ROOT), 1);
		}
	};
	private final Runnable _autoHide = this::hideIfNotHeld;

	// ---- gold coins: earned by learning English, spent on hints ----
	private static final int HINT_COST = 5;
	private static final int MORE_HINT_COST = 2;     // the detailed level of the same hint
	private static final long FREE_HINT_SEC = 300;   // a free hint every 5 minutes
	private int _coins = 0;
	private final Set<String> _rewarded = new HashSet<>();  // "L:line", "O:object", "C:choice"
	private TextView _coinView;
	private String _lineEn = null;                          // English of the line on screen

	// ---- "Find the ...!" game ----
	private static final long HUNT_FIRST_MS = 15000, HUNT_EVERY_MS = 120000, HUNT_HELP_MS = 45000,
		HUNT_GIVE_UP_MS = 150000;
	private static final int HUNT_MAX_PER_OBJECT = 3;  // the same object can be asked up to 3 times per room
	private final java.util.List<String> _roomObjects = new java.util.ArrayList<>();
	private final Set<String> _found = new HashSet<>();     // "room:name#n", n = 1..3
	private TextView _huntCard;
	private String _huntTarget = null;
	private boolean _huntHelped = false;
	private final Runnable _huntNext = () -> startHunt(false);
	private final Runnable _huntHelp = this::huntHelp;
	/** The finger is resting on the object we're looking for (found on lift, or after a short hold). */
	private boolean _huntOnTarget = false;
	private static final long HUNT_DWELL_MS = 900;
	private final Runnable _huntDwell = () -> {
		if (_huntTarget != null && _huntOnTarget && KidTouch.sFingerDown)
			huntFound();
	};
	private final Runnable _huntGiveUp = () -> endHunt(false);
	private final java.util.Random _rnd = new java.util.Random();
	/** In a conversation (dialogue options shown, and a little while after): no search cards. */
	private boolean _inConversation = false;
	private static final long CONVERSATION_TAIL_MS = 25000;
	private final Runnable _conversationOver = () -> {
		_inConversation = false;
		_ui.removeCallbacks(_huntNext);
		_ui.postDelayed(_huntNext, HUNT_FIRST_MS);
	};

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
		createChoicesPanel();
		createHuntCard();
		createHintLog();
		createButtons();
		createCoinView();
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
			// Lifting the finger on the object we're looking for = "this one!"
			if (_huntTarget != null && _huntOnTarget)
				huntFound();
			_huntOnTarget = false;
			_ui.removeCallbacks(_huntDwell);
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
		// also drop resource IDs like "/CANNON.065/" in front of names
		return s.replaceAll("^\\s*/[A-Za-z0-9_.\\-]*[0-9][A-Za-z0-9_.\\-]*/", "").replaceAll("\\s+", " ").trim();
	}

	/** Built-in pack (the Curse of Monkey Island demo), used when a game has no pack of its own. */
	private void loadDict() {
		try {
			loadPackDict(new JSONObject(readRaw(R.raw.learn_dict)));
		} catch (Exception e) {
			// No dictionary: subtitles simply stay hidden.
		}
	}

	/**
	 * Pack format (learn_pack.json, see dists/scummlearn/README-packs.md):
	 *   {"game": "...", "lines": {english: hebrew}, "ids": {lineId: hebrew},
	 *    "words": {word: {"he": ..., "note": ...}}, "hints": {"cooldown_sec":..., "rooms": {...}}}
	 */
	private void loadPackDict(JSONObject pack) {
		_lines.clear();
		_ids.clear();
		_words.clear();
		JSONObject lines = pack.optJSONObject("lines");
		if (lines != null)
			for (Iterator<String> it = lines.keys(); it.hasNext(); ) {
				String en = it.next();
				_lines.put(norm(en), lines.optString(en));
			}
		JSONObject ids = pack.optJSONObject("ids");
		if (ids != null)
			for (Iterator<String> it = ids.keys(); it.hasNext(); ) {
				String id = it.next();
				_ids.put(id.toUpperCase(Locale.ROOT), ids.optString(id));
			}
		JSONObject words = pack.optJSONObject("words");
		if (words != null)
			for (Iterator<String> it = words.keys(); it.hasNext(); ) {
				String w = it.next();
				JSONObject e = words.optJSONObject(w);
				_words.put(w.toLowerCase(Locale.ROOT), e != null ? e.optString("he") : words.optString(w));
			}
	}

	/** A game started: switch to its own pack if it has one, else keep the built-in one. */
	private void startGame(JSONObject o) {
		String game = o.optString("game");
		if (game.equals(_game))
			return;
		_game = game;
		JSONObject pack = o.optJSONObject("pack");
		if (pack != null) {
			loadPackDict(pack);
			JSONObject hints = pack.optJSONObject("hints");
			_hints = hints;
			Log.d("ScummLearn", "pack for " + game + ": " + _lines.size() + " lines, " + _ids.size()
				+ " ids, " + _words.size() + " words, hints " + (hints != null));
		} else {
			loadDict();
			try {
				_hints = new JSONObject(readRaw(R.raw.learn_hints));
			} catch (Exception e) {
				_hints = null;
			}
			Log.d("ScummLearn", "no pack for " + game + ", using built-in (" + _lines.size() + " lines)");
		}
		loadProgress();
		tickHint();
	}

	/** Progress keys are per game; the demo keeps the keys it always had. */
	private String key(String name) {
		return (_game.isEmpty() || _game.startsWith("comi-demo")) ? name : name + "@" + _game;
	}

	private String translate(String id, String text) {
		if (id != null && !id.isEmpty()) {
			String he = _ids.get(id.toUpperCase(Locale.ROOT));
			if (he != null)
				return he;
		}
		String he = _lines.get(text);
		if (he == null && text.indexOf(' ') < 0)
			he = _words.get(text.toLowerCase(Locale.ROOT)); // single-word object names
		return he;
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
		loadProgress();
	}

	private void loadProgress() {
		_seen.clear();
		_hintShown.clear();
		Set<String> seen = getPrefs().getStringSet(key("seen_lines"), null);
		if (seen != null)
			_seen.addAll(seen);
		Set<String> shown = getPrefs().getStringSet(key("hints_shown"), null);
		if (shown != null)
			_hintShown.addAll(shown);
		_rewarded.clear();
		Set<String> rw = getPrefs().getStringSet(key("rewarded"), null);
		if (rw != null)
			_rewarded.addAll(rw);
		_found.clear();
		Set<String> fd = getPrefs().getStringSet(key("found"), null);
		if (fd != null)
			_found.addAll(fd);
		_coins = getPrefs().getInt(key("coins"), 0);
		_had.clear();
		Set<String> had = getPrefs().getStringSet(key("had"), null);
		if (had != null)
			_had.addAll(had);
		_lastHint = getPrefs().getString(key("last_hint"), null);
		_hintLog.clear();
		try {
			JSONArray hl = new JSONArray(getPrefs().getString(key("hint_log"), "[]"));
			for (int i = 0; i < hl.length(); i++)
				_hintLog.add(hl.optString(i));
		} catch (Exception ignored) {
		}
		_manualDone.clear();
		Set<String> md = getPrefs().getStringSet(key("manual_done"), null);
		if (md != null)
			_manualDone.addAll(md);
		updateCoins();
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
		_hintCard.setOnClickListener(v -> hideCard());

		// "I already did this": when a hint is stuck on a step the child has finished,
		// skip to the next one for a few coins.
		_skipBtn = new TextView(_activity);
		_skipBtn.setText("✓ כבר עשיתי את זה – לרמז הבא (" + SKIP_COST + " 🪙)");
		_skipBtn.setTextColor(Color.WHITE);
		_skipBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		_skipBtn.setGravity(Gravity.CENTER);
		_skipBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
		GradientDrawable sbg = new GradientDrawable();
		sbg.setColor(0xEE3C8C3C);
		sbg.setCornerRadius(dp(14));
		_skipBtn.setBackground(sbg);
		_skipBtn.setOnClickListener(v -> onSkipPressed());

		_hintBox = new LinearLayout(_activity);
		_hintBox.setOrientation(LinearLayout.VERTICAL);
		_hintBox.setGravity(Gravity.CENTER_HORIZONTAL);
		_hintBox.addView(_hintCard, new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		blp.setMargins(0, dp(10), 0, 0);
		_hintBox.addView(_skipBtn, blp);
		_hintBox.setVisibility(View.GONE);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
		lp.setMargins(dp(110), dp(30), dp(110), dp(30));
		_root.addView(_hintBox, lp);
	}

	private void createHintLog() {
		_logPanel = new LinearLayout(_activity);
		_logPanel.setOrientation(LinearLayout.VERTICAL);
		_logPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xF2FFF4C2);
		bg.setCornerRadius(dp(18));
		bg.setStroke(dp(3), 0xFFE0A800);
		_logPanel.setBackground(bg);
		TextView title = new TextView(_activity);
		title.setText("📜 הרמזים שכבר ראית (נגיעה כדי לסגור)");
		title.setTextColor(0xFF5A4300);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		title.setGravity(Gravity.CENTER);
		title.setPadding(0, 0, 0, dp(8));
		_logPanel.addView(title);
		_logList = new LinearLayout(_activity);
		_logList.setOrientation(LinearLayout.VERTICAL);
		android.widget.ScrollView scroll = new android.widget.ScrollView(_activity);
		scroll.addView(_logList);
		_logPanel.addView(scroll);
		_logPanel.setOnClickListener(v -> hideHintLog());
		title.setOnClickListener(v -> hideHintLog());
		_logPanel.setVisibility(View.GONE);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
		lp.setMargins(dp(90), dp(24), dp(90), dp(24));
		_root.addView(_logPanel, lp);
	}

	private void showHintLog() {
		hideCard();
		_logList.removeAllViews();
		if (_hintLog.isEmpty()) {
			TextView t = new TextView(_activity);
			t.setText("עדיין לא ביקשת רמזים. לחץ על 💡 כשאתה תקוע.");
			t.setTextColor(0xFF1A1A1A);
			t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
			t.setGravity(Gravity.CENTER);
			_logList.addView(t);
		}
		for (int i = _hintLog.size() - 1; i >= 0; i--) { // newest first
			TextView t = new TextView(_activity);
			t.setText(_hintLog.get(i));
			t.setTextColor(0xFF1A1A1A);
			t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
			t.setTextDirection(View.TEXT_DIRECTION_RTL);
			t.setPadding(dp(12), dp(10), dp(12), dp(10));
			GradientDrawable ibg = new GradientDrawable();
			ibg.setColor(i == _hintLog.size() - 1 ? 0xFFFFE58A : 0xFFFFFBE6);
			ibg.setCornerRadius(dp(10));
			t.setBackground(ibg);
			t.setOnClickListener(v -> hideHintLog());
			LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
			ilp.setMargins(0, dp(4), 0, dp(4));
			_logList.addView(t, ilp);
		}
		_logPanel.setVisibility(View.VISIBLE);
		_logPanel.bringToFront();
		_pause.setPaused(true);
	}

	private void hideHintLog() {
		if (_logPanel.getVisibility() == View.VISIBLE)
			_pause.setPaused(false);
		_logPanel.setVisibility(View.GONE);
	}

	/** Remember a hint the child saw (once; seeing it again moves it to the top). */
	private void logHint(String text) {
		if (text == null || text.isEmpty())
			return;
		_hintLog.remove(text);
		_hintLog.add(text);
		while (_hintLog.size() > 200)
			_hintLog.remove(0);
		getPrefs().edit().putString(key("hint_log"), new JSONArray(_hintLog).toString()).apply();
	}

	/** Show the card; withSkip = it's a game hint the child could mark as already done. */
	private void showCard(String text, boolean withSkip) {
		_hintCard.setText(text);
		_skipBtn.setVisibility(withSkip ? View.VISIBLE : View.GONE);
		_hintBox.setVisibility(View.VISIBLE);
		_hintBox.bringToFront();
	}

	private void hideCard() {
		if (_hintBox.getVisibility() == View.VISIBLE)
			_pause.setPaused(false);
		_hintBox.setVisibility(View.GONE);
	}

	private boolean cardShown() {
		return _hintBox.getVisibility() == View.VISIBLE;
	}

	private void onSkipPressed() {
		String current = peekHint();
		if (current == null)
			return;
		if (_coins < SKIP_COST) {
			showCard(current + "\n\nכדי לדלג לרמז הבא צריך עוד " + (SKIP_COST - _coins)
				+ " 🪙. מרוויחים מטבעות ב-🔎 חיפוש חפצים.\n\n(נגיעה כדי לסגור)", false);
			return;
		}
		addCoins(-SKIP_COST);
		_manualDone.add(current);
		getPrefs().edit().putStringSet(key("manual_done"), new HashSet<>(_manualDone)).apply();
		Log.d("ScummLearn", "hint skipped by the child: " + current);
		String next = pickHint();
		_lastHint = next;
		getPrefs().edit().putString(key("last_hint"), next).apply();
		if (next == null || next.equals(current)) {
			showCard("זה היה הרמז האחרון שיש לנו למקום הזה. נסה להסתכל ולדבר עם כולם, ולחבר חפצים בתיק 🎒.\n\n(נגיעה כדי לסגור)", false);
			return;
		}
		logHint("💡 " + next);
		String tip = moreHint(next) != null
			? "\n\n(עוד לחיצה על 💡 = רמז מפורט יותר, " + MORE_HINT_COST + " 🪙)" : "";
		showCard("💡 הרמז הבא:\n" + next + tip + "\n\n(נגיעה כדי לסגור)", true);
	}

	// ---- dialogue options panel ----------------------------------------------

	private void createChoicesPanel() {
		_choicesPanel = new LinearLayout(_activity);
		_choicesPanel.setOrientation(LinearLayout.VERTICAL);
		_choicesPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xCC101828);
		bg.setCornerRadius(dp(16));
		_choicesPanel.setBackground(bg);

		TextView title = new TextView(_activity);
		title.setText("מה אפשר להגיד? (נגיעה = להקשיב)");
		title.setTextColor(0xFFB8C4E0);
		title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		title.setGravity(Gravity.CENTER);
		title.setPadding(0, 0, 0, dp(6));
		_choicesPanel.addView(title);

		_choicesList = new LinearLayout(_activity);
		_choicesList.setOrientation(LinearLayout.VERTICAL);
		android.widget.ScrollView scroll = new android.widget.ScrollView(_activity);
		scroll.addView(_choicesList);
		_choicesPanel.addView(scroll);
		_choicesPanel.setVisibility(View.GONE);

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			dp(300), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
		lp.setMargins(0, dp(90), dp(8), dp(24));
		_root.addView(_choicesPanel, lp);
	}

	private void showChoices(String all) {
		if (all.equals(_choicesKey) && _choicesPanel.getVisibility() == View.VISIBLE)
			return;
		_choicesKey = all;
		_choicesList.removeAllViews();
		int shown = 0;
		for (String raw : all.split("\n")) {
			final String en = norm(raw);
			if (en.isEmpty())
				continue;
			String he = translate(null, en);
			TextView card = new TextView(_activity);
			card.setText(he != null ? he + "\n" + en : en);
			card.setTextColor(Color.WHITE);
			card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
			card.setGravity(Gravity.CENTER);
			card.setPadding(dp(12), dp(10), dp(12), dp(10));
			GradientDrawable cbg = new GradientDrawable();
			cbg.setColor(0xFF2B3A67);
			cbg.setCornerRadius(dp(12));
			card.setBackground(cbg);
			card.setOnClickListener(v -> {
				cbg.setColor(0xFF4A6BC4);
				_ui.postDelayed(() -> cbg.setColor(0xFF2B3A67), 600);
				if (_ttsReady)
					_tts.speak(en, TextToSpeech.QUEUE_FLUSH, null, "scummlearn-choice");
			});
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
			clp.setMargins(0, dp(4), 0, dp(4));
			_choicesList.addView(card, clp);
			shown++;
		}
		boolean visible = shown > 0 && _enabled;
		_choicesPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
		if (visible)
			_choicesPanel.bringToFront();
		KidTouch.sHoldEnabled = shown == 0;
	}

	private void hideChoices() {
		_choicesKey = "";
		_choicesPanel.setVisibility(View.GONE);
		_choicesList.removeAllViews();
		KidTouch.sHoldEnabled = true;
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

		TextView log = makeButton("📜", 0xCC8C6A2E);
		log.setOnClickListener(v -> showHintLog());

		TextView hunt = makeButton("🔎", 0xCC8A3FB0);
		hunt.setOnClickListener(v -> {
			if (_huntTarget != null)
				sayHunt();
			else if (!startHunt(true))
				flash("אין פה עוד מה לחפש 🙂");
		});

		TextView inv = makeButton("🎒", 0xCC7A4E1D);
		inv.setOnClickListener(v -> _input.rightClick());

		// '.' skips the current line of dialogue in SCUMM games
		TextView skip = makeButton("⏩", 0xCC3C8C3C);
		skip.setOnClickListener(v -> _input.pressKey(56 /* KEYCODE_PERIOD */, '.'));

		int size = dp(52);
		for (TextView b : new TextView[]{_hintBtn, log, hunt, _toggle, inv, skip}) {
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
			lp.setMargins(0, dp(6), 0, dp(6));
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
		if (!on) {
			hide();
			if (_choicesPanel != null)
				_choicesPanel.setVisibility(View.GONE);
		} else if (_choicesPanel != null && _choicesList.getChildCount() > 0) {
			_choicesPanel.setVisibility(View.VISIBLE);
		}
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
		_lineEn = null;
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
		return getPrefs().getLong(key("hint_ready_at"), 0);
	}

	private void tickHint() {
		_ui.removeCallbacks(_tick);
		if (_hintBtn == null)
			return;
		long left = hintReadyAt() - System.currentTimeMillis();
		if (left > 0 && _coins >= HINT_COST) {
			_hintBtn.setText("💡\n" + HINT_COST + "🪙");
			_hintBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
			_hintBtn.setAlpha(1f);
			_ui.postDelayed(_tick, 1000);
		} else if (left <= 0) {
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
		if (_manualDone.contains(step.optString("he")))
			return true;
		JSONArray done = step.optJSONArray("done_if");
		if (done == null)
			return false;
		for (int i = 0; i < done.length(); i++)
			if (holds(done.optString(i)))
				return true;
		return false;
	}

	/**
	 * One progress marker. Current game state (bag, room) always works, also right after
	 * loading a save; history (lines heard, items carried before) is kept per save slot.
	 *   has:a+b   the hero carries a and b now, or carried them before (used up since)
	 *   now:a     the hero carries a right now
	 *   obj:a     a is in the room right now        noobj:a   a is no longer in the room
	 *   text      this line has been heard
	 */
	private boolean holds(String d) {
		if (d.startsWith("has:") || d.startsWith("now:")) {
			boolean now = d.startsWith("now:");
			for (String item : d.substring(4).split("\\+")) {
				String it = item.trim().toLowerCase(Locale.ROOT);
				if (!_invNow.contains(it) && (now || !_had.contains(it)))
					return false;
			}
			return true;
		}
		if (d.startsWith("obj:"))
			return _roomNow.contains(d.substring(4).trim().toLowerCase(Locale.ROOT));
		if (d.startsWith("noobj:"))
			return _roomKnown && !_roomNow.contains(d.substring(6).trim().toLowerCase(Locale.ROOT));
		return _seen.contains(norm(d));
	}

	// ---- learning progress per save slot ----------------------------------------

	private void snapshotTo(String slot) {
		try {
			JSONObject o = new JSONObject();
			o.put("seen", new JSONArray(_seen));
			o.put("had", new JSONArray(_had));
			o.put("shown", new JSONArray(_hintShown));
			o.put("manual", new JSONArray(_manualDone));
			o.put("log", new JSONArray(_hintLog));
			getPrefs().edit().putString(key("slot_" + slot), o.toString()).apply();
			Log.d("ScummLearn", "progress saved with slot " + slot + ": " + _seen.size() + " lines, " + _had.size() + " items");
		} catch (Exception e) {
			Log.d("ScummLearn", "progress snapshot failed: " + e);
		}
	}

	private void restoreFrom(String slot) {
		String js = getPrefs().getString(key("slot_" + slot), null);
		_lastHint = null;
		// The bag and room lists from before the load are stale: the engine sends fresh ones.
		_invNow.clear();
		_roomNow.clear();
		_roomKnown = false;
		if (js == null) {
			// An older save: nothing tells which lines were heard in it, and the history of
			// another game (lines, items carried, skipped steps) would mark steps done that
			// this save hasn't reached. Start from what the game itself shows: bag and room.
			_seen.clear();
			_had.clear();
			_hintShown.clear();
			_manualDone.clear();
			getPrefs().edit()
				.putStringSet(key("seen_lines"), new HashSet<>())
				.putStringSet(key("had"), new HashSet<>())
				.putStringSet(key("hints_shown"), new HashSet<>())
				.putStringSet(key("manual_done"), new HashSet<>())
				.putString(key("last_hint"), "")
				.apply();
			Log.d("ScummLearn", "no progress stored with slot " + slot + " (older save): history cleared, using the bag and the room");
			return;
		}
		try {
			JSONObject o = new JSONObject(js);
			_seen.clear();
			_had.clear();
			_hintShown.clear();
			JSONArray a = o.optJSONArray("seen");
			for (int i = 0; a != null && i < a.length(); i++)
				_seen.add(a.optString(i));
			a = o.optJSONArray("had");
			for (int i = 0; a != null && i < a.length(); i++)
				_had.add(a.optString(i));
			a = o.optJSONArray("shown");
			for (int i = 0; a != null && i < a.length(); i++)
				_hintShown.add(a.optString(i));
			_manualDone.clear();
			a = o.optJSONArray("manual");
			for (int i = 0; a != null && i < a.length(); i++)
				_manualDone.add(a.optString(i));
			a = o.optJSONArray("log");
			if (a != null) {
				_hintLog.clear();
				for (int i = 0; i < a.length(); i++)
					_hintLog.add(a.optString(i));
			}
			getPrefs().edit()
				.putStringSet(key("seen_lines"), new HashSet<>(_seen))
				.putStringSet(key("had"), new HashSet<>(_had))
				.putStringSet(key("hints_shown"), new HashSet<>(_hintShown))
				.putStringSet(key("manual_done"), new HashSet<>(_manualDone))
				.putString(key("hint_log"), new JSONArray(_hintLog).toString())
				.putString(key("last_hint"), "")
				.apply();
			Log.d("ScummLearn", "progress restored from slot " + slot + ": " + _seen.size() + " lines, " + _had.size() + " items");
		} catch (Exception e) {
			Log.d("ScummLearn", "progress restore failed: " + e);
		}
	}

	/** What pickHint() would return now, without marking anything as shown. */
	private String peekHint() {
		Set<String> saved = new HashSet<>(_hintShown);
		String h = pickHint();
		if (!saved.equals(_hintShown)) {
			_hintShown.clear();
			_hintShown.addAll(saved);
			getPrefs().edit().putStringSet(key("hints_shown"), new HashSet<>(_hintShown)).apply();
		}
		return h;
	}

	/** The detailed level of a hint: the step's own "more" text, or else the next step. */
	private String moreHint(String current) {
		if (_hints == null)
			return null;
		JSONObject rooms = _hints.optJSONObject("rooms");
		JSONArray steps = rooms != null ? rooms.optJSONArray(_room) : null;
		if (steps == null)
			steps = _hints.optJSONArray("general");
		if (steps != null)
			for (int i = 0; i < steps.length(); i++) {
				JSONObject step = steps.optJSONObject(i);
				if (step != null && current.equals(step.optString("he")) && step.has("more"))
					return step.optString("more");
			}
		return nextHint(current);
	}

	/** The step that comes after the given hint in the current room (more specific), if any. */
	private String nextHint(String current) {
		if (_hints == null)
			return null;
		JSONObject rooms = _hints.optJSONObject("rooms");
		JSONArray steps = rooms != null ? rooms.optJSONArray(_room) : null;
		if (steps == null)
			return null;
		for (int i = 0; i + 1 < steps.length(); i++) {
			JSONObject step = steps.optJSONObject(i);
			if (step != null && current.equals(step.optString("he"))) {
				JSONObject next = steps.optJSONObject(i + 1);
				return next != null ? next.optString("he") : null;
			}
		}
		return null;
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
			getPrefs().edit().putStringSet(key("hints_shown"), new HashSet<>(_hintShown)).apply();
			return step.optString("he");
		}
		JSONObject lastStep = steps.optJSONObject(steps.length() - 1);
		return lastStep != null ? lastStep.optString("he") : null;
	}

	private void onHintPressed() {
		boolean free = System.currentTimeMillis() >= hintReadyAt();
		// The same hint again (nothing changed since) costs nothing and doesn't restart the timer.
		String again = peekHint();
		if (again != null && again.equals(_lastHint)) {
			// Still stuck on the same step: offer the detailed hint ("more", or else the
			// next step) for a few coins. Once bought it stays free.
			String more = moreHint(again);
			String bought = key("more_bought");
			Set<String> boughtSet = new HashSet<>(getPrefs().getStringSet(bought, new HashSet<>()));
			String text;
			if (more == null) {
				text = again + "\n\n(זה אותו רמז – בחינם 🙂)";
			} else if (boughtSet.contains(again)) {
				text = again + "\n\n💡💡 רמז מפורט: " + more;
				logHint("💡💡 " + more);
			} else if (_coins >= MORE_HINT_COST) {
				addCoins(-MORE_HINT_COST);
				boughtSet.add(again);
				getPrefs().edit().putStringSet(bought, boughtSet).apply();
				text = again + "\n\n💡💡 רמז מפורט (" + MORE_HINT_COST + " 🪙): " + more;
				logHint("💡💡 " + more);
			} else {
				text = again + "\n\nלרמז מפורט יותר צריך עוד " + (MORE_HINT_COST - _coins)
					+ " 🪙. מרוויחים מטבעות ב-🔎 חיפוש חפצים.";
			}
			Log.d("ScummLearn", "hint room=" + _room + " again, more=" + (more != null) + " coins=" + _coins);
			showCard(text + "\n\n(נגיעה כדי לסגור)", true);
			_pause.setPaused(true);
			return;
		}
		if (!free && _coins < HINT_COST) {
			long sec = (hintReadyAt() - System.currentTimeMillis() + 999) / 1000;
			flash("עוד " + (HINT_COST - _coins) + " 🪙 לרמז, או לחכות "
				+ String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60) + "\nמרוויחים מטבעות מאנגלית: 🔎 חיפוש חפצים, והקשבה לשם של חפץ");
			return;
		}
		String hint = pickHint();
		Log.d("ScummLearn", "hint room=" + _room + " -> " + hint);
		_lastHint = hint;
		getPrefs().edit().putString(key("last_hint"), hint).apply();
		logHint(hint != null ? "💡 " + hint : null);
		if (hint == null)
			hint = "אין עדיין רמז למקום הזה. נסה להסתכל (Look at) ולדבר (Talk to) עם כל מה שאפשר.";
		String tip = moreHint(hint) != null
			? "\n\n(עוד לחיצה על 💡 = רמז מפורט יותר, " + MORE_HINT_COST + " 🪙)" : "";
		showCard(hint + tip + "\n\n(נגיעה כדי לסגור)", _lastHint != null);
		_pause.setPaused(true);
		if (free) {
			long cooldown = FREE_HINT_SEC * 1000;
			getPrefs().edit().putLong(key("hint_ready_at"), System.currentTimeMillis() + cooldown).apply();
		} else {
			addCoins(-HINT_COST);
		}
		tickHint();
	}

	// ---- gold coins ------------------------------------------------------------

	private void createCoinView() {
		_coinView = new TextView(_activity);
		_coinView.setTextColor(0xFF3A2A00);
		_coinView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
		_coinView.setGravity(Gravity.CENTER);
		_coinView.setPadding(dp(12), dp(4), dp(12), dp(4));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xEEFFD54A);
		bg.setCornerRadius(dp(20));
		bg.setStroke(dp(2), 0xFFB8860B);
		_coinView.setBackground(bg);
		_coinView.setOnClickListener(v -> flash("יש לך " + _coins + " 🪙\nכל רמז עולה " + HINT_COST
			+ " 🪙 (או חינם כל 5 דקות)\nמרוויחים: 🔎 מציאת חפץ = 3 (2 אחרי עזרה בעברית), הקשבה לשם של חפץ = 1"));
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
		lp.setMargins(dp(10), dp(10), 0, 0);
		_root.addView(_coinView, lp);
		updateCoins();
	}

	private void updateCoins() {
		if (_coinView != null) {
			_coinView.setText("🪙 " + _coins);
			_coinView.bringToFront();
		}
	}

	private void addCoins(int n) {
		_coins = Math.max(0, _coins + n);
		getPrefs().edit().putInt(key("coins"), _coins).apply();
		updateCoins();
		tickHint();
		Log.d("ScummLearn", "coins " + (n > 0 ? "+" : "") + n + " -> " + _coins);
	}

	/** Pay once for each new thing learned (a line, an object name, a dialogue option). */
	private void reward(String what, int n) {
		if (!_rewarded.add(what))
			return;
		getPrefs().edit().putStringSet(key("rewarded"), new HashSet<>(_rewarded)).apply();
		addCoins(n);
		popCoin("+" + n + " 🪙");
	}

	/** A small "+1 🪙" that floats up from the coin counter. */
	private void popCoin(String label) {
		if (_coinView == null)
			return;
		final TextView t = new TextView(_activity);
		t.setText(label);
		t.setTextColor(0xFFFFD54A);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		t.setShadowLayer(4, 0, 2, Color.BLACK);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
		lp.setMargins(dp(24), dp(56), 0, 0);
		_root.addView(t, lp);
		t.animate().translationY(-dp(40)).alpha(0f).setDuration(1400)
			.withEndAction(() -> _root.removeView(t)).start();
	}

	/** A short message in the hint card style that closes by itself. */
	private void flash(String msg) {
		showCard(msg, false);
		_ui.postDelayed(() -> {
			if (_hintCard.getText().toString().equals(msg))
				_hintBox.setVisibility(View.GONE);
		}, 4000);
	}

	// ---- "Find the ...!" --------------------------------------------------------

	private void createHuntCard() {
		_huntCard = new TextView(_activity);
		_huntCard.setTextColor(Color.WHITE);
		_huntCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
		_huntCard.setGravity(Gravity.CENTER);
		_huntCard.setPadding(dp(18), dp(8), dp(18), dp(8));
		_huntCard.setShadowLayer(3, 0, 2, Color.BLACK);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xDD6A2C91);
		bg.setCornerRadius(dp(16));
		bg.setStroke(dp(2), 0xFFE7C6FF);
		_huntCard.setBackground(bg);
		_huntCard.setVisibility(View.GONE);
		// Tapping the card says the sentence again (with a short flash so the child sees it worked).
		_huntCard.setOnClickListener(v -> {
			Log.d("ScummLearn", "hunt card tapped: say again");
			v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120)
				.withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
			sayHunt();
		});
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
		lp.setMargins(dp(100), dp(10), dp(100), 0);
		_root.addView(_huntCard, lp);
	}

	private void setRoomObjects(String all) {
		_roomObjects.clear();
		_roomNow.clear();
		_roomKnown = true;
		for (String raw : all.split("\n")) {
			String en = norm(raw).toLowerCase(Locale.ROOT);
			if (!en.isEmpty() && !en.equals("-"))
				_roomNow.add(en);
		}
		Set<String> uniq = new HashSet<>();
		for (String raw : all.split("\n")) {
			String en = norm(raw);
			if (en.length() < 2 || en.length() > 24 || en.split(" ").length > 3 || en.startsWith("-"))
				continue;
			if (translate(null, en) == null || !uniq.add(en.toLowerCase(Locale.ROOT)))
				continue;
			_roomObjects.add(en);
		}
		Log.d("ScummLearn", "room " + _room + " objects " + _roomObjects);
		boolean targetGone = _huntTarget != null;
		for (String en : _roomObjects)
			if (en.equalsIgnoreCase(String.valueOf(_huntTarget)))
				targetGone = false;
		if (targetGone)
			endHunt(false); // picked up or gone from the room
		else if (_huntTarget == null) {
			_ui.removeCallbacks(_huntNext);
			_ui.postDelayed(_huntNext, HUNT_FIRST_MS);
		}
	}

	/** The object under the finger is the one we're looking for (or overlaps it). */
	private boolean matchesHunt(String name, String alsoUnder) {
		if (name.equalsIgnoreCase(_huntTarget))
			return true;
		for (String other : alsoUnder.split("\n"))
			if (norm(other).equalsIgnoreCase(_huntTarget))
				return true;
		return false;
	}

	/** Start a new search. asked = the child pressed 🔎 (then don't wait for a quiet moment). */
	private boolean startHunt(boolean asked) {
		_ui.removeCallbacks(_huntNext);
		if (_huntTarget != null)
			return true;
		if (_inConversation) {
			if (asked)
				flash("נחפש אחרי השיחה 🙂");
			return true; // resumes by itself when the conversation is over
		}
		if (!asked && (_choicesPanel.getVisibility() == View.VISIBLE || cardShown()
				|| _showKind == SHOW_LINE)) {
			_ui.postDelayed(_huntNext, 10000); // busy now, try again soon
			return false;
		}
		java.util.List<String> left = new java.util.ArrayList<>();
		for (String en : _roomObjects)
			if (timesFound(en) < HUNT_MAX_PER_OBJECT)
				left.add(en);
		if (left.size() > 1)
			left.remove(_lastHunt);   // not the same object twice in a row
		if (left.isEmpty())
			return false;
		_huntTarget = left.get(_rnd.nextInt(left.size()));
		_lastHunt = _huntTarget;
		_huntHelped = false;
		_huntCard.setText("🔎 " + huntPhrase(_huntTarget) + "  🔊");
		_huntCard.setVisibility(View.VISIBLE);
		_huntCard.bringToFront();
		sayHunt();
		_ui.postDelayed(_huntHelp, HUNT_HELP_MS);
		_ui.postDelayed(_huntGiveUp, HUNT_GIVE_UP_MS);
		Log.d("ScummLearn", "hunt start " + _huntTarget);
		return true;
	}

	private String _lastHunt = null;

	private int timesFound(String en) {
		String k = _room + ":" + en.toLowerCase(Locale.ROOT) + "#";
		int n = 0;
		while (n < HUNT_MAX_PER_OBJECT && _found.contains(k + (n + 1)))
			n++;
		return n;
	}

	private static String huntPhrase(String en) {
		String low = en.toLowerCase(Locale.ROOT);
		boolean bare = Character.isUpperCase(en.charAt(0)) || low.startsWith("the ") || low.startsWith("a ")
			|| low.startsWith("an ") || low.startsWith("some ");
		return "Find " + (bare ? "" : "the ") + en + "!";
	}

	private void sayHunt() {
		if (_huntTarget != null && _ttsReady)
			_tts.speak(huntPhrase(_huntTarget), TextToSpeech.QUEUE_FLUSH, null, "scummlearn-hunt");
	}

	/** Still not found after a while: show the Hebrew too (the find is then worth a bit less). */
	private void huntHelp() {
		if (_huntTarget == null)
			return;
		String he = translate(null, _huntTarget);
		if (he == null)
			return;
		_huntHelped = true;
		_huntCard.setText("🔎 " + huntPhrase(_huntTarget) + "  🔊\n" + he);
		sayHunt();
	}

	private void huntFound() {
		_huntOnTarget = false;
		_ui.removeCallbacks(_huntDwell);
		if (_huntTarget == null)
			return;
		String en = _huntTarget;
		int n = _huntHelped ? 2 : 3;
		_found.add(_room + ":" + en.toLowerCase(Locale.ROOT) + "#" + (timesFound(en) + 1));
		getPrefs().edit().putStringSet(key("found"), new HashSet<>(_found)).apply();
		_rewarded.add("O:" + en.toLowerCase(Locale.ROOT)); // no extra coin for hearing it now
		addCoins(n);
		popCoin("+" + n + " 🪙");
		String he = translate(null, en);
		_huntCard.setText("✔ " + en + (he != null ? " = " + he : "") + "   +" + n + " 🪙");
		if (_ttsReady)
			_tts.speak("Yes! " + huntPhrase(en).replaceFirst("^Find ", ""), TextToSpeech.QUEUE_FLUSH, null, "scummlearn-hunt");
		Log.d("ScummLearn", "hunt found " + en + " +" + n);
		_huntTarget = null;
		_ui.removeCallbacks(_huntHelp);
		_ui.removeCallbacks(_huntGiveUp);
		_ui.postDelayed(() -> {
			if (_huntTarget == null)
				_huntCard.setVisibility(View.GONE);
		}, 3000);
		_ui.postDelayed(_huntNext, HUNT_EVERY_MS);
	}

	private void endHunt(boolean unused) {
		_ui.removeCallbacks(_huntHelp);
		_ui.removeCallbacks(_huntGiveUp);
		_ui.removeCallbacks(_huntNext);
		if (_huntTarget != null)
			Log.d("ScummLearn", "hunt dropped " + _huntTarget);
		_huntTarget = null;
		if (_huntCard != null)
			_huntCard.setVisibility(View.GONE);
		if (!_roomObjects.isEmpty() && !_inConversation)
			_ui.postDelayed(_huntNext, HUNT_EVERY_MS);
	}

	// ---- events from the game -------------------------------------------------

	/** Called on the native thread whenever the game shows or ends a line of text. */
	public void addLine(final String json) {
		Log.d("ScummLearn", "event " + (json.length() > 300 ? json.substring(0, 300) + "..." : json));
		_ui.post(() -> {
			try {
				JSONObject o = new JSONObject(json);
				String kind = o.optString("kind");
				if ("game".equals(kind)) {
					startGame(o);
					return;
				}
				if ("clear".equals(kind)) {
					if (_showKind == SHOW_LINE)
						hideIfNotHeld();
					return;
				}
				if ("object_end".equals(kind)) {
					_huntOnTarget = false; // the finger left the object
					_ui.removeCallbacks(_huntDwell);
				}
				if ("choice_end".equals(kind) || "object_end".equals(kind)) {
					if (_showKind == SHOW_HOVER)
						hideIfNotHeld();
					return;
				}
				if ("room".equals(kind)) {
					_room = o.optString("text");
					_roomObjects.clear();
					_roomNow.clear();
					_roomKnown = false; // until the game lists this room's objects
					endHunt(false);
					return;
				}
				if ("save".equals(kind)) {
					snapshotTo(o.optString("text"));
					return;
				}
				if ("load".equals(kind)) {
					restoreFrom(o.optString("text"));
					return;
				}
				if ("inventory".equals(kind)) {
					boolean added = false;
					_invNow.clear();
					for (String raw : o.optString("text").split("\n")) {
						String item = norm(raw).toLowerCase(Locale.ROOT);
						if (item.isEmpty() || item.equals("-"))
							continue;
						_invNow.add(item);
						if (_had.add(item))
							added = true;
					}
					if (added) {
						getPrefs().edit().putStringSet(key("had"), new HashSet<>(_had)).apply();
						Log.d("ScummLearn", "had " + _had);
					}
					return;
				}
				if ("objects".equals(kind)) {
					setRoomObjects(o.optString("text"));
					return;
				}
				if ("choices".equals(kind)) {
					_inConversation = true;
					_ui.removeCallbacks(_conversationOver);
					if (_huntTarget != null)
						endHunt(false);
					_ui.removeCallbacks(_huntNext);
					showChoices(o.optString("text"));
					return;
				}
				if ("choices_end".equals(kind)) {
					_ui.removeCallbacks(_conversationOver);
					_ui.postDelayed(_conversationOver, CONVERSATION_TAIL_MS);
					hideChoices();
					return;
				}
				String text = norm(o.optString("text"));
				boolean line = "dialog".equals(kind) || "video".equals(kind);
				if (line && _seen.add(text))
					getPrefs().edit().putStringSet(key("seen_lines"), new HashSet<>(_seen)).apply();
				if ("object".equals(kind) && _huntTarget != null) {
					// Sliding across the object doesn't count: the child taps it, lifts the finger
					// on it, or rests on it for a moment.
					if (!matchesHunt(text, o.optString("speaker"))) {
						_huntOnTarget = false;
						_ui.removeCallbacks(_huntDwell);
					} else if (KidTouch.sFingerDown) {
						if (!_huntOnTarget) {
							_huntOnTarget = true;
							_ui.removeCallbacks(_huntDwell);
							_ui.postDelayed(_huntDwell, HUNT_DWELL_MS);
						}
					} else if (android.os.SystemClock.uptimeMillis() - KidTouch.sLastUpMs < 500) {
						huntFound(); // a quick tap: the game reported the object after the finger lifted
						return;
					}
				}
				if (!_enabled || _holding)
					return; // while held, keep the current subtitle
				String he = translate(o.optString("id"), text);

				if (line) {
					if (he == null) {
						hide();
						return;
					}
					show(he, "video".equals(kind) ? VIDEO_SHOW_MS : MAX_SHOW_MS);
					_showKind = SHOW_LINE;
					_lineEn = text;
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
