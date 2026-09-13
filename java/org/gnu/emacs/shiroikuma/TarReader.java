/* A tar reader for the home-directory restore.  -*- java -*-

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

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Restores a tar stream written by {@link TarWriter} (or by GNU tar / bsdtar)
 * under a root directory, merging: entries present are written over what is
 * there, everything else is left alone.  Understands ustar (with the prefix
 * field), GNU {@code ././@LongLink} ({@code L}/{@code K}), the PAX
 * {@code path}/{@code linkpath} records of a foreign archive, octal and
 * base-256 sizes.  Regular files are written, then {@code Os.chmod}ed and
 * {@code setLastModified}; symlinks are recreated with {@code Os.symlink}
 * after unlinking whatever is in the way; hard links become copies of the
 * already-restored target; directory modes are applied last so a read-only
 * directory in the archive cannot block its own children.
 *
 * <p>Safety: names that are absolute or contain a {@code ..} segment are
 * refused (skipped and counted), as are any the caller's filter rejects, so
 * an archive can never write outside {@code root}.
 */
public final class TarReader
{
  private static final int BLOCK = 512;

  /** Decides which entries are restored; {@code rel} is the archive name.  */
  public interface Filter
  {
    boolean accept (String rel);
  }

  /** Progress, once per regular file.  */
  public interface Listener
  {
    void onEntry (String rel, long filesDone, long bytesDone);
  }

  public static final class Stats
  {
    public long files;
    public long dirs;
    public long links;
    public long bytes;
    public long skipped;
  }

  private static final class DirMode
  {
    final File dir;
    final int mode;
    final long mtime;

    DirMode (File dir, int mode, long mtime)
    {
      this.dir = dir;
      this.mode = mode;
      this.mtime = mtime;
    }
  }

  private final InputStream in;
  private final File root;
  private final String rootPath;
  private final Filter filter;
  private final Listener listener;
  private final Stats stats = new Stats ();
  private final byte[] buffer = new byte[64 * 1024];
  private final List<DirMode> dirModes = new ArrayList<DirMode> ();

  private TarReader (InputStream in, File root, Filter filter, Listener listener)
  {
    this.in = in;
    this.root = root;
    this.rootPath = root.getAbsolutePath ();
    this.filter = filter;
    this.listener = listener;
  }

  /** Extract every acceptable entry of {@code in} under {@code root}.  */
  public static Stats
  extract (InputStream in, File root, Filter filter, Listener listener) throws IOException
  {
    TarReader r = new TarReader (in, root, filter, listener);
    r.run ();
    return r.stats;
  }

  private void
  run () throws IOException
  {
    byte[] h = new byte[BLOCK];
    String longName = null;
    String longLink = null;
    String paxPath = null;
    String paxLink = null;

    while (true)
      {
        if (!readBlock (h, true))
          break;
        if (isZero (h))
          break; // end-of-archive marker (a second one, and padding, may follow)

        String name = field (h, 0, 100);
        int mode = (int) octal (h, 100, 8);
        long size = size (h, 124);
        long mtime = octal (h, 136, 12);
        byte type = h[156];
        String link = field (h, 157, 100);
        String magic = field (h, 257, 6);
        if (magic.startsWith ("ustar"))
          {
            String prefix = field (h, 345, 155);
            if (!prefix.isEmpty ())
              name = prefix + "/" + name;
          }
        if (longName != null)
          {
            name = longName;
            longName = null;
          }
        if (longLink != null)
          {
            link = longLink;
            longLink = null;
          }
        if (paxPath != null)
          {
            name = paxPath;
            paxPath = null;
          }
        if (paxLink != null)
          {
            link = paxLink;
            paxLink = null;
          }

        switch (type)
          {
          case 'L':
            longName = stringData (size);
            continue;
          case 'K':
            longLink = stringData (size);
            continue;
          case 'x':
            {
              String[] pax = paxRecords (stringData (size));
              if (pax[0] != null)
                paxPath = pax[0];
              if (pax[1] != null)
                paxLink = pax[1];
              continue;
            }
          case 'g':
            skip (size);
            continue;
          default:
            break;
          }

        String rel = normalise (name);
        if (rel == null || (filter != null && !filter.accept (rel)))
          {
            skip (size);
            stats.skipped++;
            continue;
          }
        File target = new File (root, rel);

        switch (type)
          {
          case '5':
            skip (size);
            if (!target.isDirectory ())
              {
                if (target.exists ())
                  target.delete ();
                target.mkdirs ();
              }
            dirModes.add (new DirMode (target, mode, mtime));
            stats.dirs++;
            break;

          case '0':
          case 0:
          case '7':
            writeFile (target, size, mode, mtime);
            stats.files++;
            stats.bytes += size;
            if (listener != null)
              listener.onEntry (rel, stats.files, stats.bytes);
            break;

          case '2':
            skip (size);
            symlink (target, link);
            stats.links++;
            break;

          case '1':
            {
              skip (size);
              String linkRel = normalise (link);
              File source = linkRel == null ? null : new File (root, linkRel);
              if (source != null && source.isFile ())
                {
                  copy (source, target);
                  chmod (target, mode);
                  target.setLastModified (mtime * 1000L);
                  stats.files++;
                }
              else
                stats.skipped++;
              break;
            }

          default:
            // FIFOs, devices, unknown: "socket ignored" on the way back too
            skip (size);
            stats.skipped++;
            break;
          }
      }

    // Directory modes last, deepest first, so a read-only directory never blocks its children.
    for (int i = dirModes.size () - 1; i >= 0; i--)
      {
        DirMode d = dirModes.get (i);
        chmod (d.dir, d.mode);
        d.dir.setLastModified (d.mtime * 1000L);
      }
  }

  // --- entries ----------------------------------------------------------------------------------

  private void
  writeFile (File target, long size, int mode, long mtime) throws IOException
  {
    File parent = target.getParentFile ();
    if (parent != null && !parent.isDirectory ())
      parent.mkdirs ();
    if (target.exists () && !target.isFile ())
      deleteRecursively (target);
    // A symlink in the way must go, or we would write through it.
    try
      {
        if (isSymlink (target))
          target.delete ();
      }
    catch (Exception e)
      {
        // pass
      }
    OutputStream out = new FileOutputStream (target);
    try
      {
        long remaining = size;
        while (remaining > 0)
          {
            int n = in.read (buffer, 0, (int) Math.min (buffer.length, remaining));
            if (n < 0)
              throw new EOFException ("archive truncated inside " + target.getName ());
            out.write (buffer, 0, n);
            remaining -= n;
          }
      }
    finally
      {
        try
          {
            out.close ();
          }
        catch (IOException e)
          {
            // pass
          }
      }
    skipPad (size);
    chmod (target, mode);
    target.setLastModified (mtime * 1000L);
  }

  private void
  symlink (File target, String link)
  {
    if (link == null || link.isEmpty ())
      {
        stats.skipped++;
        return;
      }
    File parent = target.getParentFile ();
    if (parent != null && !parent.isDirectory ())
      parent.mkdirs ();
    if (target.exists () || isSymlink (target))
      deleteRecursively (target);
    try
      {
        Os.symlink (link, target.getPath ());
      }
    catch (ErrnoException e)
      {
        stats.skipped++;
      }
  }

  private static boolean
  isSymlink (File f)
  {
    try
      {
        return android.system.OsConstants.S_ISLNK (Os.lstat (f.getPath ()).st_mode);
      }
    catch (ErrnoException e)
      {
        return false;
      }
  }

  private static void
  deleteRecursively (File f)
  {
    if (!isSymlink (f) && f.isDirectory ())
      {
        File[] children = f.listFiles ();
        if (children != null)
          for (File c : children)
            deleteRecursively (c);
      }
    f.delete ();
  }

  private static void
  chmod (File f, int mode)
  {
    try
      {
        Os.chmod (f.getPath (), mode & 07777);
      }
    catch (ErrnoException e)
      {
        // pass — a mode we cannot set is not worth failing a restore over
      }
  }

  private void
  copy (File from, File to) throws IOException
  {
    File parent = to.getParentFile ();
    if (parent != null && !parent.isDirectory ())
      parent.mkdirs ();
    java.io.FileInputStream fin = new java.io.FileInputStream (from);
    try
      {
        OutputStream out = new FileOutputStream (to);
        try
          {
            int n;
            while ((n = fin.read (buffer)) > 0)
              out.write (buffer, 0, n);
          }
        finally
          {
            out.close ();
          }
      }
    finally
      {
        fin.close ();
      }
  }

  /**
   * The archive name as a path relative to root, or null when it is absolute,
   * escapes via {@code ..}, or is empty.  A trailing slash is dropped.
   */
  private String
  normalise (String name)
  {
    if (name == null)
      return null;
    String n = name;
    while (n.startsWith ("./"))
      n = n.substring (2);
    while (n.endsWith ("/"))
      n = n.substring (0, n.length () - 1);
    if (n.isEmpty () || n.startsWith ("/") || n.equals ("."))
      return null;
    for (String seg : n.split ("/"))
      {
        if (seg.equals (".."))
          return null;
      }
    // Belt and braces: the resolved path must stay under root.
    String abs = new File (root, n).getAbsolutePath ();
    if (!abs.equals (rootPath) && !abs.startsWith (rootPath + "/"))
      return null;
    return n;
  }

  // --- header fields ----------------------------------------------------------------------------

  /** NUL-terminated UTF-8 string field.  */
  private static String
  field (byte[] h, int off, int len)
  {
    int end = off;
    while (end < off + len && h[end] != 0)
      end++;
    return new String (h, off, end - off, StandardCharsets.UTF_8);
  }

  /** Octal numeric field (digits, may be space/NUL terminated or padded).  */
  private static long
  octal (byte[] h, int off, int len)
  {
    long v = 0;
    boolean started = false;
    for (int i = off; i < off + len; i++)
      {
        int c = h[i] & 0xFF;
        if (c == 0 || c == ' ')
          {
            if (started)
              break;
            continue;
          }
        if (c < '0' || c > '7')
          break;
        started = true;
        v = (v << 3) | (c - '0');
      }
    return v;
  }

  /** The 12-byte size field: base-256 when the high bit of the first byte is set.  */
  private static long
  size (byte[] h, int off)
  {
    if ((h[off] & 0x80) != 0)
      {
        long v = 0;
        for (int i = 1; i < 12; i++)
          v = (v << 8) | (h[off + i] & 0xFF);
        return v;
      }
    return octal (h, off, 12);
  }

  /** {@code path} and {@code linkpath} of a PAX extended header, if present.  */
  private static String[]
  paxRecords (String data)
  {
    String[] out = new String[2];
    int i = 0;
    while (i < data.length ())
      {
        int sp = data.indexOf (' ', i);
        int nl = data.indexOf ('\n', i);
        if (sp < 0 || nl < 0 || nl < sp)
          break;
        String kv = data.substring (sp + 1, nl);
        int eq = kv.indexOf ('=');
        if (eq > 0)
          {
            String key = kv.substring (0, eq);
            String value = kv.substring (eq + 1);
            if (key.equals ("path"))
              out[0] = value;
            else if (key.equals ("linkpath"))
              out[1] = value;
          }
        i = nl + 1;
      }
    return out;
  }

  // --- stream primitives ------------------------------------------------------------------------

  /** Read one block; at a clean EOF returns false (when {@code eofOk}) instead of throwing.  */
  private boolean
  readBlock (byte[] h, boolean eofOk) throws IOException
  {
    int got = 0;
    while (got < BLOCK)
      {
        int n = in.read (h, got, BLOCK - got);
        if (n < 0)
          {
            if (got == 0 && eofOk)
              return false;
            throw new EOFException ("archive truncated in a header");
          }
        got += n;
      }
    return true;
  }

  private static boolean
  isZero (byte[] h)
  {
    for (byte b : h)
      if (b != 0)
        return false;
    return true;
  }

  private String
  stringData (long size) throws IOException
  {
    if (size > 1024 * 1024)
      throw new IOException ("unreasonable long-name record: " + size);
    byte[] data = new byte[(int) size];
    int got = 0;
    while (got < data.length)
      {
        int n = in.read (data, got, data.length - got);
        if (n < 0)
          throw new EOFException ("archive truncated in a long-name record");
        got += n;
      }
    skipPad (size);
    int end = data.length;
    while (end > 0 && data[end - 1] == 0)
      end--;
    return new String (data, 0, end, StandardCharsets.UTF_8);
  }

  private void
  skip (long size) throws IOException
  {
    long remaining = size;
    while (remaining > 0)
      {
        long n = in.skip (remaining);
        if (n <= 0)
          {
            int b = in.read ();
            if (b < 0)
              throw new EOFException ("archive truncated in entry data");
            n = 1;
          }
        remaining -= n;
      }
    skipPad (size);
  }

  private void
  skipPad (long size) throws IOException
  {
    long rem = size % BLOCK;
    if (rem == 0)
      return;
    long pad = BLOCK - rem;
    while (pad > 0)
      {
        long n = in.skip (pad);
        if (n <= 0)
          {
            int b = in.read ();
            if (b < 0)
              return; // padding at the very end may be missing in a lax archive
            n = 1;
          }
        pad -= n;
      }
  }
}
