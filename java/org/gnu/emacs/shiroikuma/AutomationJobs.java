/* The automation jobs in flight and their stop flags.  -*- java -*-

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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The jobs the automation surface has started, and the flag each of them
 * watches to stop.  A port of raikidoban's {@code AutomationJobs}, with one
 * addition: a job may carry the §1 {@code reply_id} it answers, so that a
 * {@code CANCEL_EXPORT} broadcast — which names runs by that id, or by
 * nothing at all — can reach it.
 *
 * <p>What this owns is the mapping from the id a caller was handed to a
 * cancellation it can act on, which must outlive the binder call or the
 * broadcast that created it and be reachable from a service that never saw
 * the caller.  Both doors share it: the provider hands out a job id and
 * cancels by it; the receiver mints one for its own export and cancels by
 * the {@code reply_id} tag.
 *
 * <p>Process-local and never persisted, deliberately: a persisted "running"
 * flag survives the crash that stranded it and wedges the app for good.
 */
public final class AutomationJobs
{
  private static final class Job
  {
    /** The §1 correlation id this job answers, or null on the descriptor door.  */
    final String replyId;
    volatile boolean cancelled;

    Job (String replyId)
    {
      this.replyId = replyId;
    }
  }

  private static final ConcurrentHashMap<String, Job> sJobs = new ConcurrentHashMap<String, Job> ();

  private AutomationJobs ()
  {
  }

  /** A job of the descriptor door — cancelled by its own id only.  */
  public static String
  begin ()
  {
    return begin (null);
  }

  /**
   * A job answering the §1 request {@code replyId} (may be empty: a caller
   * that sent none can still cancel with none).  Cancelled by its id or by
   * {@link #cancelByReplyId}.
   */
  public static String
  begin (String replyId)
  {
    String id = UUID.randomUUID ().toString ();
    sJobs.put (id, new Job (replyId));
    return id;
  }

  /**
   * Ask a job to stop.  A no-op for an id that is finished or was never
   * real.
   *
   * <p>Deliberately silent: a cancel arriving after the work completed is
   * the normal race, not an error, and answering it as one would make every
   * well-behaved caller look broken.
   */
  public static void
  cancel (String jobId)
  {
    if (jobId == null)
      return;
    Job job = sJobs.get (jobId);
    if (job != null)
      job.cancelled = true;
  }

  /**
   * The §1 cancel: stop every export answering {@code replyId}, or — when it
   * is empty — every §1 export in flight (§1 forbids two at once, so that is
   * unambiguous).  Descriptor-door jobs are never touched by this.
   */
  public static void
  cancelByReplyId (String replyId)
  {
    for (Map.Entry<String, Job> e : sJobs.entrySet ())
      {
        Job job = e.getValue ();
        if (job.replyId == null)
          continue;
        if (replyId == null || replyId.isEmpty () || replyId.equals (job.replyId))
          job.cancelled = true;
      }
  }

  /** Polled at write boundaries — never mid-write, so a cancelled archive is never half a file.  */
  public static boolean
  isCancelled (String jobId)
  {
    Job job = jobId == null ? null : sJobs.get (jobId);
    return job != null && job.cancelled;
  }

  /** Whether the id names a job that was begun and has not finished.  */
  public static boolean
  isKnown (String jobId)
  {
    return jobId != null && sJobs.containsKey (jobId);
  }

  public static void
  finish (String jobId)
  {
    if (jobId != null)
      sJobs.remove (jobId);
  }
}
