/* The one §3 progress sender of the automation contract.  -*- java -*-

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

import android.content.Context;
import android.content.Intent;

import java.util.HashMap;
import java.util.Map;

/**
 * The <b>one</b> §3 progress sender, shared by both automation doors (a
 * port of raikidoban's {@code AutomationProgress} onto
 * {@link EmacsBackup.Progress}).
 *
 * <p>The contract is explicit that an app which already has a §1 sender
 * must <b>parameterise that one on the correlation-id extra rather than
 * write a second</b>, because two implementations of the same watchdog
 * drift, and the one that drifts is always the one nobody is looking at.
 * The only difference between the two doors is which extra carries the
 * correlation id: the broadcast door echoes {@code reply_id}, the provider
 * door hands out a {@code job_id}.  So the id is written into <b>every</b>
 * name in {@code correlationExtras} — the provider passes both, so one
 * progress reader on the caller's side serves both doors.
 *
 * <h3>What the numbers are</h3>
 *
 * Real counts, never a percentage.  {@link EmacsBackup} reports the
 * settings category as its position in the walk and the home directory as
 * files written of files planned plus bytes of bytes, so the {@code text}
 * is numbers-first either way: {@code 区分 1/2 — Settings} or
 * {@code ファイル 1234/8942 · 512 MB / 4.2 GB}.  {@code item} is always the
 * category <b>id</b>, which is how the caller's panel knows which row is
 * running.
 *
 * <h3>The heartbeat is the point, not a nicety</h3>
 *
 * 自由作業盤 treats every progress broadcast as proof the app is still
 * alive and <b>presumes an app silent for two minutes to be dead</b>.  A
 * home directory full of packages is written file by file, so this sender
 * is rarely quiet — but on the descriptor door the destination may be a
 * pipe that stalls for as long as 応用管理 is slow to drain it, so a
 * background thread re-sends the last true line every {@link #HEARTBEAT_MS}
 * even when the numbers have not moved.  Never a moving number invented to
 * look busy: a fabricated count cannot be told from progress.
 *
 * <p>Inert when the caller passed no {@code progress_action} (or no reply
 * package to aim it at — since API 26 an implicit broadcast reaches no
 * manifest receiver at all), so both callers construct one unconditionally.
 */
public final class AutomationProgress implements EmacsBackup.Progress
{
  private static final String UNIT_CATEGORY = "区分";
  private static final String UNIT_FILE = "ファイル";
  private static final long MIN_INTERVAL_MS = 500;
  /** Comfortably inside §3's 30 s floor, itself well inside the caller's two-minute patience.  */
  private static final long HEARTBEAT_MS = 20 * 1000L;
  private static final long HEARTBEAT_TICK_MS = 5 * 1000L;

  private final Context context;
  private final String action;
  private final String replyPackage;
  private final String[] correlationExtras;
  private final String correlationId;
  private final String appLabel;
  private final boolean active;
  /** Category label → id: the engine calls back with the label it shows the panel.  */
  private final Map<String, EmacsBackup.Cat> byLabel = new HashMap<String, EmacsBackup.Cat> ();

  private volatile String lastItem;
  private volatile String lastText;
  private volatile String lastUnit;
  private volatile long lastCurrent;
  private volatile long lastTotal;
  private volatile long lastBytes;
  private volatile long lastBytesTotal;
  private volatile boolean lastHasBytes;
  private volatile long lastSentMs;
  private volatile boolean running;
  private Thread heartbeat;

  public AutomationProgress (Context context, String action, String replyPackage,
                             String correlationId, String[] correlationExtras, String appLabel)
  {
    this.context = context.getApplicationContext ();
    this.action = action == null ? "" : action.trim ();
    this.replyPackage = replyPackage == null ? "" : replyPackage.trim ();
    this.correlationExtras = correlationExtras;
    this.correlationId = correlationId == null ? "" : correlationId;
    this.appLabel = appLabel;
    // A progress_action without a reply_package is not a weak progress
    // channel, it is none at all: every broadcast we send must carry
    // setPackage.
    this.active = !this.action.isEmpty () && !this.replyPackage.isEmpty ();
    for (EmacsBackup.Cat c : EmacsBackup.Cat.values ())
      byLabel.put (this.context.getString (c.labelRes), c);
  }

  /** Begin the heartbeat.  Safe to call on an inert sender (it does nothing).  */
  public void
  start ()
  {
    if (!active || running)
      return;
    running = true;
    heartbeat = new Thread (new Runnable () {
        @Override
        public void
        run ()
        {
          while (running)
            {
              try
                {
                  Thread.sleep (HEARTBEAT_TICK_MS);
                }
              catch (InterruptedException e)
                {
                  return;
                }
              if (!running || lastText == null)
                continue;
              if (System.currentTimeMillis () - lastSentMs >= HEARTBEAT_MS)
                // Same numbers, sent again: "still here", which is all the caller needs.
                emit (lastItem, lastText, lastUnit, lastCurrent, lastTotal, lastHasBytes,
                      lastBytes, lastBytesTotal);
            }
        }
      }, "shiroikuma-automation-heartbeat");
    heartbeat.setDaemon (true);
    heartbeat.start ();
  }

  /** Stop the heartbeat.  Always call this in a {@code finally}.  */
  public void
  stop ()
  {
    running = false;
    Thread t = heartbeat;
    if (t != null)
      {
        t.interrupt ();
        heartbeat = null;
      }
  }

  /**
   * The engine's callback.  {@code item} is the category label; for the
   * home directory {@code current}/{@code total} are files and
   * {@code bytes}/{@code bytesTotal} their content, for the settings the
   * category position and the size of {@code settings.json}.
   */
  @Override
  public void
  onProgress (String item, long current, long total, long bytes, long bytesTotal)
  {
    if (!active)
      return;
    // At most one every 500 ms — but the final one always goes out.
    boolean last = total > 0 && current >= total;
    long now = System.currentTimeMillis ();
    if (!last && now - lastSentMs < MIN_INTERVAL_MS)
      return;
    EmacsBackup.Cat cat = item == null ? null : byLabel.get (item);
    String id = cat == null ? null : cat.id;
    if (cat == EmacsBackup.Cat.DATA_HOME)
      emit (id, UNIT_FILE + " " + current + "/" + total + " · " + EmacsBackup.humanSize (bytes)
            + " / " + EmacsBackup.humanSize (bytesTotal),
            UNIT_FILE, current, total, true, bytes, bytesTotal);
    else
      emit (id, UNIT_CATEGORY + " " + current + "/" + total + " — " + item,
            UNIT_CATEGORY, current, total, false, 0, 0);
  }

  /**
   * A free-form line for a step that has no honest count of its own — the
   * import spooling the archive before it can be read.  It exists for the
   * heartbeat's sake: the caller needs to keep hearing something, and
   * inventing numbers we are not actually walking would be worse than
   * sending none.
   */
  public void
  note (String text)
  {
    if (!active)
      return;
    emit (null, text, UNIT_CATEGORY, 0, 0, false, 0, 0);
  }

  private void
  emit (String item, String text, String unit, long current, long total, boolean hasBytes,
        long bytes, long bytesTotal)
  {
    lastItem = item;
    lastText = text;
    lastUnit = unit;
    lastCurrent = current;
    lastTotal = total;
    lastHasBytes = hasBytes;
    lastBytes = bytes;
    lastBytesTotal = bytesTotal;
    lastSentMs = System.currentTimeMillis ();

    Intent out = new Intent (action);
    out.setPackage (replyPackage);
    // Without this a backgrounded caller never hears us, and on a clean
    // phone the caller may not have been launched at all.
    out.addFlags (Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    // The same id under every name the two doors use, so one reader serves both.
    for (String extra : correlationExtras)
      out.putExtra (extra, correlationId);
    out.putExtra ("app", appLabel);
    if (item != null)
      out.putExtra ("item", item);
    out.putExtra ("text", text);
    out.putExtra ("current", current);
    out.putExtra ("total", total);
    out.putExtra ("unit", unit);
    if (hasBytes)
      {
        out.putExtra ("bytes", bytes);
        out.putExtra ("bytes_total", bytesTotal);
      }
    try
      {
        context.sendBroadcast (out);
      }
    catch (Exception e)
      {
        // pass — a progress line that cannot be sent is not worth an export
      }
  }
}
