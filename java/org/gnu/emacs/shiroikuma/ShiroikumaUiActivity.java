/* The 白い熊 GNU Emacs UI page.  -*- java -*-

This file is part of the shiroikuma-emacs fork of GNU Emacs
(https://github.com/ShiroiKuma0/shiroikuma-emacs).

GNU Emacs is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or (at
your option) any later version.

GNU Emacs is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GNU Emacs.  If not, see <https://www.gnu.org/licenses/>.  */

package org.gnu.emacs.shiroikuma;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.gnu.emacs.EmacsActivity;
import org.gnu.emacs.EmacsApplication;
import org.gnu.emacs.EmacsNative;
import org.gnu.emacs.R;

import java.io.File;
import java.util.Set;

/**
 * 「白い熊 GNU Emacs UI」 — the one settings page of the fork, in the house
 * look (black ground, yellow ink, text-wide underlined section titles,
 * the 36 → 72 dp indent ladder).  Emacs draws its whole UI itself, so this
 * page is the app's Settings: Export / Import (+ the sister-app automation
 * rows) · Emacs options (the three stock rows, re-implemented) · Appearance
 * (the two things the Java side can still decide) · Reset.
 *
 * <p>Entry: {@code EmacsLauncherPreferencesActivity} (the 白い熊 GNU Emacs UI
 * launcher icon) extends this class; the manifest's
 * {@code APPLICATION_PREFERENCES} activity is this class; a static launcher
 * shortcut ({@code res/xml/shortcuts.xml}) opens it from a long-press on
 * the Emacs icon.
 *
 * <p>Manual import while Emacs runs — the pending-import relaunch: the
 * panel spools the archive to the cache, {@link #relaunchForImport} writes
 * {@code pending_import} to {@code shiroikuma_eximport} ({@code commit()}),
 * starts this activity with {@code NEW_TASK|CLEAR_TASK} and calls
 * {@code System.exit(0)} — upstream's own restart trick.  The process
 * relaunches with no {@code EmacsService} (it is only started from
 * {@code EmacsActivity.onCreate}; {@code START_NOT_STICKY}), {@link #onCreate}
 * sees the marker and resumes the restore in a "Restoring…" panel.
 *
 * <p>Framework Java 7 ({@code --release 7}): anonymous classes, no AndroidX.
 */
public class ShiroikumaUiActivity extends Activity
{
  private static final int REQ_EXPORT_DIR = 41;
  private static final int REQ_IMPORT_FILE = 42;

  // The house indent ladder: section title 36 dp → rows under a section 72 dp.
  private static final int INDENT_SECTION_DP = 36;
  private static final int INDENT_ROW_DP = 72;

  private LinearLayout mRoot;
  private LinearLayout mHolder;
  private ExportImportPanel mPanel;

  /** The "Last export" value, filled in from a background query; the generation drops stale answers.  */
  private TextView mLastExportValue;
  private int mLastExportGeneration;

  private interface OnToggle
  {
    void onToggle (boolean checked);
  }

  @Override
  protected void
  onCreate (Bundle savedInstanceState)
  {
    super.onCreate (savedInstanceState);

    mRoot = new LinearLayout (this);
    mRoot.setOrientation (LinearLayout.VERTICAL);
    mRoot.setBackgroundColor (ShiroikumaLook.BG);
    mRoot.setFitsSystemWindows (true);

    mRoot.addView (headerRow ());

    ScrollView scroll = new ScrollView (this);
    scroll.setLayoutParams (new LinearLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    mHolder = new LinearLayout (this);
    mHolder.setOrientation (LinearLayout.VERTICAL);
    mHolder.setPadding (0, dp (4), 0, dp (32));
    scroll.addView (mHolder);
    mRoot.addView (scroll);

    setContentView (mRoot);
    ShiroikumaPrefs.applyWindowPrefs (this);

    if (savedInstanceState == null)
      resumePendingImportIfAny ();
  }

  @Override
  protected void
  onResume ()
  {
    super.onResume ();
    buildRows ();
  }

  @Override
  protected void
  onActivityResult (int requestCode, int resultCode, Intent data)
  {
    super.onActivityResult (requestCode, resultCode, data);
    Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData () : null;
    if (requestCode == REQ_EXPORT_DIR)
      {
        if (uri == null)
          return;
        SafDir.storeDir (this, uri);
        if (mPanel != null && mPanel.isShowing ())
          mPanel.onDirPicked ();
        buildRows ();
      }
    else if (requestCode == REQ_IMPORT_FILE)
      {
        if (uri != null && mPanel != null && mPanel.isShowing ())
          mPanel.onImportFilePicked (uri);
      }
  }

  private int
  dp (float v)
  {
    return ShiroikumaLook.dp (this, v);
  }

  // --- header -----------------------------------------------------------------------------------

  /** A header row instead of a Toolbar: back arrow + the page title.  */
  private View
  headerRow ()
  {
    LinearLayout row = new LinearLayout (this);
    row.setOrientation (LinearLayout.HORIZONTAL);
    row.setGravity (Gravity.CENTER_VERTICAL);
    row.setPadding (dp (8), dp (10), dp (16), dp (6));

    TextView back = ShiroikumaLook.text (this, "←", 24, ShiroikumaLook.INK, false);
    back.setPadding (dp (10), dp (4), dp (14), dp (4));
    back.setOnClickListener (new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          finish ();
        }
      });
    row.addView (back);

    TextView title = ShiroikumaLook.text (this, getString (R.string.shiroikuma_ui_title), 20,
                                          ShiroikumaLook.INK, true);
    row.addView (title);
    return row;
  }

  // --- row building -----------------------------------------------------------------------------

  private void
  buildRows ()
  {
    mHolder.removeAllViews ();
    mLastExportValue = null;

    addExportImportSection ();
    addOptionsSection ();
    addAppearanceSection ();
    addResetSection ();
  }

  /**
   * A section heading in the house style: 20 sp bold yellow with an
   * underline exactly as wide as the TEXT, and a 1 px full-width hairline
   * above every section but the first.
   */
  private void
  addSection (String labelText)
  {
    if (mHolder.getChildCount () > 0)
      {
        View line = new View (this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp (18);
        line.setLayoutParams (lp);
        line.setBackgroundColor (ShiroikumaLook.INK);
        line.setAlpha (0.35f);
        mHolder.addView (line);
      }

    LinearLayout box = new LinearLayout (this);
    box.setOrientation (LinearLayout.VERTICAL);
    box.setPadding (dp (INDENT_SECTION_DP), dp (16), dp (8), dp (6));

    TextView label = ShiroikumaLook.text (this, labelText, 20, ShiroikumaLook.INK, true);
    label.setMaxLines (1);
    box.addView (label);
    box.addView (underline (label, Math.max (1, dp (2.5f))));
    mHolder.addView (box);
  }

  /** An underline measured to the label's text width, 2 dp below it.  */
  private View
  underline (TextView label, int height)
  {
    label.measure (View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    View rule = new View (this);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams (label.getMeasuredWidth (), height);
    lp.topMargin = dp (2);
    rule.setLayoutParams (lp);
    rule.setBackgroundColor (ShiroikumaLook.INK);
    return rule;
  }

  private LinearLayout
  makeRowContainer ()
  {
    LinearLayout row = new LinearLayout (this);
    row.setOrientation (LinearLayout.HORIZONTAL);
    row.setGravity (Gravity.CENTER_VERTICAL);
    row.setMinimumHeight (0);
    row.setPadding (dp (INDENT_ROW_DP), dp (5), dp (14), dp (5));
    return row;
  }

  /** A 16 sp title over a 13 sp value/description line; the whole row is the tap target.  */
  private LinearLayout
  addTwoLineRow (String title, CharSequence value, int valueColor, View.OnClickListener onClick)
  {
    LinearLayout row = new LinearLayout (this);
    row.setOrientation (LinearLayout.VERTICAL);
    row.setMinimumHeight (0);
    row.setPadding (dp (INDENT_ROW_DP), dp (5), dp (14), dp (5));
    row.addView (ShiroikumaLook.text (this, title, 16, ShiroikumaLook.INK, false));
    if (value != null && value.length () > 0)
      {
        TextView valueView = ShiroikumaLook.text (this, value, 13, valueColor, false);
        if (valueColor == ShiroikumaLook.DIM)
          valueView.setAlpha (0.78f);
        valueView.setPadding (0, dp (2), 0, 0);
        row.addView (valueView);
      }
    if (onClick != null)
      {
        row.setClickable (true);
        row.setOnClickListener (onClick);
      }
    mHolder.addView (row);
    return row;
  }

  /** A title + description on the left, a yellow switch on the right.  */
  private void
  addSwitchRow (String title, String description, boolean checked, final OnToggle onToggle)
  {
    LinearLayout row = makeRowContainer ();

    LinearLayout labels = new LinearLayout (this);
    labels.setOrientation (LinearLayout.VERTICAL);
    labels.setLayoutParams (new LinearLayout.LayoutParams (0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    labels.addView (ShiroikumaLook.text (this, title, 16, ShiroikumaLook.INK, false));
    if (description != null)
      {
        TextView desc = ShiroikumaLook.text (this, description, 13, ShiroikumaLook.DIM, false);
        desc.setAlpha (0.78f);
        desc.setPadding (0, dp (2), dp (8), 0);
        labels.addView (desc);
      }
    row.addView (labels);

    final Switch toggle = new Switch (this);
    toggle.setChecked (checked);
    toggle.setThumbTintList (ColorStateList.valueOf (ShiroikumaLook.INK));
    toggle.setTrackTintList (ColorStateList.valueOf (ShiroikumaLook.DIM));
    toggle.setOnCheckedChangeListener (new CompoundButton.OnCheckedChangeListener () {
        @Override
        public void
        onCheckedChanged (CompoundButton button, boolean isChecked)
        {
          onToggle.onToggle (isChecked);
        }
      });
    row.addView (toggle);

    row.setOnClickListener (new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          toggle.toggle ();
        }
      });
    mHolder.addView (row);
  }

  /** A dim 13 sp caption under a section's rows.  */
  private void
  addCaption (String text)
  {
    TextView tv = ShiroikumaLook.text (this, text, 13, ShiroikumaLook.DIM, false);
    tv.setAlpha (0.78f);
    tv.setPadding (dp (INDENT_ROW_DP), dp (4), dp (14), dp (8));
    mHolder.addView (tv);
  }

  // --- Export / Import (first section) ----------------------------------------------------------

  private void
  addExportImportSection ()
  {
    addSection (getString (R.string.shiroikuma_section_eim));
    addTwoLineRow (getString (R.string.shiroikuma_eim_entry), getString (R.string.shiroikuma_eim_entry_desc),
                   ShiroikumaLook.DIM, new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          openExportImport ();
        }
      });
    addDirRow ();
    addLastExportRow ();
    addAutomationRows ();
  }

  /** The export directory: the path when set, red "not set" otherwise; tap = the SAF tree picker.  */
  private void
  addDirRow ()
  {
    String label = SafDir.dirLabel (this);
    String remembered = label == null ? SafDir.rememberedDirLabel (this) : null;
    CharSequence value = label != null ? label
      : remembered != null ? remembered + "\n" + getString (R.string.shiroikuma_eim_dir_regrant)
      : getString (R.string.shiroikuma_eim_dir_unset);
    addTwoLineRow (getString (R.string.shiroikuma_eim_dir), value,
                   label != null ? ShiroikumaLook.DIM : ShiroikumaLook.WARN, new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          pickExportDir ();
        }
      });
  }

  /** "Last export": the newest backup's date, time and size, queried on a background thread.  */
  private void
  addLastExportRow ()
  {
    final int generation = ++mLastExportGeneration;
    if (SafDir.usableDir (this) == null)
      {
        boolean remembered = SafDir.rememberedDirLabel (this) != null;
        addTwoLineRow (getString (R.string.shiroikuma_eim_last_title),
                       getString (remembered ? R.string.shiroikuma_eim_warn_regrant
                                  : R.string.shiroikuma_eim_warn_nodir),
                       ShiroikumaLook.WARN, null);
        return;
      }
    LinearLayout row = addTwoLineRow (getString (R.string.shiroikuma_eim_last_title),
                                      getString (R.string.shiroikuma_eim_querying), ShiroikumaLook.DIM, null);
    final TextView value = (TextView) row.getChildAt (1);
    mLastExportValue = value;
    new Thread (new Runnable () {
        @Override
        public void
        run ()
        {
          final SafDir.Entry newest = SafDir.newestExport (ShiroikumaUiActivity.this);
          runOnUiThread (new Runnable () {
              @Override
              public void
              run ()
              {
                if (generation != mLastExportGeneration || mLastExportValue != value)
                  return;
                if (newest == null)
                  {
                    value.setText (getString (R.string.shiroikuma_eim_warn_none));
                    value.setTextColor (ShiroikumaLook.WARN);
                    value.setAlpha (1f);
                  }
                else
                  {
                    value.setText (getString (R.string.shiroikuma_eim_last_size,
                                              ExportImportPanel.formatTs (ShiroikumaUiActivity.this,
                                                                          newest.lastModified),
                                              EmacsBackup.humanSize (newest.size)));
                    value.setTextColor (ShiroikumaLook.INK);
                    value.setAlpha (1f);
                  }
              }
            });
        }
      }, "last-export").start ();
  }

  private void
  openExportImport ()
  {
    mPanel = new ExportImportPanel (this, new ExportImportPanel.Host () {
        @Override
        public void
        pickExportDir ()
        {
          ShiroikumaUiActivity.this.pickExportDir ();
        }

        @Override
        public void
        pickImportFile ()
        {
          startActivityForResult (SafDir.filePicker (), REQ_IMPORT_FILE);
        }

        @Override
        public void
        relaunchForImport (Set<EmacsBackup.Cat> cats)
        {
          ShiroikumaUiActivity.this.relaunchForImport (cats);
        }

        @Override
        public void
        startEmacs ()
        {
          ShiroikumaUiActivity.this.startEmacs ();
        }

        @Override
        public void
        onChainFinished ()
        {
          // A finished export/import closes the whole chain: info dialog → panel → this page.
          finish ();
        }
      });
    mPanel.show ();
  }

  private void
  pickExportDir ()
  {
    try
      {
        startActivityForResult (SafDir.treePicker (this), REQ_EXPORT_DIR);
      }
    catch (Exception e)
      {
        ShiroikumaLook.toast (this, e.getClass ().getSimpleName ());
      }
  }

  // --- the pending-import relaunch --------------------------------------------------------------

  /**
   * Emacs is running in this process, so the home directory cannot be
   * replaced here: remember what to restore, start this page in a fresh
   * process and end this one.  The spooled archive is already in the cache.
   */
  private void
  relaunchForImport (Set<EmacsBackup.Cat> cats)
  {
    StringBuilder ids = new StringBuilder ();
    for (EmacsBackup.Cat c : cats)
      {
        if (ids.length () > 0)
          ids.append (',');
        ids.append (c.id);
      }
    ShiroikumaPrefs.eximport (this).edit ()
      .putBoolean (ShiroikumaPrefs.KEY_PENDING_IMPORT, true)
      .putString (ShiroikumaPrefs.KEY_PENDING_ITEMS, ids.toString ())
      .commit ();
    Intent intent = new Intent (this, getClass ());
    intent.addFlags (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    startActivity (intent);
    System.exit (0);
  }

  /** The fresh process: honour the marker exactly once, then restore.  */
  private void
  resumePendingImportIfAny ()
  {
    SharedPreferences prefs = ShiroikumaPrefs.eximport (this);
    if (!prefs.getBoolean (ShiroikumaPrefs.KEY_PENDING_IMPORT, false))
      return;
    String items = prefs.getString (ShiroikumaPrefs.KEY_PENDING_ITEMS, "");
    prefs.edit ().remove (ShiroikumaPrefs.KEY_PENDING_IMPORT).remove (ShiroikumaPrefs.KEY_PENDING_ITEMS)
      .commit ();
    File spool = ExportImportPanel.spoolFile (this);
    if (!spool.isFile ())
      return;
    if (EmacsBackup.emacsRunning ())
      {
        // Emacs came up first (the user opened it before this page): try the bounce once more.
        Set<EmacsBackup.Cat> cats = EmacsBackup.Cat.parse (items, null);
        relaunchForImport (cats);
        return;
      }
    Set<EmacsBackup.Cat> cats = EmacsBackup.Cat.parse (items, null);
    mPanel = new ExportImportPanel (this, new ExportImportPanel.Host () {
        @Override
        public void
        pickExportDir ()
        {
          ShiroikumaUiActivity.this.pickExportDir ();
        }

        @Override
        public void
        pickImportFile ()
        {
          startActivityForResult (SafDir.filePicker (), REQ_IMPORT_FILE);
        }

        @Override
        public void
        relaunchForImport (Set<EmacsBackup.Cat> cats)
        {
          ShiroikumaUiActivity.this.relaunchForImport (cats);
        }

        @Override
        public void
        startEmacs ()
        {
          ShiroikumaUiActivity.this.startEmacs ();
        }

        @Override
        public void
        onChainFinished ()
        {
          finish ();
        }
      });
    mPanel.resumePendingImport (cats);
  }

  private void
  startEmacs ()
  {
    Intent intent = new Intent (this, EmacsActivity.class);
    intent.addFlags (Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity (intent);
    finish ();
  }

  // --- automation rows (sister-app contract v2) -------------------------------------------------

  private void
  addAutomationRows ()
  {
    addSwitchRow (getString (R.string.shiroikuma_auto_switch), getString (R.string.shiroikuma_auto_switch_desc),
                  AutomationAuth.isEnabled (this), new OnToggle () {
        @Override
        public void
        onToggle (boolean checked)
        {
          AutomationAuth.setEnabled (ShiroikumaUiActivity.this, checked);
        }
      });
    addSwitchRow (getString (R.string.shiroikuma_auto_require_token),
                  getString (R.string.shiroikuma_auto_require_token_desc),
                  AutomationAuth.isTokenRequired (this), new OnToggle () {
        @Override
        public void
        onToggle (boolean checked)
        {
          AutomationAuth.setTokenRequired (ShiroikumaUiActivity.this, checked);
          // The token row appears/disappears with this switch; posted so the Switch finishes drawing.
          mHolder.post (new Runnable () {
              @Override
              public void
              run ()
              {
                buildRows ();
              }
            });
        }
      });
    if (AutomationAuth.isTokenRequired (this))
      addTokenRow ();
  }

  /** Tap = copy the full token; "Regenerate" on the right = a fresh secret.  */
  private void
  addTokenRow ()
  {
    LinearLayout row = makeRowContainer ();

    LinearLayout labels = new LinearLayout (this);
    labels.setOrientation (LinearLayout.VERTICAL);
    labels.setLayoutParams (new LinearLayout.LayoutParams (0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    labels.addView (ShiroikumaLook.text (this, getString (R.string.shiroikuma_auto_token), 16,
                                         ShiroikumaLook.INK, false));
    TextView value = ShiroikumaLook.text (this, AutomationAuth.abbreviate (AutomationAuth.token (this)), 13,
                                          ShiroikumaLook.DIM, false);
    value.setTypeface (Typeface.MONOSPACE);
    value.setPadding (0, dp (2), dp (8), 0);
    labels.addView (value);
    row.addView (labels);

    TextView regenerate = ShiroikumaLook.text (this, getString (R.string.shiroikuma_auto_regenerate), 15,
                                               ShiroikumaLook.INK, true);
    regenerate.setPadding (dp (12), dp (8), dp (4), dp (8));
    regenerate.setOnClickListener (new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          confirmRegenerateToken ();
        }
      });
    row.addView (regenerate);

    row.setOnClickListener (new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          ClipboardManager cb = (ClipboardManager) getSystemService (Context.CLIPBOARD_SERVICE);
          if (cb != null)
            cb.setPrimaryClip (ClipData.newPlainText ("automation_token",
                                                      AutomationAuth.token (ShiroikumaUiActivity.this)));
          ShiroikumaLook.toast (ShiroikumaUiActivity.this, getString (R.string.shiroikuma_auto_token_copied));
        }
      });
    mHolder.addView (row);
  }

  private void
  confirmRegenerateToken ()
  {
    ShiroikumaLook.show (new AlertDialog.Builder (this)
                         .setTitle (R.string.shiroikuma_auto_token_regen_title)
                         .setMessage (R.string.shiroikuma_auto_token_regen_msg)
                         .setPositiveButton (R.string.shiroikuma_auto_regenerate,
                                             new DialogInterface.OnClickListener () {
        @Override
        public void
        onClick (DialogInterface d, int which)
        {
          AutomationAuth.regenerateToken (ShiroikumaUiActivity.this);
          buildRows ();
          ShiroikumaLook.toast (ShiroikumaUiActivity.this,
                                getString (R.string.shiroikuma_auto_token_regenerated));
        }
      })
                         .setNegativeButton (android.R.string.cancel, null));
  }

  // --- Emacs options (the three stock rows, re-implemented) -------------------------------------

  private void
  addOptionsSection ()
  {
    addSection (getString (R.string.shiroikuma_section_options));
    addTwoLineRow (trim (getString (R.string.start_quick_title)), trim (getString (R.string.start_quick_caption)),
                   ShiroikumaLook.DIM, new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          restartEmacs ("--quick");
        }
      });
    addTwoLineRow (trim (getString (R.string.start_debug_init_title)),
                   trim (getString (R.string.start_debug_init_caption)),
                   ShiroikumaLook.DIM, new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          restartEmacs ("--debug-init");
        }
      });
    addTwoLineRow (trim (getString (R.string.erase_dump_title)), trim (getString (R.string.erase_dump_caption)),
                   ShiroikumaLook.DIM, new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          eraseDumpFile ();
        }
      });
  }

  /** Upstream's option strings are written with surrounding newlines.  */
  private static String
  trim (String s)
  {
    return s == null ? "" : s.trim ();
  }

  /**
   * Restart Emacs with an extra command-line argument: kill this process
   * after asking the system to start EmacsActivity with the argument (the
   * stock EmacsPreferencesActivity's own recipe).
   */
  private void
  restartEmacs (String argument)
  {
    Intent intent = new Intent (this, EmacsActivity.class);
    intent.addFlags (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    intent.putExtra (EmacsActivity.EXTRA_STARTUP_ARGUMENTS, new String[] { argument, });
    startActivity (intent);
    System.exit (0);
  }

  /** Erase Emacs's dump file and forget it, so the next start does not look for it.  */
  private void
  eraseDumpFile ()
  {
    String wantedDumpFile = "emacs-" + EmacsNative.getFingerprint () + ".pdmp";
    File file = new File (getFilesDir (), wantedDumpFile);
    boolean existed = file.exists ();
    if (existed)
      file.delete ();
    EmacsApplication.dumpFileName = null;
    ShiroikumaLook.toast (this, getString (existed ? R.string.shiroikuma_opt_dump_removed
                                           : R.string.shiroikuma_opt_dump_missing));
  }

  // --- Appearance -------------------------------------------------------------------------------

  private void
  addAppearanceSection ()
  {
    addSection (getString (R.string.shiroikuma_section_look));
    addSwitchRow (getString (R.string.shiroikuma_look_black_bars),
                  getString (R.string.shiroikuma_look_black_bars_desc),
                  ShiroikumaPrefs.blackBars (this), new OnToggle () {
        @Override
        public void
        onToggle (boolean checked)
        {
          ShiroikumaPrefs.setBlackBars (ShiroikumaUiActivity.this, checked);
        }
      });
    addSwitchRow (getString (R.string.shiroikuma_look_keep_screen_on),
                  getString (R.string.shiroikuma_look_keep_screen_on_desc),
                  ShiroikumaPrefs.keepScreenOn (this), new OnToggle () {
        @Override
        public void
        onToggle (boolean checked)
        {
          ShiroikumaPrefs.setKeepScreenOn (ShiroikumaUiActivity.this, checked);
        }
      });
    addCaption (getString (R.string.shiroikuma_look_caption));
  }

  // --- Reset ------------------------------------------------------------------------------------

  private void
  addResetSection ()
  {
    addSection (getString (R.string.shiroikuma_section_reset));
    addTwoLineRow (getString (R.string.shiroikuma_reset_row), getString (R.string.shiroikuma_reset_row_desc),
                   ShiroikumaLook.DIM, new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          confirmReset ();
        }
      });
  }

  private void
  confirmReset ()
  {
    ShiroikumaLook.show (new AlertDialog.Builder (this)
                         .setTitle (R.string.shiroikuma_reset_confirm_title)
                         .setMessage (R.string.shiroikuma_reset_confirm_msg)
                         .setPositiveButton (R.string.shiroikuma_reset_go, new DialogInterface.OnClickListener () {
        @Override
        public void
        onClick (DialogInterface d, int which)
        {
          ShiroikumaPrefs.reset (ShiroikumaUiActivity.this);
          buildRows ();
          ShiroikumaLook.toast (ShiroikumaUiActivity.this, getString (R.string.shiroikuma_reset_done));
        }
      })
                         .setNegativeButton (android.R.string.cancel, null));
  }
}
