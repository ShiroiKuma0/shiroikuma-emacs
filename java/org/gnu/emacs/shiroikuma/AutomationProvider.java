/* The automation data door of the sister-app contract.  -*- java -*-

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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The data door: export this app's own state, and put it back, for a
 * caller we can identify — the §2a half of the sister-app contract v2, and
 * what makes a clean-phone restore possible.  It sits <i>alongside</i>
 * {@link StateExportReceiver} and replaces nothing.  Authority
 * {@code shiroikuma.emacs.automation}, exported, no permission (a port of
 * raikidoban's {@code AutomationProvider}).
 *
 * <h3>Why a provider and not the broadcast receiver next to it</h3>
 *
 * <b>A broadcast cannot tell you who sent it.</b> v1's answer to that was
 * the shared secret, which cannot survive the wipe this feature exists to
 * recover from.  A provider gets the caller's identity from the framework —
 * see {@link AutomationCallers} for what is checked and why a package-name
 * prefix would have been worse than the token it replaced.
 *
 * <p><b>And a list needs a synchronous answer.</b> 応用管理 draws a row per
 * installed app before any export exists; a broadcast round trip per app
 * to fill a list is the wrong shape entirely.
 *
 * <h3>What does NOT happen here</h3>
 *
 * The payload.  {@link #call} validates, starts {@link BackupService} and
 * returns — a home directory of packages over minutes inside a binder call
 * would block the caller, report no progress, refuse cancellation and die
 * silently if this process were killed.  The bytes go through a file
 * descriptor the caller opened, and the terminal answer comes back on the
 * broadcast the family already proved on EMUI.
 *
 * <h3>{@code describe} runs before this app exists</h3>
 *
 * A provider is published before {@code Application.onCreate}, and on a
 * clean phone a {@code call()} is what starts the process at all.  So
 * {@link #describe} answers from the PackageManager, the {@link EmacsBackup.Cat}
 * enum and string resources only — never from {@code EmacsNative}, which
 * loads {@code libemacs.so}, and never from anything the application's
 * {@code onCreate} sets up.
 *
 * <h3>{@code import} exists ONLY here</h3>
 *
 * It never gets a broadcast action.  An import overwrites the home
 * directory, and the §1 receiver is {@code exported="true"} with no
 * permission — an import there would let any app on the phone wipe
 * {@code ~/.emacs.d}.  And a home restore is refused while Emacs runs in
 * this process (a live Emacs would write its own files back over the
 * restore); the refusal names the remedy.
 */
public class AutomationProvider extends ContentProvider
{
  public static final String METHOD_DESCRIBE = "describe";
  public static final String METHOD_EXPORT = "export";
  public static final String METHOD_IMPORT = "import";
  public static final String METHOD_CANCEL = "cancel";

  public static final String KEY_RESULT = "result";
  public static final String KEY_FD = "fd";
  public static final String KEY_TOKEN = "token";
  public static final String KEY_JOB_ID = "job_id";
  /** The broadcast door's correlation extra.  The provider door mirrors its
   * job_id into it too, so ONE progress/reply reader on the caller's side
   * serves both doors.  */
  public static final String KEY_REPLY_ID = "reply_id";
  public static final String KEY_ITEMS = "items";
  public static final String KEY_REPLY_ACTION = "reply_action";
  public static final String KEY_REPLY_PACKAGE = "reply_package";
  public static final String KEY_PROGRESS_ACTION = "progress_action";

  /** This app's archive format — the manifest's {@code version}.  Bumped when
   * an older build could no longer read what we write.  */
  public static final int FORMAT = EmacsBackup.VERSION;

  /**
   * The oldest archive this build can still read.
   *
   * <p>Version skew has a direction: old data into a newer app is normally
   * fine, because an app migrates its own storage; newer data into an older
   * app is not.  This field is what lets a caller refuse the second case at
   * discovery time, before anything is streamed.
   */
  public static final int MIN_FORMAT_READABLE = 1;

  /** The exact line the contract's caller shows when a home restore meets a live Emacs.  */
  public static final String ERROR_EMACS_RUNNING
    = "ERROR:Emacs is running; quit it (C-x C-c) or force-stop, then retry";

  @Override
  public boolean
  onCreate ()
  {
    return true;
  }

  /**
   * Every method answers a {@link Bundle} with {@link #KEY_RESULT} —
   * {@code OK…} or {@code ERROR:…}, the same vocabulary the broadcast
   * contract uses, so a caller has one grammar to parse, not two.
   *
   * <p><b>A refusal is returned, never thrown</b>: an exception across a
   * binder reaches the caller as a {@code RuntimeException} carrying our
   * stack trace, which tells 白い熊 nothing and tells a misbehaving caller
   * rather more than it should.
   */
  @Override
  public Bundle
  call (String method, String arg, Bundle extras)
  {
    Context ctx = getContext ();
    if (ctx == null)
      return answer ("ERROR:not ready");
    ctx = ctx.getApplicationContext ();

    try
      {
        // WHO, before WHAT.  A caller we cannot identify gets the same
        // answer whatever it asked for.
        String refusedCaller = AutomationCallers.verify (ctx, getCallingPackage ());
        if (refusedCaller != null)
          return answer (refusedCaller);
        // Then this app's own switches — a token is ignored unless this app
        // asks for one (§2).
        String refused = AutomationAuth.refuse (ctx, extras == null ? null
                                                : extras.getString (KEY_TOKEN));
        if (refused != null)
          return answer (refused);

        if (METHOD_DESCRIBE.equals (method))
          return answer (describe (ctx));
        if (METHOD_EXPORT.equals (method))
          return start (ctx, extras, false);
        if (METHOD_IMPORT.equals (method))
          return start (ctx, extras, true);
        if (METHOD_CANCEL.equals (method))
          {
            AutomationJobs.cancel (extras == null ? null : extras.getString (KEY_JOB_ID));
            return answer ("OK:cancelled");
          }
        return answer ("ERROR:unknown method: " + method);
      }
    catch (Throwable t)
      {
        return answer ("ERROR:" + describeThrowable (t));
      }
  }

  /**
   * What this app would export, answered without exporting anything.
   *
   * <p>Returned from the call rather than written into the archive,
   * deliberately: 応用管理 must draw a row before an export exists, and at
   * restore must judge compatibility <b>before</b> streaming tens of
   * megabytes into an app that would reject them — which it cannot do if
   * the header is buried inside an encrypted archive.
   */
  private static String
  describe (Context ctx)
  {
    JSONObject header = new JSONObject ();
    try
      {
        header.put ("app_id", ctx.getPackageName ());
        long code = 0;
        String name = "";
        try
          {
            PackageInfo info = ctx.getPackageManager ().getPackageInfo (ctx.getPackageName (), 0);
            code = info.getLongVersionCode ();
            name = info.versionName == null ? "" : info.versionName;
          }
        catch (Exception e)
          {
            // pass — a header without a version is still a usable header
          }
        header.put ("version_code", code);
        header.put ("version_name", name);
        header.put ("format", FORMAT);
        header.put ("min_format_readable", MIN_FORMAT_READABLE);
        // The import merges into the app's own data directory and 応用管理
        // force-stops us the moment we report success — a never-launched
        // install is fine and this app does not have to claim the exception.
        header.put ("requires_launch_first", false);
        // Every write of the restore is the app's own files under
        // /data/data/<pkg> and its own SharedPreferences: no runtime
        // permission guards any of it.
        header.put ("requires_permissions", new JSONArray ());
        JSONArray contains = new JSONArray ();
        for (EmacsBackup.Cat cat : EmacsBackup.Cat.defaults ())
          // Top-level parts only: `contains' is a short human summary for a
          // list row, not the category picker, which LIST_CATEGORIES
          // already answers in full.
          if (cat.parentId == null)
            contains.put (ctx.getString (cat.labelRes));
        header.put ("contains", contains);
      }
    catch (Exception e)
      {
        return "ERROR:" + describeThrowable (e);
      }
    return "OK:" + header.toString ();
  }

  /**
   * Hand the descriptor to {@link BackupService} and get out of the way.
   *
   * <p>The descriptor is <b>duplicated</b> before it leaves this method.
   * The one in {@code extras} belongs to the binder transaction and is
   * closed when {@code call()} returns; a service reading it afterwards
   * would find it shut.  That is a bug you only see under load, so it is
   * not left to the service to remember.
   *
   * <p>The refusals that can be known now are answered now, before a job
   * id is minted — a caller is never handed an {@code OK:<job_id>} for a
   * job that will not run: an unknown item, a home restore under a live
   * Emacs, a service that could not be started.
   */
  @SuppressWarnings ("deprecation")
  private static Bundle
  start (Context ctx, Bundle extras, boolean importing)
  {
    ParcelFileDescriptor fd = extras == null ? null
      : (ParcelFileDescriptor) extras.getParcelable (KEY_FD);
    if (fd == null)
      return answer ("ERROR:no descriptor");

    String items = extras.getString (KEY_ITEMS);
    List<String> unknown = new ArrayList<String> ();
    Set<EmacsBackup.Cat> wanted = EmacsBackup.Cat.parse (items, unknown);
    if (!unknown.isEmpty ())
      return answer ("ERROR:unknown item " + join (unknown));
    if (importing && wanted.contains (EmacsBackup.Cat.DATA_HOME) && EmacsBackup.emacsRunning ())
      return answer (ERROR_EMACS_RUNNING);

    ParcelFileDescriptor dup;
    try
      {
        dup = fd.dup ();
      }
    catch (Exception e)
      {
        return answer ("ERROR:descriptor unusable");
      }
    String jobId = AutomationJobs.begin ();
    try
      {
        BackupService.startDescriptorJob (ctx, jobId, dup, importing, extras);
      }
    catch (Throwable t)
      {
        // Never strand the caller's open file in a map nothing will read,
        // and never hand out an id for a job that will not run.
        AutomationJobs.finish (jobId);
        closeQuietly (dup);
        return answer (BackupService.startFailure (ctx, t));
      }
    return answer ("OK:" + jobId);
  }

  static String
  join (List<String> ids)
  {
    StringBuilder sb = new StringBuilder ();
    for (String id : ids)
      {
        if (sb.length () > 0)
          sb.append (',');
        sb.append (id);
      }
    return sb.toString ();
  }

  static String
  describeThrowable (Throwable t)
  {
    String message = t.getMessage ();
    return message != null && !message.isEmpty () ? message : t.getClass ().getSimpleName ();
  }

  private static void
  closeQuietly (ParcelFileDescriptor fd)
  {
    try
      {
        fd.close ();
      }
    catch (Exception e)
      {
        // pass
      }
  }

  private static Bundle
  answer (String result)
  {
    Bundle b = new Bundle ();
    b.putString (KEY_RESULT, result);
    return b;
  }

  // A provider that is only ever call()ed still has to answer these.
  // Refusing loudly beats returning an empty cursor, which reads downstream
  // as "there is no data" rather than "wrong door".

  @Override
  public Cursor
  query (Uri uri, String[] projection, String selection, String[] args, String order)
  {
    throw new UnsupportedOperationException ("automation is call() only");
  }

  @Override
  public String
  getType (Uri uri)
  {
    return null;
  }

  @Override
  public Uri
  insert (Uri uri, ContentValues values)
  {
    throw new UnsupportedOperationException ("automation is call() only");
  }

  @Override
  public int
  delete (Uri uri, String selection, String[] args)
  {
    throw new UnsupportedOperationException ("automation is call() only");
  }

  @Override
  public int
  update (Uri uri, ContentValues values, String selection, String[] args)
  {
    throw new UnsupportedOperationException ("automation is call() only");
  }
}
