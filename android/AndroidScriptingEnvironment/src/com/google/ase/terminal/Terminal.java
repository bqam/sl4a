/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.ase.terminal;

import java.io.File;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.widget.Toast;

import com.google.ase.AndroidFacade;
import com.google.ase.AseAnalytics;
import com.google.ase.AseLog;
import com.google.ase.AsePreferences;
import com.google.ase.Constants;
import com.google.ase.R;
import com.google.ase.ScriptStorageAdapter;
import com.google.ase.interpreter.Interpreter;
import com.google.ase.interpreter.InterpreterProcess;
import com.google.ase.interpreter.InterpreterUtils;

/**
 * A terminal emulator activity.
 */
public class Terminal extends Activity {

  /**
   * Set to true to add debugging code and logging.
   */
  public static final boolean DEBUG = false;

  /**
   * Set to true to log each character received from the remote process to the android log, which
   * makes it easier to debug some kinds of problems with emulating escape sequences and control
   * codes.
   */
  public static final boolean LOG_CHARACTERS_FLAG = DEBUG;

  /**
   * Set to true to log unknown escape sequences.
   */
  public static final boolean LOG_UNKNOWN_ESCAPE_SEQUENCES = DEBUG;

  /**
   * The tag we use when logging, so that our messages can be distinguished from other messages in
   * the log. Public because it's used by several classes.
   */
  public static final String TAG = "Terminal";

  /**
   * Our main view. Displays the emulated terminal screen.
   */
  private EmulatorView mEmulatorView;

  /**
   * A key listener that tracks the modifier keys and allows the full ASCII character set to be
   * entered.
   */
  private TermKeyListener mKeyListener;

  /**
   * The name of our emulator view in the view resource.
   */
  private static final int EMULATOR_VIEW = R.id.emulatorView;

  private static final int DEFAULT_FONT_SIZE = 10;
  private static final String FONTSIZE_KEY = "fontsize";

  public static final int WHITE = 0xffffffff;
  public static final int BLACK = 0xff000000;
  public static final int BLUE = 0xff344ebd;

  private static final int DEFAULT_COLOR_SCHEME = 1;
  private static final String COLOR_KEY = "color";

  private static final int[][] COLOR_SCHEMES =
      { { BLACK, WHITE }, { WHITE, BLACK }, { WHITE, BLUE } };

  private static final String CONTROLKEY_KEY = "controlkey";
  private int mControlKeyId = 0;

  private static final int[] CONTROL_KEY_SCHEMES =
      { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_ALT_LEFT,
          KeyEvent.KEYCODE_ALT_RIGHT };

  private static final String[] CONTROL_KEY_NAME = { "Ball", "@", "Left-Alt", "Right-Alt" };

  private int mControlKeyCode;

  private SharedPreferences mPrefs;

  private String mScriptPath;
  private InterpreterProcess mInterpreterProcess;
  private AndroidFacade mAndroidFacade;
  private String mInterpreterName;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // TODO(damonkohler): Until we are able to save and return state, it's better to just die.
    if (savedInstanceState != null) {
      AseLog.e("Attempted to restore previous state. Aborting.");
      finish();
      return;
    }

    requestWindowFeature(Window.FEATURE_NO_TITLE);
    setContentView(R.layout.term);

    String scriptName = getIntent().getStringExtra(Constants.EXTRA_SCRIPT_NAME);
    if (scriptName != null) {
      File script = ScriptStorageAdapter.getScript(scriptName);
      if (script != null) {
        mScriptPath = script.getAbsolutePath();
        Interpreter interpreter = InterpreterUtils.getInterpreterForScript(scriptName);
        if (interpreter != null) {
          mInterpreterName = interpreter.getName();
        }
      } else {
        Toast.makeText(this, "Script not found.", Toast.LENGTH_SHORT);
        finish();
        return;
      }
    } else {
      mInterpreterName = getIntent().getStringExtra(Constants.EXTRA_INTERPRETER_NAME);
    }

    if (mInterpreterName == null) {
      Toast.makeText(this, "No interpreter specified.", Toast.LENGTH_SHORT).show();
      finish();
      return;
    }

    mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
    mEmulatorView = (EmulatorView) findViewById(EMULATOR_VIEW);
    mKeyListener = new TermKeyListener();
    updatePreferences();
    startInterpreter();

    AseAnalytics.trackActivity(this, mInterpreterName);
  }

  private void startInterpreter() {
    AseLog.v("Starting interpreter.");
    mAndroidFacade = new AndroidFacade(this, new Handler(), getIntent());
    Interpreter interpreter = InterpreterUtils.getInterpreterByName(mInterpreterName);
    if (interpreter != null) {
      mInterpreterProcess = interpreter.buildProcess(mAndroidFacade, mScriptPath);
    } else {
      Toast.makeText(this, "InterpreterInterface not found.", Toast.LENGTH_SHORT).show();
      finish();
      return;
    }
    mInterpreterProcess.start();
    mEmulatorView.attachInterpreterProcess(mInterpreterProcess);
  }

  private void restart() {
    startActivity(getIntent());
    finish();
  }

  private void updatePreferences() {
    mEmulatorView.setTextSize(readIntPref(FONTSIZE_KEY, DEFAULT_FONT_SIZE, 30));
    int[] scheme =
        COLOR_SCHEMES[readIntPref(COLOR_KEY, DEFAULT_COLOR_SCHEME, COLOR_SCHEMES.length - 1)];
    mEmulatorView.setColors(scheme[0], scheme[1]);
    mControlKeyId = readIntPref(CONTROLKEY_KEY, mControlKeyId, CONTROL_KEY_SCHEMES.length - 1);
    mControlKeyCode = CONTROL_KEY_SCHEMES[mControlKeyId];
  }

  private int readIntPref(String key, int defaultValue, int maxValue) {
    int val;
    try {
      val = Integer.parseInt(mPrefs.getString(key, Integer.toString(defaultValue)));
    } catch (NumberFormatException e) {
      val = defaultValue;
    }
    val = Math.max(0, Math.min(val, maxValue));
    return val;
  }

  @Override
  public void onResume() {
    super.onResume();
    // Typically, onResume is called after we update our preferences.
    updatePreferences();
    mEmulatorView.update();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    mEmulatorView.update();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (handleControlKey(keyCode, true)) {
      return true;
    } else if (event.isSystem()) {
      // Don't intercept the system keys.
      return super.onKeyDown(keyCode, event);
    } else if (handleDPad(keyCode, true)) {
      return true;
    }

    // Translate the keyCode into an ASCII character.
    int letter = mKeyListener.keyDown(keyCode, event);
    if (letter > -1) {
      mInterpreterProcess.print((char) letter);
    }
    return true;
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (handleControlKey(keyCode, false)) {
      return true;
    } else if (event.isSystem()) {
      // Don't intercept the system keys.
      return super.onKeyUp(keyCode, event);
    } else if (handleDPad(keyCode, false)) {
      return true;
    }
    mKeyListener.keyUp(keyCode);
    return true;
  }

  private boolean handleControlKey(int keyCode, boolean down) {
    if (keyCode == mControlKeyCode) {
      mKeyListener.handleControlKey(down);
      return true;
    }
    return false;
  }

  /**
   * Handle dpad left-right-up-down events. Don't handle dpad-center, that's our control key.
   *
   * @param keyCode
   * @param down
   */
  private boolean handleDPad(int keyCode, boolean down) {
    if (keyCode < KeyEvent.KEYCODE_DPAD_UP || keyCode > KeyEvent.KEYCODE_DPAD_CENTER) {
      // keyCode does not correspond to the dpad.
      return false;
    }

    if (down) {
      if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
        // TODO(damonkohler): If center is our control key, why are we printing \r?
        mInterpreterProcess.print('\r');
      } else {
        char code;
        switch (keyCode) {
          case KeyEvent.KEYCODE_DPAD_UP:
            code = 'A';
            break;
          case KeyEvent.KEYCODE_DPAD_DOWN:
            code = 'B';
            break;
          case KeyEvent.KEYCODE_DPAD_LEFT:
            code = 'D';
            break;
          default:
          case KeyEvent.KEYCODE_DPAD_RIGHT:
            code = 'C';
            break;
        }
        mInterpreterProcess.print((char) 27); // ESC
        if (mEmulatorView.getKeypadApplicationMode()) {
          mInterpreterProcess.print('O');
        } else {
          mInterpreterProcess.print('[');
        }
        mInterpreterProcess.print(code);
      }
    }
    return true;
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.terminal, menu);
    if (mScriptPath == null) {
      menu.removeItem(R.id.terminal_menu_exit_and_edit);
    }
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.terminal_menu_preferences:
        doPreferences();
        break;
      case R.id.terminal_menu_reset:
        doResetTerminal();
        break;
      case R.id.terminal_menu_send_email:
        doEmailTranscript();
        break;
      case R.id.terminal_menu_special_keys:
        doDocumentKeys();
        break;
      case R.id.terminal_menu_exit_and_edit:
        Intent i = new Intent(Constants.ACTION_EDIT_SCRIPT);
        // TODO(damonkohler): This is a hacky way of getting the script name.
        i.putExtra(Constants.EXTRA_SCRIPT_NAME, new File(mScriptPath).getName());
        startActivity(i);
        finish();
        break;
    }
    return super.onOptionsItemSelected(item);
  }

  private void doPreferences() {
    startActivity(new Intent(this, AsePreferences.class));
  }

  private void doResetTerminal() {
    restart();
  }

  private void doEmailTranscript() {
    // Don't really want to supply an address, but currently it's required, otherwise we get an
    // exception.
    String addr = "user@example.com";
    Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + addr));
    intent.putExtra("body", mEmulatorView.getTranscriptText());
    startActivity(intent);
  }

  private void doDocumentKeys() {
    String controlKey = CONTROL_KEY_NAME[mControlKeyId];
    new AlertDialog.Builder(this).setTitle("Press " + controlKey + " and Key").setMessage(
        controlKey + " Space ==> Control-@ (NUL)\n" + controlKey + " A..Z ==> Control-A..Z\n"
            + controlKey + " 1 ==> Control-[ (ESC)\n" + controlKey + " 5 ==> Control-_\n"
            + controlKey + " . ==> Control-\\\n" + controlKey + " 0 ==> Control-]\n" + controlKey
            + " 6 ==> Control-^").show();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    mAndroidFacade.onActivityResult(requestCode, resultCode, data);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (mInterpreterProcess != null) {
      mInterpreterProcess.kill();
    }
    if (mAndroidFacade != null) {
      mAndroidFacade.onDestroy();
    }
    Toast.makeText(this, "Terminal killed.", Toast.LENGTH_SHORT).show();
  }
}
