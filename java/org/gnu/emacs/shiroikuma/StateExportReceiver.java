/* The §1 broadcast door of the sister-app automation contract.  -*- java -*-

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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.gnu.emacs.EmacsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sister-app <b>state-export automation contract</b> (保存復元) — the
 * wire shape every 白い熊 app exposes so one 自由作業盤 task can back them
 * all up headlessly.  A port of raikidoban's {@code StateExportReceiver},
 * with the export moved out of the receiver into {@link BackupService}
 * (walking {@code ~} takes longer than a broadcast window).
 *
 * <ul>
 * <li>{@code shiroikuma.emacs.action.EXPORT_STATE}: run the category-ZIP
 * export ({@link EmacsBackup}) with no UI.  Extras (all String):
 * {@code token} (OPTIONAL — checked only while 「Use authorization token?」
 * is on, silently ignored otherwise), {@code path} (optional absolute
 * directory; honoured only with All-Files-Access, which this app declares
 * — without the grant the reply is exactly {@code ERROR:no-storage-access},
 * never a silent fall-back), {@code items} (optional comma list of
 * {@link EmacsBackup.Cat} ids; absent/empty = the default set),
 * {@code progress_action} (optional), plus the reply trio
 * {@code reply_action} / {@code reply_package} / {@code reply_id}.</li>
 * <li>{@code shiroikuma.emacs.action.LIST_CATEGORIES}: gated the same way,
 * instant.  One {@code id<TAB>label<TAB>parent<TAB>on|off} line per
 * category; both of ours are top-level (empty third field) and {@code on}.</li>
 * <li>{@code shiroikuma.emacs.action.CANCEL_EXPORT}: stop a running export.
 * Extras {@code token} (the same gate, equally optional) and an optional
 * {@code reply_id} (absent = the export in flight).  Fire-and-forget — it
 * never replies, not even to a bad token, and is a silent no-op when
 * nothing is running.  The export unwinds at the next entry boundary,
 * deletes the {@code .part}, and answers its ORIGINAL request with
 * {@code ERROR:cancelled}.</li>
 * </ul>
 *
 * <p><b>ONE ZIP per request, always</b> — every category is an entry inside
 * the single archive, {@code shiroikuma-emacs_<yyyy-MM-dd_HH-mm-ss>.zip}
 * (identical to what the Export / Import panel writes, and importable by it).
 *
 * <p>Reply: a FRESH broadcast to {@code reply_package} with action
 * {@code reply_action}, extras {@code reply_id} (echoed verbatim) +
 * {@code result} = {@code OK:<path>|<bytes>|<human size>|<n> categories},
 * {@code OK:} + the category lines, or {@code ERROR:<reason>}.  Exactly one
 * terminal reply, guarded by an {@link AtomicBoolean} — here for the
 * synchronous answers, in the service for the export.  NO binders
 * (ResultReceiver/PendingIntent/Messenger) and NO reliance on the
 * ordered-broadcast result — EMUI severs both between third-party apps;
 * the plain reply broadcast is the only channel that works.
 * {@link Intent#FLAG_INCLUDE_STOPPED_PACKAGES} so a backgrounded/stopped
 * caller still hears us.
 *
 * <p>Security: exported with NO {@code android:permission}.  In v2 this
 * receiver is deliberately the <b>unauthenticated</b> half of the surface —
 * it only ever writes where it was told to and reports what it did.
 * Everything that moves data through a caller-supplied descriptor lives
 * behind {@link AutomationProvider}, which knows who is calling.  The
 * master switch (and the opt-in token) are {@link AutomationAuth}; both live
 * on the 白い熊 GNU Emacs UI page under Export / Import.
 */
public class StateExportReceiver extends BroadcastReceiver
{
  public static final String ACTION_EXPORT_STATE
    = EmacsConfig.APPLICATION_ID + ".action.EXPORT_STATE";
  public static final String ACTION_LIST_CATEGORIES
    = EmacsConfig.APPLICATION_ID + ".action.LIST_CATEGORIES";
  public static final String ACTION_CANCEL_EXPORT
    = EmacsConfig.APPLICATION_ID + ".action.CANCEL_EXPORT";

  // Contract extras — deliberately bare names, shared verbatim by every sister app.
  private static final String EXTRA_TOKEN = "token";
  private static final String EXTRA_PATH = "path";
  private static final String EXTRA_ITEMS = "items";
  private static final String EXTRA_PROGRESS_ACTION = "progress_action";
  private static final String EXTRA_REPLY_ACTION = "reply_action";
  private static final String EXTRA_REPLY_PACKAGE = "reply_package";
  private static final String EXTRA_REPLY_ID = "reply_id";
  private static final String EXTRA_RESULT = "result";

  @Override
  public void
  onReceive (Context context, Intent intent)
  {
    final Context app = context.getApplicationContext ();
    final String action = intent == null ? null : intent.getAction ();
    if (action == null)
      return;
    final String token = intent.getStringExtra (EXTRA_TOKEN);
    final String replyAction = trimmed (intent.getStringExtra (EXTRA_REPLY_ACTION));
    final String replyPackage = trimmed (intent.getStringExtra (EXTRA_REPLY_PACKAGE));
    // Echoed back verbatim, never interpreted — not even trimmed.
    final String replyId = orEmpty (intent.getStringExtra (EXTRA_REPLY_ID));
    final String progressAction = trimmed (intent.getStringExtra (EXTRA_PROGRESS_ACTION));
    final String pathOverride = trimmed (intent.getStringExtra (EXTRA_PATH));
    final String items = trimmed (intent.getStringExtra (EXTRA_ITEMS));

    // Cancel is handled ahead of the replying gate below: it must never
    // answer anything, and a rejected token is silence too.  Safe to send
    // at any time — when nothing matches, nothing happens.
    if (ACTION_CANCEL_EXPORT.equals (action))
      {
        if (AutomationAuth.refuse (app, token) == null)
          AutomationJobs.cancelByReplyId (replyId);
        return;
      }

    final AtomicBoolean replied = new AtomicBoolean (false);
    final Replier reply = new Replier () {
        @Override
        public void
        send (String result)
        {
          if (!replied.compareAndSet (false, true))
            return;
          if (replyAction.isEmpty () || replyPackage.isEmpty ())
            return;
          Intent out = new Intent (replyAction);
          out.setPackage (replyPackage);
          out.addFlags (Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
          out.putExtra (EXTRA_REPLY_ID, replyId);
          out.putExtra (EXTRA_RESULT, result);
          app.sendBroadcast (out);
        }
      };

    // Gate first, in ONE place (contract v2 §2).  The switch is on by
    // default and the token is opt-in, so a `token' extra sent to this app
    // while it is not asking for one is IGNORED rather than refused.
    String refusal = AutomationAuth.refuse (app, token);
    if (refusal != null)
      {
        reply.send (refusal);
        return;
      }

    if (ACTION_LIST_CATEGORIES.equals (action))
      {
        StringBuilder sb = new StringBuilder ("OK:");
        boolean first = true;
        for (EmacsBackup.Cat cat : EmacsBackup.Cat.values ())
          {
            if (!first)
              sb.append ('\n');
            first = false;
            // id TAB label TAB parent TAB on|off — the parent field stays
            // present but empty for a top-level category, because the
            // "starts ticked" flag after it is positional.
            sb.append (cat.id).append ('\t').append (app.getString (cat.labelRes))
              .append ('\t').append (cat.parentId == null ? "" : cat.parentId)
              .append ('\t').append (cat.defaultSelected ? "on" : "off");
          }
        reply.send (sb.toString ());
        return;
      }

    if (!ACTION_EXPORT_STATE.equals (action))
      {
        reply.send ("ERROR:unknown action: " + action);
        return;
      }

    // `items': absent = the default set (what LIST_CATEGORIES calls `on');
    // an unknown id is refused here, before anything is started.
    List<String> unknown = new ArrayList<String> ();
    Set<EmacsBackup.Cat> cats = EmacsBackup.Cat.parse (items, unknown);
    if (!unknown.isEmpty ())
      {
        reply.send ("ERROR:unknown item " + AutomationProvider.join (unknown));
        return;
      }
    if (cats.isEmpty ())
      cats = EmacsBackup.Cat.defaults ();

    // Destination precedence: `path' extra → the configured export directory
    // → ERROR:no-directory.  This app declares MANAGE_EXTERNAL_STORAGE, so a
    // `path' it may not write is the keyed refusal — never a fall-back to
    // the configured directory, which would look successful and put the
    // archive outside the set the batch collects.
    String destPath = null;
    String destTree = null;
    if (!pathOverride.isEmpty ())
      {
        if (!hasAllFilesAccess ())
          {
            reply.send ("ERROR:no-storage-access");
            return;
          }
        destPath = pathOverride;
      }
    else
      {
        Uri tree = SafDir.usableDir (app);
        if (tree == null)
          {
            reply.send ("ERROR:no-directory");
            return;
          }
        destTree = tree.toString ();
      }

    // Hand off and get out: the export walks the whole home directory,
    // which a broadcast window cannot hold.  The start is a background
    // start on API 31+ and can be refused — an exception escaping onReceive
    // would take the process down, so it is answered instead.
    String jobId = AutomationJobs.begin (replyId);
    Intent service = BackupService.pathExport (app, jobId, replyId, replyAction, replyPackage,
                                               progressAction, items, destPath, destTree);
    try
      {
        app.startForegroundService (service);
      }
    catch (Throwable t)
      {
        AutomationJobs.finish (jobId);
        reply.send (BackupService.startFailure (app, t));
      }
  }

  /**
   * All-Files-Access — required to write a caller-supplied absolute path on
   * API 30+.  On 29 (this app's minSdk) legacy external storage plus
   * WRITE_EXTERNAL_STORAGE is the equivalent, and the manifest requests it.
   */
  private static boolean
  hasAllFilesAccess ()
  {
    return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager ();
  }

  private static String
  trimmed (String s)
  {
    return s == null ? "" : s.trim ();
  }

  private static String
  orEmpty (String s)
  {
    return s == null ? "" : s;
  }

  private interface Replier
  {
    void send (String result);
  }
}
