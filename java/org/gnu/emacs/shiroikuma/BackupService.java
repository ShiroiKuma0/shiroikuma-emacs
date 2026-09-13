/* The foreground service behind both automation doors.  -*- java -*-

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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where every automation export or import actually runs — the one
 * foreground service ({@code foregroundServiceType="dataSync"}, unexported)
 * behind both doors of the sister-app contract v2:
 *
 * <ul>
 * <li><b>§1, the path door</b> — {@link StateExportReceiver} validated an
 * {@code EXPORT_STATE} broadcast and hands over the destination: an
 * absolute directory (All-Files-Access held) or the configured SAF tree.
 * The ZIP is written as {@code <name>.part} and renamed on completion; the
 * partial is deleted on any failure or cancel.</li>
 * <li><b>§2a, the descriptor door</b> — {@link AutomationProvider} verified
 * the caller and hands over a duplicated {@link ParcelFileDescriptor}: an
 * export is written into it, an import is spooled from it to the cache,
 * validated there, then merged.</li>
 * </ul>
 *
 * <h3>Why a foreground service and not the receiver or the provider call</h3>
 *
 * A manifest receiver must return within the broadcast window (~10 s in
 * the foreground, ~60 s out of it) or the system ANRs and kills the
 * process mid-export; a binder call holds the caller for the duration.
 * Walking {@code ~/.emacs.d} with its packages takes longer than either.
 * EMUI additionally force-releases a background app's partial wakelock
 * seconds after it starts, so one is held here for the duration and
 * released in the {@code finally}.
 *
 * <h3>The recipe of {@link #onStartCommand}, in this order</h3>
 *
 * <ol>
 * <li><b>Read the extras</b> — microseconds, no early return.</li>
 * <li><b>{@code startForeground} inside a {@code try}.</b> A start from a
 * broadcast or a binder call is a background start, and API 31+ can refuse
 * it; if the promotion is refused, answer the terminal reply
 * ({@code ERROR:no-foreground-start} when the battery exemption would
 * help, a descriptive line otherwise), close the descriptor, stop.</li>
 * <li><b>Drain the handover</b>, then the early returns — <b>silent</b> on
 * a stale or already-claimed id, because that id's request has already had
 * its one terminal reply.</li>
 * <li>One job at a time: a second request while one runs is answered
 * {@code ERROR:export already running} and never touches the running one's
 * foreground state.</li>
 * <li>The worker thread, under a partial wakelock, with exactly one
 * terminal reply guarded by an {@link AtomicBoolean}.</li>
 * </ol>
 */
public class BackupService extends Service
{
  /** Low importance: a backup notification must never make a sound.  */
  public static final String CHANNEL = "shiroikuma_backup";
  /** Distinct from EmacsService's id 1 — both can be up at once.  */
  private static final int NOTIFICATION_ID = 4241;
  /** Long enough for a home directory full of packages, short enough not to strand the CPU.  */
  private static final long WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L;

  private static final String EXTRA_JOB = "job";
  private static final String EXTRA_DOOR = "door";
  private static final String DOOR_PATH = "path";
  private static final String DOOR_FD = "fd";
  private static final String EXTRA_IMPORTING = "importing";
  private static final String EXTRA_DEST_PATH = "dest_path";
  private static final String EXTRA_DEST_TREE = "dest_tree";

  /**
   * The descriptor's way across, because an Intent is the wrong vehicle
   * for one: a {@link ParcelFileDescriptor} in an Intent extra is
   * duplicated by the system on delivery and the copy's lifetime stops
   * being ours to reason about.  A map keyed by the job id keeps exactly
   * one open descriptor with exactly one owner.
   */
  private static final ConcurrentHashMap<String, ParcelFileDescriptor> HANDOVER
    = new ConcurrentHashMap<String, ParcelFileDescriptor> ();

  private static final Object sLock = new Object ();
  /** The job whose worker is running, or null; guarded by {@link #sLock}.  */
  private static String sActiveJob;
  /** The newest start id, so the job that ends last stops the service; guarded by {@link #sLock}.  */
  private static int sLatestStartId;

  // ---------------------------------------------------------------------------------------------
  // starting
  // ---------------------------------------------------------------------------------------------

  /**
   * The §1 export: to {@code destPath} (an absolute directory, All-Files-
   * Access already checked by the receiver) or, when that is null, into the
   * SAF tree {@code destTree}.  The caller starts it inside a {@code try}.
   */
  public static Intent
  pathExport (Context context, String jobId, String replyId, String replyAction,
              String replyPackage, String progressAction, String items, String destPath,
              String destTree)
  {
    Intent intent = new Intent (context, BackupService.class);
    intent.putExtra (EXTRA_JOB, jobId);
    intent.putExtra (EXTRA_DOOR, DOOR_PATH);
    intent.putExtra (EXTRA_IMPORTING, false);
    intent.putExtra (AutomationProvider.KEY_REPLY_ID, replyId);
    intent.putExtra (AutomationProvider.KEY_REPLY_ACTION, replyAction);
    intent.putExtra (AutomationProvider.KEY_REPLY_PACKAGE, replyPackage);
    intent.putExtra (AutomationProvider.KEY_PROGRESS_ACTION, progressAction);
    intent.putExtra (AutomationProvider.KEY_ITEMS, items);
    intent.putExtra (EXTRA_DEST_PATH, destPath);
    intent.putExtra (EXTRA_DEST_TREE, destTree);
    return intent;
  }

  /**
   * The §2a job.  Throws whatever {@code startForegroundService} throws —
   * the provider answers the refusal, closes the dup and drops the job —
   * and never leaves the descriptor in the handover map when it does.
   */
  public static void
  startDescriptorJob (Context context, String jobId, ParcelFileDescriptor fd, boolean importing,
                      Bundle extras)
  {
    HANDOVER.put (jobId, fd);
    Intent intent = new Intent (context, BackupService.class);
    intent.putExtra (EXTRA_JOB, jobId);
    intent.putExtra (EXTRA_DOOR, DOOR_FD);
    intent.putExtra (EXTRA_IMPORTING, importing);
    if (extras != null)
      {
        intent.putExtra (AutomationProvider.KEY_ITEMS, extras.getString (AutomationProvider.KEY_ITEMS));
        intent.putExtra (AutomationProvider.KEY_REPLY_ACTION,
                         extras.getString (AutomationProvider.KEY_REPLY_ACTION));
        intent.putExtra (AutomationProvider.KEY_REPLY_PACKAGE,
                         extras.getString (AutomationProvider.KEY_REPLY_PACKAGE));
        intent.putExtra (AutomationProvider.KEY_PROGRESS_ACTION,
                         extras.getString (AutomationProvider.KEY_PROGRESS_ACTION));
      }
    try
      {
        context.startForegroundService (intent);
      }
    catch (RuntimeException e)
      {
        HANDOVER.remove (jobId);
        throw e;
      }
  }

  /**
   * The reply for a refused start, by the contract's two-condition rule:
   * {@code ERROR:no-foreground-start} — which earns 白い熊 a 「電池最適化を
   * 除外」 button on the failed row — only when the throwable is
   * {@code ForegroundServiceStartNotAllowedException} <b>and</b> the app
   * is not already battery-exempt; a descriptive line otherwise.  The class
   * is matched by <b>name</b>, never {@code instanceof}: it is API 31 and
   * this app runs from 29.
   */
  public static String
  startFailure (Context context, Throwable t)
  {
    String name = t.getClass ().getSimpleName ();
    if ("ForegroundServiceStartNotAllowedException".equals (name) && !batteryExempt (context))
      return "ERROR:no-foreground-start";
    return "ERROR:cannot start export service: " + name;
  }

  static boolean
  batteryExempt (Context context)
  {
    try
      {
        PowerManager pm = (PowerManager) context.getSystemService (Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations (context.getPackageName ());
      }
    catch (Exception e)
      {
        return false;
      }
  }

  // ---------------------------------------------------------------------------------------------
  // one job
  // ---------------------------------------------------------------------------------------------

  /** One request: its extras, its descriptor (descriptor door), its one terminal reply.  */
  private final class Job
  {
    final String jobId;
    final boolean descriptorDoor;
    final boolean importing;
    final String items;
    final String replyAction;
    final String replyPackage;
    /** The §1 correlation id (echoed verbatim); on the descriptor door the job id itself.  */
    final String replyId;
    final String progressAction;
    final String destPath;
    final String destTree;
    ParcelFileDescriptor fd;
    final AtomicBoolean replied = new AtomicBoolean (false);

    Job (Intent intent)
    {
      jobId = intent == null ? null : trimmedOrNull (intent.getStringExtra (EXTRA_JOB));
      descriptorDoor = intent != null && DOOR_FD.equals (intent.getStringExtra (EXTRA_DOOR));
      importing = intent != null && intent.getBooleanExtra (EXTRA_IMPORTING, false);
      items = intent == null ? null : intent.getStringExtra (AutomationProvider.KEY_ITEMS);
      replyAction = intent == null ? "" : trimmed (intent.getStringExtra (AutomationProvider.KEY_REPLY_ACTION));
      replyPackage = intent == null ? "" : trimmed (intent.getStringExtra (AutomationProvider.KEY_REPLY_PACKAGE));
      progressAction = intent == null ? "" : trimmed (intent.getStringExtra (AutomationProvider.KEY_PROGRESS_ACTION));
      destPath = intent == null ? null : trimmedOrNull (intent.getStringExtra (EXTRA_DEST_PATH));
      destTree = intent == null ? null : trimmedOrNull (intent.getStringExtra (EXTRA_DEST_TREE));
      // §1's id is echoed verbatim, never interpreted — not even trimmed.
      String echoed = intent == null ? null : intent.getStringExtra (AutomationProvider.KEY_REPLY_ID);
      if (echoed == null)
        echoed = "";
      replyId = descriptorDoor ? (jobId == null ? "" : jobId) : echoed;
    }

    /** The extras every progress line and the reply carry the id under.  */
    String[]
    correlationExtras ()
    {
      return descriptorDoor
        ? new String[] { AutomationProvider.KEY_JOB_ID, AutomationProvider.KEY_REPLY_ID }
        : new String[] { AutomationProvider.KEY_REPLY_ID };
    }

    /**
     * Exactly one terminal answer per job, whatever path got here — a
     * synchronous failure and an asynchronous success must never both
     * fire.  A FRESH broadcast, never a binder; {@code setPackage}
     * unconditional, and nothing sent at all when there is nobody to
     * answer.
     */
    void
    reply (String result)
    {
      if (!replied.compareAndSet (false, true))
        return;
      AutomationJobs.finish (jobId);
      if (replyAction.isEmpty () || replyPackage.isEmpty ())
        return;
      Intent out = new Intent (replyAction);
      out.setPackage (replyPackage);
      // Without this a backgrounded caller never hears the answer, and on
      // a clean phone the caller may not have been launched at all.
      out.addFlags (Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
      for (String extra : correlationExtras ())
        out.putExtra (extra, replyId);
      out.putExtra (AutomationProvider.KEY_RESULT, result);
      try
        {
          sendBroadcast (out);
        }
      catch (Exception e)
        {
          // pass — nothing is left to answer with
        }
    }
  }

  @Override
  public IBinder
  onBind (Intent intent)
  {
    return null;
  }

  @Override
  public int
  onStartCommand (Intent intent, int flags, final int startId)
  {
    synchronized (sLock)
      {
        sLatestStartId = startId;
      }

    // 1. The extras — defensively, and no early return yet: once a caller
    //    has invoked startForegroundService the platform requires
    //    startForeground within the window whatever we then decide.
    final Job job = new Job (intent);

    // 2. Go foreground, guarded.  Refused → reply, release the descriptor, stop.
    try
      {
        goForeground (job.importing);
      }
    catch (Throwable t)
      {
        closeQuietly (job.jobId == null ? null : HANDOVER.remove (job.jobId));
        job.reply (startFailure (this, t));
        AutomationJobs.finish (job.jobId);
        stopSelf (startId);
        return START_NOT_STICKY;
      }

    // 3. Drain the handover, then the early returns — silent on a stale id.
    if (job.jobId == null || !AutomationJobs.isKnown (job.jobId))
      return bail (startId);
    if (job.descriptorDoor)
      {
        job.fd = HANDOVER.remove (job.jobId);
        if (job.fd == null)
          {
            AutomationJobs.finish (job.jobId);
            return bail (startId);
          }
      }

    // 4. One at a time.  The running job owns the foreground state and
    //    stops the service — with the newest start id — when it ends.
    synchronized (sLock)
      {
        if (sActiveJob != null)
          {
            closeQuietly (job.fd);
            job.reply ("ERROR:export already running");
            return START_NOT_STICKY;
          }
        sActiveJob = job.jobId;
      }

    // 5. The worker owns the descriptor from here.
    new Thread (new Runnable () {
        @Override
        public void
        run ()
        {
          work (job);
        }
      }, "shiroikuma-backup").start ();
    return START_NOT_STICKY;
  }

  private int
  bail (int startId)
  {
    synchronized (sLock)
      {
        if (sActiveJob == null)
          {
            stopForeground (STOP_FOREGROUND_REMOVE);
            stopSelf (startId);
          }
      }
    return START_NOT_STICKY;
  }

  private void
  goForeground (boolean importing)
  {
    NotificationManager manager
      = (NotificationManager) getSystemService (Context.NOTIFICATION_SERVICE);
    if (manager != null)
      manager.createNotificationChannel (new NotificationChannel (CHANNEL,
                                                                  getString (R.string.shiroikuma_backup_channel),
                                                                  NotificationManager.IMPORTANCE_LOW));
    Notification notification = new Notification.Builder (this, CHANNEL)
      .setContentTitle (getString (importing ? R.string.shiroikuma_auto_notif_import
                                   : R.string.shiroikuma_auto_notif_export))
      .setSmallIcon (R.drawable.shiroikuma_notification)
      .setOngoing (true)
      .build ();
    // The typed overload only where the type is enforced (API 34+); the
    // plain one below, which takes the manifest's dataSync.  Either can be
    // refused, which is what the caller's try is for.
    if (Build.VERSION.SDK_INT >= 34)
      startForeground (NOTIFICATION_ID, notification,
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else
      startForeground (NOTIFICATION_ID, notification);
  }

  // ---------------------------------------------------------------------------------------------
  // the worker
  // ---------------------------------------------------------------------------------------------

  private void
  work (Job job)
  {
    PowerManager.WakeLock wakeLock = null;
    try
      {
        PowerManager pm = (PowerManager) getSystemService (Context.POWER_SERVICE);
        if (pm != null)
          {
            wakeLock = pm.newWakeLock (PowerManager.PARTIAL_WAKE_LOCK, "shiroikuma.emacs:backup");
            wakeLock.acquire (WAKELOCK_TIMEOUT_MS);
          }
        if (!job.descriptorDoor)
          exportToDirectory (job);
        else if (job.importing)
          importFromDescriptor (job);
        else
          exportToDescriptor (job);
      }
    catch (EmacsBackup.CancelledException e)
      {
        // The terminal reply for the ORIGINAL request — sent even if nobody
        // is still listening, because it is what proves the run ended
        // rather than carried on unseen.
        job.reply ("ERROR:cancelled");
      }
    catch (Throwable t)
      {
        job.reply ("ERROR:" + AutomationProvider.describeThrowable (t));
      }
    finally
      {
        closeQuietly (job.fd);
        if (wakeLock != null && wakeLock.isHeld ())
          wakeLock.release ();
        AutomationJobs.finish (job.jobId);
        synchronized (sLock)
          {
            sActiveJob = null;
            stopForeground (STOP_FOREGROUND_REMOVE);
            stopSelf (sLatestStartId);
          }
      }
  }

  /** The categories of {@code items}, or null after replying about an unknown id.  */
  private Set<EmacsBackup.Cat>
  resolve (Job job)
  {
    List<String> unknown = new ArrayList<String> ();
    Set<EmacsBackup.Cat> cats = EmacsBackup.Cat.parse (job.items, unknown);
    if (!unknown.isEmpty ())
      {
        job.reply ("ERROR:unknown item " + AutomationProvider.join (unknown));
        return null;
      }
    return cats.isEmpty () ? EmacsBackup.Cat.defaults () : cats;
  }

  private EmacsBackup.Cancel
  cancelOf (final Job job)
  {
    return new EmacsBackup.Cancel () {
        @Override
        public boolean
        isCancelled ()
        {
          return AutomationJobs.isCancelled (job.jobId);
        }
      };
  }

  private AutomationProgress
  progressOf (Job job)
  {
    return new AutomationProgress (this, job.progressAction, job.replyPackage, job.replyId,
                                   job.correlationExtras (),
                                   getString (R.string.shiroikuma_app_name));
  }

  // --- §1: to a directory -----------------------------------------------------------------------

  private void
  exportToDirectory (Job job) throws IOException
  {
    Set<EmacsBackup.Cat> cats = resolve (job);
    if (cats == null)
      return;
    AutomationProgress progress = progressOf (job);
    EmacsBackup.Cancel cancel = cancelOf (job);
    String name = EmacsBackup.exportFileName ();
    // What we are half-way through writing, so an abort can take it back
    // out again: a cancelled or failed export must leave the backup
    // directory exactly as it found it.
    File partFile = null;
    Uri partDoc = null;
    boolean written = false;
    progress.start ();
    try
      {
        long bytes;
        String shownPath;
        if (job.destPath != null)
          {
            File dir = new File (job.destPath);
            dir.mkdirs ();
            if (!dir.isDirectory ())
              throw new IOException ("not a directory: " + job.destPath);
            File done = new File (dir, name);
            partFile = new File (dir, name + ".part");
            OutputStream os = new BufferedOutputStream (new FileOutputStream (partFile), 256 * 1024);
            try
              {
                EmacsBackup.export (this, cats, os, progress, cancel);
              }
            finally
              {
                os.close ();
              }
            if (!partFile.renameTo (done))
              throw new IOException ("cannot rename " + partFile.getName ());
            written = true;
            bytes = done.length ();
            shownPath = done.getAbsolutePath ();
          }
        else
          {
            Uri tree = Uri.parse (job.destTree);
            // octet-stream, not zip: the external-storage provider forces
            // the MIME type's extension onto a name that does not carry it,
            // and `x.zip.part' would come back as `x.zip.part.zip'.
            partDoc = SafDir.createFile (this, tree, "application/octet-stream", name + ".part");
            OutputStream raw = getContentResolver ().openOutputStream (partDoc, "w");
            if (raw == null)
              throw new IOException ("cannot open " + name + " for writing");
            CountingOutputStream counting = new CountingOutputStream (raw);
            OutputStream os = new BufferedOutputStream (counting, 256 * 1024);
            try
              {
                EmacsBackup.export (this, cats, os, progress, cancel);
              }
            finally
              {
                os.close ();
              }
            Uri done = SafDir.rename (this, partDoc, name);
            written = true;
            SafDir.Entry st = SafDir.stat (this, done);
            bytes = st != null && st.size > 0 ? st.size : counting.written;
            String finalName = st != null && st.name != null ? st.name : name;
            String abs = SafDir.absolutePathOf (tree, finalName);
            if (abs == null)
              {
                String label = SafDir.dirLabel (this);
                abs = label != null ? label + "/" + finalName : finalName;
              }
            shownPath = abs;
          }
        job.reply ("OK:" + shownPath + "|" + bytes + "|" + EmacsBackup.humanSize (bytes) + "|"
                   + cats.size () + " categories");
      }
    finally
      {
        progress.stop ();
        if (!written)
          {
            if (partFile != null)
              partFile.delete ();
            if (partDoc != null)
              SafDir.delete (this, partDoc);
          }
      }
  }

  // --- §2a: into the caller's descriptor ---------------------------------------------------------

  private void
  exportToDescriptor (Job job) throws IOException
  {
    Set<EmacsBackup.Cat> cats = resolve (job);
    if (cats == null)
      return;
    AutomationProgress progress = progressOf (job);
    EmacsBackup.Cancel cancel = cancelOf (job);
    progress.start ();
    // Counted as it goes rather than stat'ed afterwards: the caller owns the
    // file and we may not be able to see it at all — it can be an anonymous
    // pipe, or a descriptor into a directory this app cannot list.
    CountingOutputStream counting
      = new CountingOutputStream (new ParcelFileDescriptor.AutoCloseOutputStream (job.fd));
    OutputStream os = new BufferedOutputStream (counting, 256 * 1024);
    try
      {
        EmacsBackup.export (this, cats, os, progress, cancel);
        os.flush ();
      }
    finally
      {
        progress.stop ();
        os.close ();
        // AutoCloseOutputStream closed the descriptor; the finally of work() must not again.
        job.fd = null;
      }
    if (AutomationJobs.isCancelled (job.jobId))
      job.reply ("ERROR:cancelled");
    else
      job.reply ("OK:" + counting.written + "|" + EmacsBackup.humanSize (counting.written) + "|"
                 + cats.size () + " categories");
  }

  // --- §2a: the import — the half that exists ONLY behind the provider ----------------------------

  /**
   * Spool the archive to a cache file, validate it there, then apply it.
   *
   * <p>Reading the whole archive before touching anything is deliberate: a
   * partial read that failed half way would import half an archive, and a
   * half-restored home directory is worse than one that refused.  But "read
   * it all first" must not mean "hold it all in RAM" — this app's backup
   * carries the whole of {@code ~}, so the bound is disk, not memory.
   */
  private void
  importFromDescriptor (Job job) throws IOException
  {
    File spool = new File (getCacheDir (), "automation-import-" + job.jobId + ".zip");
    AutomationProgress progress = progressOf (job);
    progress.start ();
    try
      {
        // Spooling has no honest count of its own, so it says what it is
        // doing and lets the heartbeat carry the caller until the real
        // per-category numbers begin.
        progress.note (getString (R.string.shiroikuma_auto_notif_spooling));
        long total = 0;
        InputStream in = new ParcelFileDescriptor.AutoCloseInputStream (job.fd);
        try
          {
            OutputStream out = new FileOutputStream (spool);
            try
              {
                byte[] chunk = new byte[64 * 1024];
                int read;
                while ((read = in.read (chunk)) != -1)
                  {
                    out.write (chunk, 0, read);
                    total += read;
                  }
                out.flush ();
              }
            finally
              {
                out.close ();
              }
          }
        finally
          {
            in.close ();
            job.fd = null;
          }
        if (total == 0)
          {
            job.reply ("ERROR:empty archive");
            return;
          }

        // Every category the archive actually carries, not every category
        // we know about: asking for one the archive lacks is how a restore
        // ends up reporting success over nothing.
        List<String> present = EmacsBackup.categoriesIn (spool);
        if (present.isEmpty ())
          {
            job.reply ("ERROR:" + getString (R.string.shiroikuma_eim_import_none));
            return;
          }
        Set<EmacsBackup.Cat> wanted = resolve (job);
        if (wanted == null)
          return;
        Set<EmacsBackup.Cat> cats = new LinkedHashSet<EmacsBackup.Cat> ();
        for (EmacsBackup.Cat cat : EmacsBackup.Cat.values ())
          if (wanted.contains (cat) && present.contains (cat.id))
            cats.add (cat);
        if (cats.isEmpty ())
          {
            job.reply ("ERROR:archive carries none of the requested categories");
            return;
          }
        // The provider refused this before minting the job; checked again
        // here because Emacs may have been started in the meantime, and a
        // live Emacs writes its own files back over a restored home.
        if (cats.contains (EmacsBackup.Cat.DATA_HOME) && EmacsBackup.emacsRunning ())
          {
            job.reply (AutomationProvider.ERROR_EMACS_RUNNING);
            return;
          }
        if (AutomationJobs.isCancelled (job.jobId))
          throw new EmacsBackup.CancelledException ();

        EmacsBackup.Result result = EmacsBackup.importZip (this, spool, cats, progress);
        // Flush synchronously BEFORE replying OK: 応用管理 force-stops us the
        // instant we answer (SIGKILL), and an apply() still in flight would
        // be lost.  The engine's merge already commit()s; an empty commit()
        // per prefs file the restore spans blocks on that file's write
        // lock until anything queued has landed.
        ShiroikumaPrefs.ui (this).edit ().commit ();
        job.reply ("OK:" + cats.size () + " categories restored"
                   + (result.summary.isEmpty () ? "" : "; " + result.summary.replace ('\n', ' ')));
      }
    finally
      {
        progress.stop ();
        // Never leave it behind: it is a complete copy of the home
        // directory sitting in cache.
        if (spool.exists () && !spool.delete ())
          spool.deleteOnExit ();
      }
  }

  // ---------------------------------------------------------------------------------------------

  /** Bytes that actually reached the destination, for the reply and for a fallback size.  */
  private static final class CountingOutputStream extends OutputStream
  {
    private final OutputStream out;
    long written;

    CountingOutputStream (OutputStream out)
    {
      this.out = out;
    }

    @Override
    public void
    write (int b) throws IOException
    {
      out.write (b);
      written++;
    }

    @Override
    public void
    write (byte[] b, int off, int len) throws IOException
    {
      out.write (b, off, len);
      written += len;
    }

    @Override
    public void
    flush () throws IOException
    {
      out.flush ();
    }

    @Override
    public void
    close () throws IOException
    {
      out.close ();
    }
  }

  private static void
  closeQuietly (ParcelFileDescriptor fd)
  {
    if (fd == null)
      return;
    try
      {
        fd.close ();
      }
    catch (Exception e)
      {
        // pass — an already-closed descriptor is the normal case here
      }
  }

  private static String
  trimmed (String s)
  {
    return s == null ? "" : s.trim ();
  }

  private static String
  trimmedOrNull (String s)
  {
    String t = trimmed (s);
    return t.isEmpty () ? null : t;
  }
}
