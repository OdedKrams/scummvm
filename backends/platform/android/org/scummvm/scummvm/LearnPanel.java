package org.scummvm.scummvm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.speech.tts.TextToSpeech;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * ScummLearn help panel.
 *
 * A round "?" button floats over the game. Tapping it pauses the game and shows a
 * WebView with the last lines the game displayed (sent from C++ via JNI::learnLine).
 * The panel page itself (res/raw/learn_panel.html) does the rendering, word taps and
 * translation calls, so the UI can change without touching this class.
 */
public class LearnPanel {
	public interface PauseCallback {
		void setPaused(boolean paused);
	}

	private static final int MAX_LINES = 50;
	private static final String PREFS = "scummlearn";

	private final Activity _activity;
	private final FrameLayout _root;
	private final PauseCallback _pause;
	private final ArrayDeque<String> _lines = new ArrayDeque<>();

	private TextView _button;
	private WebView _web;
	private boolean _open = false;
	private TextToSpeech _tts;
	private boolean _ttsReady = false;

	public LearnPanel(Activity activity, FrameLayout root, PauseCallback pause) {
		_activity = activity;
		_root = root;
		_pause = pause;
		createButton();
		_tts = new TextToSpeech(activity.getApplicationContext(), status -> {
			if (status == TextToSpeech.SUCCESS) {
				_tts.setLanguage(Locale.US);
				_tts.setSpeechRate(0.85f);
				_ttsReady = true;
			}
		});
	}

	private int dp(float v) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
			_activity.getResources().getDisplayMetrics());
	}

	private void createButton() {
		_button = new TextView(_activity);
		_button.setText("?");
		_button.setTextColor(Color.WHITE);
		_button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
		_button.setGravity(Gravity.CENTER);
		GradientDrawable bg = new GradientDrawable();
		bg.setShape(GradientDrawable.OVAL);
		bg.setColor(0xCC2E6BE6);
		bg.setStroke(dp(2), Color.WHITE);
		_button.setBackground(bg);
		_button.setOnClickListener(v -> toggle());

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.START);
		lp.setMargins(dp(16), dp(16), 0, 0); // top-left: ScummVM's own buttons are top-right
		_root.addView(_button, lp);
		_button.bringToFront();
	}

	@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
	private void createWebView() {
		_web = new WebView(_activity);
		_web.setBackgroundColor(Color.TRANSPARENT);
		WebSettings s = _web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		_web.setWebViewClient(new WebViewClient());
		_web.addJavascriptInterface(new Bridge(), "Android");

		String devUrl = getPrefs().getString("panel_url", "");
		if (!devUrl.isEmpty()) {
			// Development: load the panel live from a server, so UI changes need no rebuild.
			_web.loadUrl(devUrl);
		} else {
			_web.loadDataWithBaseURL("https://scummlearn.local/", readPanelHtml(), "text/html", "utf-8", null);
		}

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
			FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
		_web.setVisibility(View.GONE);
		_root.addView(_web, lp);
	}

	private SharedPreferences getPrefs() {
		return _activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
	}

	private String readPanelHtml() {
		return readRaw(R.raw.learn_panel);
	}

	private String readRaw(int id) {
		try (InputStream in = _activity.getResources().openRawResource(id)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
			return out.toString("UTF-8");
		} catch (Exception e) {
			return id == R.raw.learn_panel ? "<html><body style='color:white'>Panel missing: " + e + "</body></html>" : "{}";
		}
	}

	/** Called on the native thread whenever the game shows a line of text. */
	public void addLine(final String json) {
		_activity.runOnUiThread(() -> {
			synchronized (_lines) {
				_lines.addLast(json);
				while (_lines.size() > MAX_LINES)
					_lines.removeFirst();
			}
			if (_open && _web != null)
				_web.evaluateJavascript("window.onLine && window.onLine(" + json + ")", null);
		});
	}

	public boolean isOpen() {
		return _open;
	}

	public void toggle() {
		if (_open)
			close();
		else
			open();
	}

	public void open() {
		if (_web == null)
			createWebView();
		_open = true;
		_pause.setPaused(true);
		_web.setVisibility(View.VISIBLE);
		_web.bringToFront();
		_button.bringToFront();
		_button.setText("✕"); // ✕
		_web.evaluateJavascript("window.onOpen && window.onOpen()", null);
	}

	public void close() {
		_open = false;
		if (_web != null)
			_web.setVisibility(View.GONE);
		_button.setText("?");
		if (_tts != null)
			_tts.stop();
		_pause.setPaused(false);
	}

	public void destroy() {
		if (_tts != null) {
			_tts.shutdown();
			_tts = null;
		}
	}

	/** Methods the panel page can call as window.Android.* */
	private class Bridge {
		@JavascriptInterface
		public String getLines() {
			JSONArray arr = new JSONArray();
			synchronized (_lines) {
				for (String l : _lines)
					arr.put(l);
			}
			return arr.toString();
		}

		/** Offline translations bundled in the APK (res/raw/learn_dict.json). */
		@JavascriptInterface
		public String getDict() {
			return readRaw(R.raw.learn_dict);
		}

		@JavascriptInterface
		public void speak(String text) {
			if (_tts != null && _ttsReady)
				_tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "scummlearn");
		}

		@JavascriptInterface
		public void close() {
			_activity.runOnUiThread(LearnPanel.this::close);
		}

		@JavascriptInterface
		public String getSetting(String key) {
			return getPrefs().getString(key, "");
		}

		@JavascriptInterface
		public void setSetting(String key, String value) {
			getPrefs().edit().putString(key, value).apply();
		}
	}
}
