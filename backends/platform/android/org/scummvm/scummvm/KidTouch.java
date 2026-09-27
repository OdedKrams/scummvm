package org.scummvm.scummvm;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * ScummLearn: simple touch controls for kids (direct touch, no hidden gestures).
 *
 *  - Tap                 = left click where the finger touched (walk / default action)
 *  - Slide a finger      = move the cursor (object names show up as you slide)
 *  - Touch and hold      = hold the left button (opens the Curse of Monkey Island verb coin);
 *                          slide to the verb and lift the finger to choose it
 *  - Two-finger tap      = right click (inventory)
 *
 * Coordinates are the surface view's own, the same ones MouseHelper sends for a real mouse.
 */
public class KidTouch {
	private static final long HOLD_MS = 350;
	private static final long CLICK_GAP_MS = 40;    // move -> down: SCUMM games want a small gap
	private static final long CLICK_LEN_MS = 90;    // down -> up

	/** Finger state, read by the learning overlay (object names show only while touching). */
	public static volatile boolean sFingerDown = false;
	public static Runnable sOnFingerUp = null;
	/** Off while a dialogue menu is shown: a resting finger must not pick an option. */
	public static volatile boolean sHoldEnabled = true;

	private final ScummVM _scummvm;
	private final Handler _h = new Handler(Looper.getMainLooper());

	private float _x, _y, _anchorX, _anchorY;
	private boolean _holding = false;
	private boolean _twoFinger = false;
	private View _view;
	private final float _slop;

	private final Runnable _startHold = () -> {
		if (!sHoldEnabled)
			return;
		_holding = true;
		Log.d("ScummLearn", "touch hold at " + (int) _x + "," + (int) _y);
		send(ScummVMEvents.JE_LMB_DOWN, _x, _y);
		if (_view != null)
			_view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
	};

	public KidTouch(ScummVM scummvm, float density) {
		_scummvm = scummvm;
		_slop = 14 * density;
	}

	private void send(int type, float x, float y) {
		_scummvm.pushEvent(type, (int) x, (int) y, 0, 0, 0, 0);
	}

	private void click(int down, int up, float x, float y) {
		final int ix = (int) x, iy = (int) y;
		send(ScummVMEvents.JE_MOUSE_MOVE, ix, iy);
		_h.postDelayed(() -> _scummvm.pushEvent(down, ix, iy, 0, 0, 0, 0), CLICK_GAP_MS);
		_h.postDelayed(() -> _scummvm.pushEvent(up, ix, iy, 0, 0, 0, 0), CLICK_GAP_MS + CLICK_LEN_MS);
	}

	/** Right click at the last touched position (used by the inventory button too). */
	public void rightClick() {
		click(ScummVMEvents.JE_RMB_DOWN, ScummVMEvents.JE_RMB_UP, _x, _y);
	}

	private static void fingerUp() {
		sFingerDown = false;
		Runnable r = sOnFingerUp;
		if (r != null)
			r.run();
	}

	public boolean onTouch(View v, MotionEvent e) {
		_view = v;
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				sFingerDown = true;
				_x = _anchorX = e.getX();
				_y = _anchorY = e.getY();
				_holding = false;
				_twoFinger = false;
				send(ScummVMEvents.JE_MOUSE_MOVE, _x, _y);
				_h.postDelayed(_startHold, HOLD_MS);
				return true;

			case MotionEvent.ACTION_POINTER_DOWN:
				if (!_holding) {
					_twoFinger = true;
					_h.removeCallbacks(_startHold);
				}
				return true;

			case MotionEvent.ACTION_MOVE:
				if (_twoFinger)
					return true;
				_x = e.getX();
				_y = e.getY();
				send(ScummVMEvents.JE_MOUSE_MOVE, _x, _y);
				if (!_holding && Math.hypot(_x - _anchorX, _y - _anchorY) > _slop) {
					// Still sliding: restart the hold timer from here, so resting on an
					// object after sliding to it opens the verb coin.
					_anchorX = _x;
					_anchorY = _y;
					_h.removeCallbacks(_startHold);
					_h.postDelayed(_startHold, HOLD_MS);
				}
				return true;

			case MotionEvent.ACTION_UP:
				_h.removeCallbacks(_startHold);
				if (_twoFinger) {
					Log.d("ScummLearn", "touch two-finger tap");
					rightClick();
				} else if (_holding) {
					send(ScummVMEvents.JE_MOUSE_MOVE, e.getX(), e.getY());
					send(ScummVMEvents.JE_LMB_UP, e.getX(), e.getY());
				} else if (Math.hypot(e.getX() - _anchorX, e.getY() - _anchorY) <= _slop) {
					Log.d("ScummLearn", "touch tap at " + (int) e.getX() + "," + (int) e.getY());
					click(ScummVMEvents.JE_LMB_DOWN, ScummVMEvents.JE_LMB_UP, e.getX(), e.getY());
				}
				_holding = false;
				_twoFinger = false;
				fingerUp();
				return true;

			case MotionEvent.ACTION_CANCEL:
				_h.removeCallbacks(_startHold);
				if (_holding)
					send(ScummVMEvents.JE_LMB_UP, _x, _y);
				_holding = false;
				_twoFinger = false;
				fingerUp();
				return true;
		}
		return true;
	}
}
