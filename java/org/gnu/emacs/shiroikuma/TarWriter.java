/* A tar writer for the home-directory backup.  -*- java -*-

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

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes a directory tree as a POSIX ustar stream the way
 * {@code cd <root>; tar -cf - <subdir>} would: entry names relative to
 * {@code root}, GNU {@code ././@LongLink} records for names and link targets
 * over 100 bytes, octal (or base-256 above 8 GiB) sizes, mode = {@code
 * st_mode & 07777}, mtime = {@code st_mtime}, uid/gid as found.  Types
 * written: regular file, directory, symbolic link ({@code Os.readlink}).
 * <b>Sockets, FIFOs and device nodes are skipped and counted</b> — the Emacs
 * {@code server} socket lives in HOME — exactly as the reference backup
 * script dropped tar's "socket ignored" lines.  Hard links are stored as
 * independent files.  Everything is decided by {@code Os.lstat}, so a
 * symlink to a directory is archived as the link, never followed.
 *
 * <p>A file that changes while being read is written to exactly the size in
 * its header (truncated, or zero-padded if it shrank), so the stream never
 * desynchronises.  Pure Java 7; no third-party jar.
 */
public final class TarWriter
{
  private static final int BLOCK = 512;
  private static final int RECORD = 10240;
  private static final String LONGLINK = "././@LongLink";

  /** Decides which entries go in; {@code rel} is the archive name ({@code files/...}).  */
  public interface Filter
  {
    boolean accept (String rel, boolean isDir);
  }

  /** Progress and cancellation, polled once per entry — never mid-write.  */
  public interface Listener
  {
    void onEntry (String rel, long filesDone, long bytesDone);

    boolean isCancelled ();
  }

  /** What a walk or a write found.  */
  public static final class Stats
  {
    /** Regular files.  */
    public long files;
    /** Directories.  */
    public long dirs;
    /** Symbolic links.  */
    public long links;
    /** Bytes of regular-file content.  */
    public long bytes;
    /** Sockets, FIFOs, devices and unreadable entries left out.  */
    public long skipped;
  }

  private final File root;
  private final Filter filter;
  private final OutputStream out;
  private final Listener listener;
  private final Stats stats = new Stats ();
  private final byte[] buffer = new byte[64 * 1024];
  private long written;

  private TarWriter (File root, Filter filter, OutputStream out, Listener listener)
  {
    this.root = root;
    this.filter = filter;
    this.out = out;
    this.listener = listener;
  }

  /** Pre-walk: the real counts an honest progress bar needs.  Writes nothing.  */
  public static Stats
  scan (File root, String subdir, Filter filter)
  {
    TarWriter w = new TarWriter (root, filter, null, null);
    try
      {
        w.walk (subdir, false);
      }
    catch (IOException e)
      {
        // cannot happen without an output stream
      }
    return w.stats;
  }

  /**
   * Write {@code subdir} (relative to {@code root}) as a complete tar
   * stream — entries, two zero blocks, padding to the 10 KiB record — to
   * {@code out}, which the caller closes.
   */
  public static Stats
  write (File root, String subdir, Filter filter, OutputStream out, Listener listener)
    throws IOException
  {
    TarWriter w = new TarWriter (root, filter, out, listener);
    w.walk (subdir, true);
    w.finish ();
    return w.stats;
  }

  // --- the walk ---------------------------------------------------------------------------------

  private void
  walk (String rel, boolean emit) throws IOException
  {
    if (emit && listener != null && listener.isCancelled ())
      throw new EmacsBackup.CancelledException ();

    String abs = new File (root, rel).getPath ();
    StructStat st;
    try
      {
        st = Os.lstat (abs);
      }
    catch (ErrnoException e)
      {
        stats.skipped++;
        return;
      }

    int mode = st.st_mode;
    if (OsConstants.S_ISDIR (mode))
      {
        if (filter != null && !filter.accept (rel, true))
          return;
        stats.dirs++;
        if (emit)
          header (rel + "/", (byte) '5', st, 0, null);
        String[] names = new File (abs).list ();
        if (names == null)
          return;
        Arrays.sort (names);
        for (String name : names)
          walk (rel + "/" + name, emit);
        return;
      }

    if (filter != null && !filter.accept (rel, false))
      return;

    if (OsConstants.S_ISREG (mode))
      {
        long size = st.st_size;
        stats.files++;
        if (emit)
          {
            header (rel, (byte) '0', st, size, null);
            copyExactly (abs, size);
            stats.bytes += size;
            if (listener != null)
              listener.onEntry (rel, stats.files, stats.bytes);
          }
        else
          stats.bytes += size;
        return;
      }

    if (OsConstants.S_ISLNK (mode))
      {
        String target;
        try
          {
            target = Os.readlink (abs);
          }
        catch (ErrnoException e)
          {
            stats.skipped++;
            return;
          }
        stats.links++;
        if (emit)
          header (rel, (byte) '2', st, 0, target);
        return;
      }

    // sockets, FIFOs, block/character devices: "socket ignored"
    stats.skipped++;
  }

  /** Copy exactly {@code size} bytes of {@code abs}; zero-pad a file that shrank, then pad the block.  */
  private void
  copyExactly (String abs, long size) throws IOException
  {
    long remaining = size;
    InputStream in = null;
    try
      {
        in = new FileInputStream (abs);
      }
    catch (IOException e)
      {
        // vanished between lstat and open: the header is written, so pad it out
      }
    try
      {
        while (remaining > 0 && in != null)
          {
            int n = in.read (buffer, 0, (int) Math.min (buffer.length, remaining));
            if (n < 0)
              break;
            out.write (buffer, 0, n);
            written += n;
            remaining -= n;
          }
      }
    finally
      {
        if (in != null)
          {
            try
              {
                in.close ();
              }
            catch (IOException e)
              {
                // pass
              }
          }
      }
    if (remaining > 0)
      zeros (remaining);
    padBlock ();
  }

  // --- headers ----------------------------------------------------------------------------------

  private void
  header (String name, byte type, StructStat st, long size, String link) throws IOException
  {
    byte[] nameBytes = name.getBytes (StandardCharsets.UTF_8);
    if (nameBytes.length > 100)
      longLink ((byte) 'L', nameBytes);
    byte[] linkBytes = link == null ? null : link.getBytes (StandardCharsets.UTF_8);
    if (linkBytes != null && linkBytes.length > 100)
      longLink ((byte) 'K', linkBytes);

    byte[] h = new byte[BLOCK];
    put (h, 0, 100, nameBytes);
    octal (h, 100, 8, st.st_mode & 07777);
    octal (h, 108, 8, st.st_uid);
    octal (h, 116, 8, st.st_gid);
    size (h, 124, size);
    octal (h, 136, 12, st.st_mtime);
    h[156] = type;
    if (linkBytes != null)
      put (h, 157, 100, linkBytes);
    ustar (h);
    checksum (h);
    out.write (h);
    written += BLOCK;
  }

  private void
  longLink (byte type, byte[] data) throws IOException
  {
    byte[] h = new byte[BLOCK];
    put (h, 0, 100, LONGLINK.getBytes (StandardCharsets.US_ASCII));
    octal (h, 100, 8, 0644);
    octal (h, 108, 8, 0);
    octal (h, 116, 8, 0);
    size (h, 124, data.length + 1);
    octal (h, 136, 12, 0);
    h[156] = type;
    ustar (h);
    checksum (h);
    out.write (h);
    out.write (data);
    out.write (0);
    written += BLOCK + data.length + 1;
    padBlock ();
  }

  private static void
  ustar (byte[] h)
  {
    put (h, 257, 6, "ustar".getBytes (StandardCharsets.US_ASCII)); // "ustar\0"
    h[263] = '0';
    h[264] = '0';
    octal (h, 329, 8, 0);
    octal (h, 337, 8, 0);
  }

  private static void
  checksum (byte[] h)
  {
    Arrays.fill (h, 148, 156, (byte) ' ');
    int sum = 0;
    for (int i = 0; i < BLOCK; i++)
      sum += h[i] & 0xFF;
    // six octal digits, NUL, space
    String s = Integer.toOctalString (sum);
    while (s.length () < 6)
      s = "0" + s;
    byte[] b = s.getBytes (StandardCharsets.US_ASCII);
    System.arraycopy (b, 0, h, 148, 6);
    h[154] = 0;
    h[155] = ' ';
  }

  private static void
  put (byte[] h, int off, int len, byte[] value)
  {
    System.arraycopy (value, 0, h, off, Math.min (len, value.length));
  }

  /** {@code len - 1} zero-padded octal digits and a NUL.  */
  private static void
  octal (byte[] h, int off, int len, long value)
  {
    String s = Long.toOctalString (Math.max (0, value));
    int digits = len - 1;
    if (s.length () > digits)
      s = s.substring (s.length () - digits);
    while (s.length () < digits)
      s = "0" + s;
    byte[] b = s.getBytes (StandardCharsets.US_ASCII);
    System.arraycopy (b, 0, h, off, digits);
    h[off + digits] = 0;
  }

  /** The 12-byte size: octal up to 8 GiB - 1, GNU base-256 above.  */
  private static void
  size (byte[] h, int off, long value)
  {
    if (value < 077777777777L)
      {
        octal (h, off, 12, value);
        return;
      }
    h[off] = (byte) 0x80;
    for (int i = 11; i >= 1; i--)
      {
        h[off + i] = (byte) (value & 0xFF);
        value >>>= 8;
      }
  }

  // --- padding ----------------------------------------------------------------------------------

  private void
  zeros (long count) throws IOException
  {
    Arrays.fill (buffer, (byte) 0);
    while (count > 0)
      {
        int n = (int) Math.min (buffer.length, count);
        out.write (buffer, 0, n);
        written += n;
        count -= n;
      }
  }

  private void
  padBlock () throws IOException
  {
    long rem = written % BLOCK;
    if (rem != 0)
      zeros (BLOCK - rem);
  }

  private void
  finish () throws IOException
  {
    zeros (2 * BLOCK);
    long rem = written % RECORD;
    if (rem != 0)
      zeros (RECORD - rem);
    out.flush ();
  }
}
