/* The Export / Import window.  -*- java -*-

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
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.gnu.emacs.R;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Export / Import window — one black-yellow page that backs up and
 * restores everything of 白い熊 GNU Emacs, in the family's shared visual
 * format: one bordered rounded box carrying a centred title, a dim
 * description, a bordered tappable directory box (red when unset), the
 * last-backup line, a divider, 「Select all」 + the category checkboxes, a
 * divider, and the button bar — round pills, Cancel alone on the left,
 * Import + Export grouped on the right.  While an export or import runs a
 * progress line (files n/N · MB) sits under the status line.
 *
 * <p>All work goes through {@link EmacsBackup}.  A successful export ends
 * with a bordered info dialog whose OK closes the whole chain (info dialog
 * → this panel → the UI page, via {@link Host#onChainFinished()}); failures
 * leave the panel open.  An import that includes the home directory while
 * Emacs is running cannot proceed in this process: after a confirmation
 * the panel spools the archive, writes the pending-import marker and hands
 * the relaunch to the host ({@link Host#relaunchForImport}); the fresh
 * process resumes through {@link #resumePendingImport}.
 */
public final class ExportImportPanel
{
  /** What the hosting activity provides: the SAF pickers, the relaunch, and the chain close.  */
  public interface Host
  {
    void pickExportDir ();

    void pickImportFile ();

    /** Write the marker and bounce the process (Emacs is running).  */
    void relaunchForImport (Set<EmacsBackup.Cat> cats);

    /** The 「Start Emacs」 pill of the import result.  */
    void startEmacs ();

    void onChainFinished ();
  }

  private static final long PROGRESS_INTERVAL_MS = 150;

  private final Activity mActivity;
  private final Host mHost;

  private final Set<EmacsBackup.Cat> mSelected = new LinkedHashSet<EmacsBackup.Cat> (EmacsBackup.Cat.defaults ());

  private AlertDialog mDialog;
  private LinearLayout mBox;
  private TextView mStatusLine;
  private TextView mProgressLine;
  private final List<Button> mButtons = new ArrayList<Button> ();
  private volatile boolean mBusy;
  private long mLastProgressTs;
  private int mStatusGeneration;

  public ExportImportPanel (Activity activity, Host host)
  {
    mActivity = activity;
    mHost = host;
  }

  public boolean
  isShowing ()
  {
    return mDialog != null && mDialog.isShowing ();
  }

  public void
  show ()
  {
    mBox = new LinearLayout (mActivity);
    mBox.setOrientation (LinearLayout.VERTICAL);
    mBox.setPadding (dp (20), dp (16), dp (20), dp (20));
    mBox.setBackground (ShiroikumaLook.panelBackground (mActivity, 16));

    ScrollView scroll = new ScrollView (mActivity);
    int m = dp (10);
    scroll.setPadding (m, m, m, m);
    scroll.setClipToPadding (false);
    scroll.addView (mBox, new ViewGroup.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT,
                                                       ViewGroup.LayoutParams.WRAP_CONTENT));

    mDialog = new AlertDialog.Builder (mActivity).setView (scroll).create ();
    mDialog.setOnDismissListener (new DialogInterface.OnDismissListener () {
        @Override
        public void
        onDismiss (DialogInterface d)
        {
          mDialog = null;
        }
      });
    mDialog.show ();
    ShiroikumaLook.transparentWindow (mDialog);
    rebuild ();
  }

  public void
  dismiss ()
  {
    if (mDialog != null)
      {
        mDialog.dismiss ();
        mDialog = null;
      }
  }

  // ---- content ---------------------------------------------------------------------------------

  public void
  rebuild ()
  {
    if (mBox == null)
      return;
    mBox.removeAllViews ();
    mButtons.clear ();

    TextView title = text (mActivity.getString (R.string.shiroikuma_eim_title), 18, ShiroikumaLook.INK, true);
    title.setGravity (Gravity.CENTER);
    title.setPadding (0, dp (2), 0, dp (6));
    mBox.addView (title);

    TextView desc = text (mActivity.getString (R.string.shiroikuma_eim_desc), 13, ShiroikumaLook.DIM, false);
    desc.setPadding (0, 0, 0, dp (10));
    mBox.addView (desc);

    mBox.addView (dirBox ());
    mStatusLine = statusLine ();
    mBox.addView (mStatusLine);
    mProgressLine = text ("", 13, ShiroikumaLook.INK, false);
    mProgressLine.setPadding (dp (2), 0, 0, dp (8));
    mProgressLine.setVisibility (View.GONE);
    mBox.addView (mProgressLine);

    mBox.addView (divider (0));

    final CheckBox selectAll = checkbox (mActivity.getString (R.string.shiroikuma_eim_select_all), true, 0);
    selectAll.setChecked (mSelected.size () == EmacsBackup.Cat.values ().length);
    selectAll.setOnClickListener (new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          if (selectAll.isChecked ())
            mSelected.addAll (EmacsBackup.Cat.all ());
          else
            mSelected.clear ();
          rebuild ();
        }
      });
    mBox.addView (selectAll);

    for (EmacsBackup.Cat cat : EmacsBackup.Cat.values ())
      mBox.addView (categoryRow (cat));

    mBox.addView (divider (8));
    mBox.addView (buttonRow ());
    setBusy (mBusy);
  }

  /** The folder box: bordered, clearly tappable — small label over the value, red when unset.  */
  private View
  dirBox ()
  {
    LinearLayout box = new LinearLayout (mActivity);
    box.setOrientation (LinearLayout.VERTICAL);
    box.setClickable (true);
    box.setPadding (dp (12), dp (10), dp (12), dp (10));
    GradientDrawable bg = ShiroikumaLook.panelBackground (mActivity, 10);
    box.setBackground (bg);
    box.setOnClickListener (new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          mHost.pickExportDir ();
        }
      });

    box.addView (text (mActivity.getString (R.string.shiroikuma_eim_dir), 12, ShiroikumaLook.INK, false));
    String label = SafDir.dirLabel (mActivity);
    String remembered = label == null ? SafDir.rememberedDirLabel (mActivity) : null;
    String shown = label != null ? label
      : remembered != null ? remembered
      : mActivity.getString (R.string.shiroikuma_eim_dir_unset);
    box.addView (text (shown, 15, label != null ? ShiroikumaLook.INK : ShiroikumaLook.WARN, true));
    if (remembered != null)
      {
        TextView hint = text (mActivity.getString (R.string.shiroikuma_eim_dir_regrant), 12,
                              ShiroikumaLook.WARN, false);
        hint.setPadding (0, dp (2), 0, 0);
        box.addView (hint);
      }

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT,
                                                                  ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp (6);
    lp.bottomMargin = dp (6);
    box.setLayoutParams (lp);
    return box;
  }

  /** The last-backup line; the directory query runs off the UI thread.  */
  private TextView
  statusLine ()
  {
    final TextView tv = text ("", 14, ShiroikumaLook.DIM, false);
    tv.setPadding (dp (2), 0, 0, dp (8));
    final int generation = ++mStatusGeneration;
    if (SafDir.usableDir (mActivity) == null)
      {
        boolean remembered = SafDir.rememberedDirLabel (mActivity) != null;
        tv.setText (mActivity.getString (remembered ? R.string.shiroikuma_eim_warn_regrant
                                         : R.string.shiroikuma_eim_warn_nodir));
        tv.setTextColor (ShiroikumaLook.WARN);
        return tv;
      }
    tv.setText (mActivity.getString (R.string.shiroikuma_eim_querying));
    new Thread (new Runnable () {
        @Override
        public void
        run ()
        {
          final SafDir.Entry newest = SafDir.newestExport (mActivity);
          mActivity.runOnUiThread (new Runnable () {
              @Override
              public void
              run ()
              {
                if (generation != mStatusGeneration || mDialog == null)
                  return;
                if (newest == null)
                  {
                    tv.setText (mActivity.getString (R.string.shiroikuma_eim_warn_none));
                    tv.setTextColor (ShiroikumaLook.WARN);
                  }
                else
                  {
                    tv.setText (mActivity.getString (R.string.shiroikuma_eim_last,
                                                     formatTs (mActivity, newest.lastModified)
                                                     + " · " + EmacsBackup.humanSize (newest.size)));
                    tv.setTextColor (ShiroikumaLook.DIM);
                  }
              }
            });
        }
      }, "eim-status").start ();
    return tv;
  }

  static String
  formatTs (Activity activity, long ts)
  {
    return DateFormat.getDateFormat (activity).format (ts) + " "
      + DateFormat.getTimeFormat (activity).format (ts);
  }

  private View
  categoryRow (final EmacsBackup.Cat cat)
  {
    boolean isChild = cat.parentId != null;
    final CheckBox cb = checkbox (mActivity.getString (cat.labelRes), false, isChild ? dp (28) : 0);
    boolean parentOn = !isChild || mSelected.contains (EmacsBackup.Cat.byId (cat.parentId));
    cb.setChecked (mSelected.contains (cat) && parentOn);
    cb.setEnabled (parentOn);
    cb.setAlpha (parentOn ? 1f : 0.5f);
    cb.setOnClickListener (new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          boolean checked = cb.isChecked ();
          if (checked)
            mSelected.add (cat);
          else
            mSelected.remove (cat);
          boolean hasChildren = false;
          for (EmacsBackup.Cat other : EmacsBackup.Cat.values ())
            {
              if (cat.id.equals (other.parentId))
                {
                  hasChildren = true;
                  if (checked)
                    mSelected.add (other);
                  else
                    mSelected.remove (other);
                }
            }
          if (hasChildren)
            rebuild ();
        }
      });
    return cb;
  }

  /** The button bar: Cancel alone on the left, Import + Export grouped on the right.  */
  private View
  buttonRow ()
  {
    LinearLayout row = new LinearLayout (mActivity);
    row.setOrientation (LinearLayout.HORIZONTAL);
    row.setGravity (Gravity.CENTER_VERTICAL);
    row.setPadding (0, dp (14), 0, 0);

    row.addView (pill (mActivity.getString (android.R.string.cancel), new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          if (!mBusy)
            dismiss ();
        }
      }));
    View spacer = new View (mActivity);
    row.addView (spacer, new LinearLayout.LayoutParams (0, 0, 1f));
    Button importButton = pill (mActivity.getString (R.string.shiroikuma_eim_import),
                                new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          onImportClicked ();
        }
      });
    ((LinearLayout.LayoutParams) importButton.getLayoutParams ()).rightMargin = dp (8);
    row.addView (importButton);
    mButtons.add (importButton);
    Button exportButton = pill (mActivity.getString (R.string.shiroikuma_eim_export),
                                new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          onExportClicked ();
        }
      });
    row.addView (exportButton);
    mButtons.add (exportButton);
    return row;
  }

  private void
  setBusy (boolean busy)
  {
    mBusy = busy;
    for (Button b : mButtons)
      {
        b.setEnabled (!busy);
        b.setAlpha (busy ? 0.4f : 1f);
      }
    if (mDialog != null)
      mDialog.setCancelable (!busy);
    if (mProgressLine != null && !busy)
      mProgressLine.setVisibility (View.GONE);
  }

  /** Worker-thread progress → the in-panel line, at most every 150 ms (the last one always).  */
  private void
  postProgress (final String verb, final long current, final long total, final long bytes,
                final boolean force)
  {
    long now = SystemClock.uptimeMillis ();
    if (!force && now - mLastProgressTs < PROGRESS_INTERVAL_MS)
      return;
    mLastProgressTs = now;
    mActivity.runOnUiThread (new Runnable () {
        @Override
        public void
        run ()
        {
          if (mProgressLine == null || mDialog == null)
            return;
          mProgressLine.setVisibility (View.VISIBLE);
          mProgressLine.setText (mActivity.getString (R.string.shiroikuma_eim_progress, verb,
                                                      current, total, EmacsBackup.humanSize (bytes)));
        }
      });
  }

  // ---- export ----------------------------------------------------------------------------------

  private void
  onExportClicked ()
  {
    if (mBusy)
      return;
    if (mSelected.isEmpty ())
      {
        showInfo (mActivity.getString (R.string.shiroikuma_eim_export_fail_title),
                  mActivity.getString (R.string.shiroikuma_eim_none_selected), false);
        return;
      }
    final Uri tree = SafDir.usableDir (mActivity);
    if (tree == null)
      {
        mHost.pickExportDir ();
        return;
      }
    setBusy (true);
    final String verb = mActivity.getString (R.string.shiroikuma_eim_exporting);
    postProgress (verb, 0, 0, 0, true);
    final Set<EmacsBackup.Cat> cats = new LinkedHashSet<EmacsBackup.Cat> (mSelected);
    final String name = EmacsBackup.exportFileName ();
    new Thread (new Runnable () {
        @Override
        public void
        run ()
        {
          Uri part = null;
          String path;
          long size = 0;
          final EmacsBackup.Result result;
          try
            {
              // octet-stream, not zip: the external-storage provider forces the MIME
              // type's extension onto a name that lacks it (x.zip.part → x.zip.part.zip).
              part = SafDir.createFile (mActivity, tree, "application/octet-stream", name + ".part");
              OutputStream raw = mActivity.getContentResolver ().openOutputStream (part, "w");
              if (raw == null)
                throw new IOException ("cannot open " + name);
              OutputStream os = new BufferedOutputStream (raw, 256 * 1024);
              try
                {
                  result = EmacsBackup.export (mActivity, cats, os, new EmacsBackup.Progress () {
                      @Override
                      public void
                      onProgress (String item, long current, long total, long bytes, long bytesTotal)
                      {
                        postProgress (verb, current, total, bytes, current == total);
                      }
                    }, null);
                }
              finally
                {
                  os.close ();
                }
              Uri done = SafDir.rename (mActivity, part, name);
              part = null;
              SafDir.Entry st = SafDir.stat (mActivity, done);
              size = st != null ? st.size : 0;
              String abs = SafDir.absolutePathOf (tree, st != null && st.name != null ? st.name : name);
              path = abs != null ? abs : name;
            }
          catch (Throwable t)
            {
              if (part != null)
                SafDir.delete (mActivity, part);
              final String message = t.getMessage () != null ? t.getMessage ()
                : t.getClass ().getSimpleName ();
              mActivity.runOnUiThread (new Runnable () {
                  @Override
                  public void
                  run ()
                  {
                    setBusy (false);
                    showInfo (mActivity.getString (R.string.shiroikuma_eim_export_fail_title),
                              mActivity.getString (R.string.shiroikuma_eim_export_fail, message), false);
                  }
                });
              return;
            }
          final String shownPath = path;
          final long shownSize = size;
          mActivity.runOnUiThread (new Runnable () {
              @Override
              public void
              run ()
              {
                setBusy (false);
                showInfo (mActivity.getString (R.string.shiroikuma_eim_export_done_title),
                          mActivity.getString (R.string.shiroikuma_eim_export_done_body, shownPath,
                                               EmacsBackup.humanSize (shownSize), result.categories)
                          + "\n\n" + result.summary,
                          true);
              }
            });
        }
      }, "eim-export").start ();
  }

  // ---- import ----------------------------------------------------------------------------------

  private void
  onImportClicked ()
  {
    if (mBusy)
      return;
    if (mSelected.isEmpty ())
      {
        showInfo (mActivity.getString (R.string.shiroikuma_eim_import_fail_title),
                  mActivity.getString (R.string.shiroikuma_eim_none_selected), false);
        return;
      }
    final List<SafDir.Entry> backups = SafDir.listExports (mActivity);
    final List<CharSequence> labels = new ArrayList<CharSequence> ();
    for (SafDir.Entry f : backups)
      labels.add (f.name + "  ·  " + EmacsBackup.humanSize (f.size));
    labels.add (mActivity.getString (R.string.shiroikuma_eim_browse));
    ShiroikumaLook.show (new AlertDialog.Builder (mActivity)
                         .setTitle (R.string.shiroikuma_eim_pick_backup)
                         .setItems (labels.toArray (new CharSequence[0]),
                                    new DialogInterface.OnClickListener () {
        @Override
        public void
        onClick (DialogInterface d, int which)
        {
          if (which >= backups.size ())
            mHost.pickImportFile ();
          else
            runImport (backups.get (which).uri);
        }
      })
                         .setNegativeButton (android.R.string.cancel, null));
  }

  /** Called by the host after the SAF file picker returns.  */
  public void
  onImportFilePicked (Uri uri)
  {
    if (uri != null)
      runImport (uri);
  }

  /** The archive spooled to the cache: the one file the relaunch trick carries across.  */
  public static File
  spoolFile (Activity activity)
  {
    return new File (activity.getCacheDir (), "import.zip");
  }

  private void
  runImport (final Uri uri)
  {
    setBusy (true);
    final String verb = mActivity.getString (R.string.shiroikuma_eim_importing);
    postProgress (verb, 0, 0, 0, true);
    final Set<EmacsBackup.Cat> cats = new LinkedHashSet<EmacsBackup.Cat> (mSelected);
    new Thread (new Runnable () {
        @Override
        public void
        run ()
        {
          final File spool = spoolFile (mActivity);
          try
            {
              spool (uri, spool);
              List<String> present = EmacsBackup.categoriesIn (spool);
              if (present.isEmpty ())
                throw new IOException (mActivity.getString (R.string.shiroikuma_eim_import_none));
              if (cats.contains (EmacsBackup.Cat.DATA_HOME)
                  && present.contains (EmacsBackup.Cat.DATA_HOME.id) && EmacsBackup.emacsRunning ())
                {
                  // The home directory cannot be replaced under a live Emacs: bounce the process.
                  mActivity.runOnUiThread (new Runnable () {
                      @Override
                      public void
                      run ()
                      {
                        setBusy (false);
                        confirmRelaunch (cats);
                      }
                    });
                  return;
                }
              final EmacsBackup.Result result = EmacsBackup.importZip (mActivity, spool, cats,
                                                                       new EmacsBackup.Progress () {
                  @Override
                  public void
                  onProgress (String item, long current, long total, long bytes, long bytesTotal)
                  {
                    postProgress (verb, current, total, bytes, current == total);
                  }
                });
              spool.delete ();
              mActivity.runOnUiThread (new Runnable () {
                  @Override
                  public void
                  run ()
                  {
                    setBusy (false);
                    showImportResult (result.summary);
                  }
                });
            }
          catch (Throwable t)
            {
              spool.delete ();
              final String message = t.getMessage () != null ? t.getMessage ()
                : t.getClass ().getSimpleName ();
              mActivity.runOnUiThread (new Runnable () {
                  @Override
                  public void
                  run ()
                  {
                    setBusy (false);
                    showInfo (mActivity.getString (R.string.shiroikuma_eim_import_fail_title),
                              mActivity.getString (R.string.shiroikuma_eim_import_fail, message), false);
                  }
                });
            }
        }
      }, "eim-import").start ();
  }

  private void
  spool (Uri uri, File spool) throws IOException
  {
    InputStream is = mActivity.getContentResolver ().openInputStream (uri);
    if (is == null)
      throw new IOException ("no input stream");
    try
      {
        OutputStream os = new FileOutputStream (spool);
        try
          {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = is.read (buffer)) > 0)
              os.write (buffer, 0, n);
          }
        finally
          {
            os.close ();
          }
      }
    finally
      {
        is.close ();
      }
  }

  /** Emacs is running: say what the bounce costs, then let the host do it.  */
  private void
  confirmRelaunch (final Set<EmacsBackup.Cat> cats)
  {
    ShiroikumaLook.show (new AlertDialog.Builder (mActivity)
                         .setTitle (R.string.shiroikuma_eim_running_title)
                         .setMessage (R.string.shiroikuma_eim_running_body)
                         .setPositiveButton (R.string.shiroikuma_eim_running_go,
                                             new DialogInterface.OnClickListener () {
        @Override
        public void
        onClick (DialogInterface d, int which)
        {
          mHost.relaunchForImport (cats);
        }
      })
                         .setNegativeButton (android.R.string.cancel, new DialogInterface.OnClickListener () {
        @Override
        public void
        onClick (DialogInterface d, int which)
        {
          spoolFile (mActivity).delete ();
        }
      }));
  }

  /**
   * The fresh process after a bounce: show the panel in its "Restoring…"
   * shape (no buttons), run the spooled import, then the result dialog.
   */
  public void
  resumePendingImport (final Set<EmacsBackup.Cat> cats)
  {
    mBox = new LinearLayout (mActivity);
    mBox.setOrientation (LinearLayout.VERTICAL);
    mBox.setPadding (dp (22), dp (20), dp (22), dp (20));
    mBox.setBackground (ShiroikumaLook.panelBackground (mActivity, 16));
    TextView title = text (mActivity.getString (R.string.shiroikuma_eim_restoring_title), 19,
                           ShiroikumaLook.INK, true);
    mBox.addView (title);
    TextView body = text (mActivity.getString (R.string.shiroikuma_eim_restoring_body), 14,
                          ShiroikumaLook.DIM, false);
    body.setPadding (0, dp (10), 0, dp (6));
    mBox.addView (body);
    mProgressLine = text ("", 13, ShiroikumaLook.INK, false);
    mBox.addView (mProgressLine);

    mDialog = infoDialog (mBox, false);
    mDialog.show ();
    ShiroikumaLook.transparentWindow (mDialog);
    mBusy = true;

    final String verb = mActivity.getString (R.string.shiroikuma_eim_importing);
    postProgress (verb, 0, 0, 0, true);
    new Thread (new Runnable () {
        @Override
        public void
        run ()
        {
          final File spool = spoolFile (mActivity);
          try
            {
              final EmacsBackup.Result result = EmacsBackup.importZip (mActivity, spool, cats,
                                                                       new EmacsBackup.Progress () {
                  @Override
                  public void
                  onProgress (String item, long current, long total, long bytes, long bytesTotal)
                  {
                    postProgress (verb, current, total, bytes, current == total);
                  }
                });
              spool.delete ();
              mActivity.runOnUiThread (new Runnable () {
                  @Override
                  public void
                  run ()
                  {
                    mBusy = false;
                    showImportResult (result.summary);
                  }
                });
            }
          catch (Throwable t)
            {
              spool.delete ();
              final String message = t.getMessage () != null ? t.getMessage ()
                : t.getClass ().getSimpleName ();
              mActivity.runOnUiThread (new Runnable () {
                  @Override
                  public void
                  run ()
                  {
                    mBusy = false;
                    showInfo (mActivity.getString (R.string.shiroikuma_eim_import_fail_title),
                              mActivity.getString (R.string.shiroikuma_eim_import_fail, message), true);
                  }
                });
            }
        }
      }, "eim-restore").start ();
  }

  /**
   * The import result: a persistent bordered dialog with 「Close」 (closes
   * the whole chain) and 「Start Emacs」 (the host launches EmacsActivity,
   * which reads the restored files afresh).
   */
  private void
  showImportResult (String summary)
  {
    String body = summary + "\n\n" + mActivity.getString (R.string.shiroikuma_eim_import_hint);
    LinearLayout box = infoBox (mActivity.getString (R.string.shiroikuma_eim_import_done_title), body);
    final AlertDialog dialog = infoDialog (box, false);

    LinearLayout buttons = new LinearLayout (mActivity);
    buttons.setOrientation (LinearLayout.HORIZONTAL);
    buttons.setGravity (Gravity.END);
    buttons.setPadding (0, dp (16), 0, 0);
    Button close = pill (mActivity.getString (R.string.shiroikuma_eim_close), new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          dialog.dismiss ();
          dismiss ();
          mHost.onChainFinished ();
        }
      });
    ((LinearLayout.LayoutParams) close.getLayoutParams ()).rightMargin = dp (10);
    buttons.addView (close);
    buttons.addView (pill (mActivity.getString (R.string.shiroikuma_eim_start_emacs),
                           new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          dialog.dismiss ();
          dismiss ();
          mHost.startEmacs ();
        }
      }));
    box.addView (buttons);
    dialog.show ();
    ShiroikumaLook.transparentWindow (dialog);
  }

  // ---- the export directory --------------------------------------------------------------------

  /** Called by the host after the SAF folder picker returns (already stored).  */
  public void
  onDirPicked ()
  {
    rebuild ();
  }

  // ---- info dialogs ----------------------------------------------------------------------------

  /**
   * A bordered black-yellow info dialog with a single OK.  When
   * {@code closeChain} is set, acknowledging it closes this panel and the
   * UI page too; otherwise only the dialog goes, leaving the panel to retry.
   */
  private void
  showInfo (String title, String body, final boolean closeChain)
  {
    LinearLayout box = infoBox (title, body);
    final AlertDialog dialog = infoDialog (box, !closeChain);

    LinearLayout buttons = new LinearLayout (mActivity);
    buttons.setOrientation (LinearLayout.HORIZONTAL);
    buttons.setGravity (Gravity.END);
    buttons.setPadding (0, dp (16), 0, 0);
    buttons.addView (pill (mActivity.getString (android.R.string.ok), new View.OnClickListener () {
        @Override
        public void
        onClick (View v)
        {
          dialog.dismiss ();
          if (closeChain)
            {
              dismiss ();
              mHost.onChainFinished ();
            }
        }
      }));
    box.addView (buttons);
    dialog.show ();
    ShiroikumaLook.transparentWindow (dialog);
  }

  private LinearLayout
  infoBox (String title, String body)
  {
    LinearLayout box = new LinearLayout (mActivity);
    box.setOrientation (LinearLayout.VERTICAL);
    box.setPadding (dp (22), dp (20), dp (22), dp (16));
    box.setBackground (ShiroikumaLook.panelBackground (mActivity, 16));
    box.addView (text (title, 19, ShiroikumaLook.INK, true));
    TextView bodyView = text (body, 14, ShiroikumaLook.INK, false);
    bodyView.setPadding (0, dp (10), 0, 0);
    box.addView (bodyView);
    return box;
  }

  private AlertDialog
  infoDialog (View content, boolean cancelable)
  {
    ScrollView scroll = new ScrollView (mActivity);
    int m = dp (10);
    scroll.setPadding (m, m, m, m);
    scroll.setClipToPadding (false);
    scroll.addView (content, new ViewGroup.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT,
                                                          ViewGroup.LayoutParams.WRAP_CONTENT));
    AlertDialog dialog = new AlertDialog.Builder (mActivity).setView (scroll).create ();
    dialog.setCancelable (cancelable);
    return dialog;
  }

  // ---- view builders ---------------------------------------------------------------------------

  private TextView
  text (CharSequence s, int sizeSp, int color, boolean bold)
  {
    return ShiroikumaLook.text (mActivity, s, sizeSp, color, bold);
  }

  private CheckBox
  checkbox (String label, boolean bold, int indent)
  {
    CheckBox cb = new CheckBox (mActivity);
    cb.setText (label);
    cb.setTextColor (ShiroikumaLook.INK);
    cb.setTextSize (TypedValue.COMPLEX_UNIT_SP, 15);
    if (bold)
      cb.setTypeface (cb.getTypeface (), android.graphics.Typeface.BOLD);
    cb.setButtonTintList (ColorStateList.valueOf (ShiroikumaLook.INK));
    cb.setPadding (dp (8) + indent, dp (7), 0, dp (7));
    return cb;
  }

  private View
  divider (int topGap)
  {
    View v = new View (mActivity);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT,
                                                                  Math.max (1, dp (1)));
    lp.topMargin = dp (topGap);
    v.setLayoutParams (lp);
    v.setBackgroundColor (ShiroikumaLook.INK);
    v.setAlpha (0.4f);
    return v;
  }

  private Button
  pill (String label, View.OnClickListener onClick)
  {
    Button b = ShiroikumaLook.pill (mActivity, label, onClick);
    b.setLayoutParams (new LinearLayout.LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT,
                                                      ViewGroup.LayoutParams.WRAP_CONTENT));
    return b;
  }

  private int
  dp (float v)
  {
    return ShiroikumaLook.dp (mActivity, v);
  }
}
