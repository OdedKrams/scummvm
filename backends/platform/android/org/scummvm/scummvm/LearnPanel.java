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
	private LinearLayout _choicesPanel;
	private LinearLayout _choicesList;
	private String _choicesKey = "";
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
		if (_hoverEn != null && KidTouch.sFingerDown && _ttsReady) {
			_tts.speak(_hoverEn, TextToSpeech.QUEUE_FLUSH, null, "scummlearn-object");
			reward("O:" + _hoverEn.toLowerCase(Locale.ROOT), 1);
		}
	};
	private final Runnable _autoHide = this::hideIfNotHeld;

	// ---- gold coins: earned by learning English, spent on hints ----
	private static final int HINT_COST = 3;
	private int _coins = 0;
	private final Set<String> _rewarded = new HashSet<>();  // "L:line", "O:object", "C:choice"
	private TextView _coinView;
	private String _lineEn = null;                          // English of the line on screen

	// ---- "Find the ...!" game ----
	private static final long HUNT_FIRST_MS = 15000, HUNT_EVERY_MS = 45000, HUNT_HELP_MS = 20000,
		HUNT_GIVE_UP_MS = 120000;
	private final java.util.List<String> _roomObjects = new java.util.ArrayList<>();
	private final Set<String> _found = new HashSet<>();     // "room:name"
	private TextView _huntCard;
	private String _huntTarget = null;
	private boolean _huntHelped = false;
	private final Runnable _huntNext = () -> startHunt(false);
	private final Runnable _huntHelp = this::huntHelp;
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
		return s.replaceAll("^\\s*/[A-Za-z0-9_\\-]+\\.[0-9]+/", "").replaceAll("\\s+", " ").trim();
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
					if (_showKind == SHOW_LINE && _lineEn != null)
						reward("L:" + _lineEn, 1);
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
				reward("C:" + en, 1);
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

		int size = dp(56);
		for (TextView b : new TextView[]{_hintBtn, hunt, _toggle, inv, skip}) {
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
			getPrefs().edit().putStringSet(key("hints_shown"), new HashSet<>(_hintShown)).apply();
			return step.optString("he");
		}
		JSONObject lastStep = steps.optJSONObject(steps.length() - 1);
		return lastStep != null ? lastStep.optString("he") : null;
	}

	private void onHintPressed() {
		boolean free = System.currentTimeMillis() >= hintReadyAt();
		if (!free && _coins < HINT_COST) {
			long sec = (hintReadyAt() - System.currentTimeMillis() + 999) / 1000;
			flash("עוד " + (HINT_COST - _coins) + " 🪙 לרמז, או לחכות "
				+ String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60) + "\nמרוויחים מטבעות מאנגלית: 🔎 חיפוש, החזקה על כתובית, הקשבה לשם של חפץ");
			return;
		}
		String hint = pickHint();
		Log.d("ScummLearn", "hint room=" + _room + " -> " + hint);
		if (hint == null)
			hint = "אין עדיין רמז למקום הזה. נסה להסתכל (Look at) ולדבר (Talk to) עם כל מה שאפשר.";
		_hintCard.setText(hint + "\n\n(נגיעה כדי לסגור)");
		_hintCard.setVisibility(View.VISIBLE);
		_hintCard.bringToFront();
		_pause.setPaused(true);
		if (free) {
			long cooldown = (_hints != null ? _hints.optLong("cooldown_sec", 180) : 180) * 1000;
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
			+ " 🪙 (או חינם כל 3 דקות)\nמרוויחים: 🔎 מציאת חפץ = 3, החזקה על כתובית, הקשבה לשם של חפץ או לתשובה = 1"));
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
		_hintCard.setText(msg);
		_hintCard.setVisibility(View.VISIBLE);
		_hintCard.bringToFront();
		_ui.postDelayed(() -> {
			if (_hintCard.getText().toString().equals(msg))
				_hintCard.setVisibility(View.GONE);
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
		_huntCard.setOnClickListener(v -> sayHunt());
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
		lp.setMargins(dp(100), dp(10), dp(100), 0);
		_root.addView(_huntCard, lp);
	}

	private void setRoomObjects(String all) {
		_roomObjects.clear();
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

	/** The finger is on (or just tapped) the object we're looking for, or one it overlaps. */
	private boolean isHuntHit(String name, String alsoUnder) {
		boolean touching = KidTouch.sFingerDown
			|| android.os.SystemClock.uptimeMillis() - KidTouch.sLastUpMs < 1500;
		if (!touching)
			return false;
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
		if (!asked && (_choicesPanel.getVisibility() == View.VISIBLE || _hintCard.getVisibility() == View.VISIBLE
				|| _showKind == SHOW_LINE)) {
			_ui.postDelayed(_huntNext, 10000); // busy now, try again soon
			return false;
		}
		java.util.List<String> left = new java.util.ArrayList<>();
		for (String en : _roomObjects)
			if (!_found.contains(_room + ":" + en.toLowerCase(Locale.ROOT)))
				left.add(en);
		if (left.isEmpty())
			return false;
		_huntTarget = left.get(_rnd.nextInt(left.size()));
		_huntHelped = false;
		_huntCard.setText("🔎 " + huntPhrase(_huntTarget));
		_huntCard.setVisibility(View.VISIBLE);
		_huntCard.bringToFront();
		sayHunt();
		_ui.postDelayed(_huntHelp, HUNT_HELP_MS);
		_ui.postDelayed(_huntGiveUp, HUNT_GIVE_UP_MS);
		Log.d("ScummLearn", "hunt start " + _huntTarget);
		return true;
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
		_huntCard.setText("🔎 " + huntPhrase(_huntTarget) + "\n" + he);
		sayHunt();
	}

	private void huntFound() {
		String en = _huntTarget;
		int n = _huntHelped ? 2 : 3;
		_found.add(_room + ":" + en.toLowerCase(Locale.ROOT));
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
				if ("choice_end".equals(kind) || "object_end".equals(kind)) {
					if (_showKind == SHOW_HOVER)
						hideIfNotHeld();
					return;
				}
				if ("room".equals(kind)) {
					_room = o.optString("text");
					_roomObjects.clear();
					endHunt(false);
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
				if ("object".equals(kind) && _huntTarget != null && isHuntHit(text, o.optString("speaker"))) {
					huntFound();
					return;
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
