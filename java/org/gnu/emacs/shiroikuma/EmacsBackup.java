/* The category-ZIP export/import engine.  -*- java -*-

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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.gnu.emacs.EmacsService;
import org.gnu.emacs.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The category-ZIP export/import engine of 白い熊 GNU Emacs — the 白い熊
 * family's shared backup shape, extended to a tar of the whole home
 * directory.  Every caller — the Export / Import panel and the headless
 * automation side — goes through {@link #export} / {@link #importZip}; the
 * logic lives here once.
 *
 * <p><b>One ZIP per export</b>, {@code shiroikuma-emacs_<yyyy-MM-dd_HH-mm-ss>.zip}:
 * <ul>
 * <li>{@code manifest.json} FIRST — {@code format}, {@code version}, {@code app},
 * {@code appVersion}, {@code createdTs}, {@code categories[]}, plus
 * {@code counts} (files/bytes per tar category, so a restore can show honest
 * progress without a second pass);</li>
 * <li>{@code settings.json} — the type-tagged dump of the {@code shiroikuma_ui}
 * SharedPreferences ({@code {"<file>":{"<key>":{"t":"int|long|float|bool|string|set","v":…}}}});
 * never the automation prefs, never the export-directory prefs;</li>
 * <li>{@code data.home.tar} — {@code tar -C <dataDir> -cf - files} ({@code files/}
 * IS HOME, {@code /data/data/shiroikuma.emacs/files}), DEFLATED at
 * {@code Deflater.BEST_SPEED}, minus {@code files/emacs-*.pdmp} (the dump is
 * bound to one build), sockets skipped.  Still restorable by hand:
 * {@code unzip -p x.zip data.home.tar | tar -C /data/data/shiroikuma.emacs -x}.</li>
 * </ul>
 *
 * <p>Import merges: the settings per key ({@code commit()}), the tar over the
 * live tree (files present are written, others left alone), only the
 * categories present in the archive AND asked for.  A home restore must not
 * run under a live Emacs — {@link #emacsRunning()} is the caller's test; the
 * panel bounces the process (see {@link ShiroikumaUiActivity}), the
 * automation door refuses.
 */
public final class EmacsBackup
{
  public static final String FORMAT = "shiroikuma-emacs-export";
  public static final int VERSION = 1;

  /** The family naming convention: {@code <repo basename>_<stamp>.zip}.  */
  public static final String EXPORT_PREFIX = "shiroikuma-emacs_";

  public static final String MANIFEST_ENTRY = "manifest.json";
  public static final String SETTINGS_ENTRY = "settings.json";
  public static final String HOME_ENTRY = "data.home.tar";

  /** The tar's one top-level entry: {@code <dataDir>/files} = HOME.  */
  public static final String HOME_SUBDIR = "files";

  private EmacsBackup ()
  {
  }

  /**
   * A selectable part of the backup.  {@code id} is the category id of the
   * automation contract; {@code defaultSelected} is what a freshly opened
   * picker ticks and what {@code LIST_CATEGORIES} reports as {@code on}.
   */
  public enum Cat
  {
    SETTINGS ("settings", R.string.shiroikuma_eim_cat_settings, null, true),
    DATA_HOME ("data.home", R.string.shiroikuma_eim_cat_data_home, null, true);

    public final String id;
    public final int labelRes;
    /** The parent category's id for a sub-option, or null for a top-level category.  */
    public final String parentId;
    public final boolean defaultSelected;

    Cat (String id, int labelRes, String parentId, boolean defaultSelected)
    {
      this.id = id;
      this.labelRes = labelRes;
      this.parentId = parentId;
      this.defaultSelected = defaultSelected;
    }

    public static Cat
    byId (String id)
    {
      for (Cat c : values ())
        if (c.id.equals (id))
          return c;
      return null;
    }

    public static Set<Cat>
    all ()
    {
      Set<Cat> set = new LinkedHashSet<Cat> ();
      for (Cat c : values ())
        set.add (c);
      return set;
    }

    /** The set a picker starts on — also what an EXPORT_STATE with no {@code items} means.  */
    public static Set<Cat>
    defaults ()
    {
      Set<Cat> set = new LinkedHashSet<Cat> ();
      for (Cat c : values ())
        if (c.defaultSelected)
          set.add (c);
      return set;
    }

    /** Parse a comma-separated {@code items} list; unknown ids are returned in {@code unknown}.  */
    public static Set<Cat>
    parse (String items, List<String> unknown)
    {
      Set<Cat> set = new LinkedHashSet<Cat> ();
      if (items == null || items.trim ().isEmpty ())
        return defaults ();
      for (String raw : items.split (","))
        {
          String id = raw.trim ();
          if (id.isEmpty ())
            continue;
          Cat c = byId (id);
          if (c != null)
            set.add (c);
          else if (unknown != null)
            unknown.add (id);
        }
      return set;
    }
  }

  /**
   * Real counts, never a percentage: {@code item} is the category label,
   * {@code current}/{@code total} are files of a tar category (or the category
   * position for settings), {@code bytes}/{@code bytesTotal} their content.
   * Called from the worker thread, possibly many times a second — throttle
   * on the receiving side.
   */
  public interface Progress
  {
    void onProgress (String item, long current, long total, long bytes, long bytesTotal);
  }

  /** Polled between entries so a running export can be stopped from outside.  */
  public interface Cancel
  {
    boolean isCancelled ();
  }

  /** Thrown out of {@link #export} when the caller's {@link Cancel} goes up.  */
  public static class CancelledException extends IOException
  {
    private static final long serialVersionUID = 1L;

    public CancelledException ()
    {
      super ("cancelled");
    }
  }

  /** What an export or import did.  */
  public static final class Result
  {
    public int categories;
    public long files;
    public long bytes;
    public long skipped;
    /** One line per category, for the result dialog / the automation reply.  */
    public String summary = "";
  }

  // ---------------------------------------------------------------------------------------------
  // naming, sizes, state
  // ---------------------------------------------------------------------------------------------

  /** The name of the ZIP to write now — identical for the panel and the automation side.  */
  public static String
  exportFileName ()
  {
    return EXPORT_PREFIX
      + new SimpleDateFormat ("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format (new Date ())
      + ".zip";
  }

  /** True if this is one of our backups (the shared directory also holds sister apps').  */
  public static boolean
  isExportFileName (String name)
  {
    return name != null && name.endsWith (".zip") && name.startsWith (EXPORT_PREFIX);
  }

  public static String
  humanSize (long bytes)
  {
    if (bytes >= (1L << 30))
      return String.format (Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
    if (bytes >= (1L << 20))
      return String.format (Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
    if (bytes >= (1L << 10))
      return String.format (Locale.ROOT, "%.1f KB", bytes / (double) (1L << 10));
    return bytes + " B";
  }

  /**
   * Whether Emacs has been started in this process.  {@code EmacsService} is
   * only ever started from {@code EmacsActivity.onCreate}, so in a process
   * launched for the UI page alone this is false and a home restore is safe.
   */
  public static boolean
  emacsRunning ()
  {
    return EmacsService.SERVICE != null;
  }

  /** The tar root: {@code /data/user/0/shiroikuma.emacs}; HOME is its {@code files}.  */
  public static File
  homeRoot (Context context)
  {
    return context.getDataDir ();
  }

  /** The dump file is bound to one build: never carried, never restored.  */
  public static TarWriter.Filter
  homeFilter ()
  {
    return new TarWriter.Filter () {
        @Override
        public boolean
        accept (String rel, boolean isDir)
        {
          return !isDumpFile (rel);
        }
      };
  }

  static boolean
  isDumpFile (String rel)
  {
    return rel.startsWith (HOME_SUBDIR + "/emacs-") && rel.endsWith (".pdmp")
      && rel.indexOf ('/', HOME_SUBDIR.length () + 1) < 0;
  }

  // ---------------------------------------------------------------------------------------------
  // EXPORT
  // ---------------------------------------------------------------------------------------------

  /**
   * Write a ZIP of the selected categories to {@code out} (which the caller
   * closes).  {@code progress} and {@code cancel} may be null.
   */
  public static Result
  export (Context context, Set<Cat> cats, OutputStream out, final Progress progress,
          final Cancel cancel) throws IOException
  {
    List<Cat> ordered = new ArrayList<Cat> ();
    for (Cat c : Cat.values ())
      if (cats.contains (c))
        ordered.add (c);

    Result result = new Result ();
    result.categories = ordered.size ();

    File root = homeRoot (context);
    TarWriter.Filter filter = homeFilter ();
    TarWriter.Stats plan = null;
    if (ordered.contains (Cat.DATA_HOME))
      plan = TarWriter.scan (root, HOME_SUBDIR, filter);

    ZipOutputStream zip = new ZipOutputStream (out);
    zip.setLevel (Deflater.BEST_SPEED);

    // manifest.json first
    JSONObject manifest = new JSONObject ();
    try
      {
        JSONArray ids = new JSONArray ();
        for (Cat c : ordered)
          ids.put (c.id);
        manifest.put ("format", FORMAT);
        manifest.put ("version", VERSION);
        manifest.put ("app", context.getPackageName ());
        manifest.put ("appVersion", appVersion (context));
        manifest.put ("createdTs", System.currentTimeMillis ());
        manifest.put ("categories", ids);
        if (plan != null)
          {
            JSONObject counts = new JSONObject ();
            JSONObject home = new JSONObject ();
            home.put ("files", plan.files);
            home.put ("bytes", plan.bytes);
            home.put ("dirs", plan.dirs);
            home.put ("links", plan.links);
            home.put ("skipped", plan.skipped);
            counts.put (Cat.DATA_HOME.id, home);
            manifest.put ("counts", counts);
          }
      }
    catch (JSONException e)
      {
        // pass — a manifest field is never worth failing an export over
      }
    writeEntry (zip, MANIFEST_ENTRY, manifest.toString ().getBytes (StandardCharsets.UTF_8));

    StringBuilder summary = new StringBuilder ();
    int position = 0;
    for (Cat cat : ordered)
      {
        throwIfCancelled (cancel);
        position++;
        final String label = context.getString (cat.labelRes);
        switch (cat)
          {
          case SETTINGS:
            {
              byte[] json = dumpSettings (context).getBytes (StandardCharsets.UTF_8);
              writeEntry (zip, SETTINGS_ENTRY, json);
              int n = ShiroikumaPrefs.ui (context).getAll ().size ();
              result.files += n;
              appendLine (summary, label + ": " + n);
              if (progress != null)
                progress.onProgress (label, position, ordered.size (), json.length, json.length);
              break;
            }

          case DATA_HOME:
            {
              final long totalFiles = plan.files;
              final long totalBytes = plan.bytes;
              zip.putNextEntry (new ZipEntry (HOME_ENTRY));
              TarWriter.Stats stats = TarWriter
                .write (root, HOME_SUBDIR, filter, zip, new TarWriter.Listener () {
                    @Override
                    public void
                    onEntry (String rel, long filesDone, long bytesDone)
                    {
                      if (progress != null)
                        progress.onProgress (label, filesDone, totalFiles, bytesDone, totalBytes);
                    }

                    @Override
                    public boolean
                    isCancelled ()
                    {
                      return cancel != null && cancel.isCancelled ();
                    }
                  });
              zip.closeEntry ();
              result.files += stats.files;
              result.bytes += stats.bytes;
              result.skipped += stats.skipped;
              appendLine (summary, context.getString (R.string.shiroikuma_eim_summary_files, label,
                                                      stats.files, humanSize (stats.bytes)));
              if (stats.skipped > 0)
                appendLine (summary, context.getString (R.string.shiroikuma_eim_summary_skipped,
                                                        stats.skipped));
              if (progress != null)
                progress.onProgress (label, stats.files, Math.max (stats.files, totalFiles),
                                     stats.bytes, Math.max (stats.bytes, totalBytes));
              break;
            }
          }
      }
    throwIfCancelled (cancel);
    zip.finish ();
    zip.flush ();
    // Do not close `zip': that would close the caller's stream twice.
    result.summary = summary.toString ();
    return result;
  }

  private static void
  throwIfCancelled (Cancel cancel) throws CancelledException
  {
    if (cancel != null && cancel.isCancelled ())
      throw new CancelledException ();
  }

  private static void
  writeEntry (ZipOutputStream zip, String name, byte[] content) throws IOException
  {
    zip.putNextEntry (new ZipEntry (name));
    zip.write (content);
    zip.closeEntry ();
  }

  private static void
  appendLine (StringBuilder sb, String line)
  {
    if (sb.length () > 0)
      sb.append ('\n');
    sb.append (line);
  }

  private static String
  appVersion (Context context)
  {
    try
      {
        String v = context.getPackageManager ()
          .getPackageInfo (context.getPackageName (), 0).versionName;
        return v == null ? "" : v;
      }
    catch (PackageManager.NameNotFoundException e)
      {
        return "";
      }
  }

  // ---------------------------------------------------------------------------------------------
  // IMPORT
  // ---------------------------------------------------------------------------------------------

  /** The archive's manifest, or null when it is not one of ours / unreadable.  */
  public static JSONObject
  manifestOf (File file)
  {
    ZipInputStream zis;
    try
      {
        zis = new ZipInputStream (new BufferedInputStream (new FileInputStream (file)));
      }
    catch (IOException e)
      {
        return null;
      }
    try
      {
        ZipEntry ze;
        while ((ze = zis.getNextEntry ()) != null)
          {
            if (!MANIFEST_ENTRY.equals (ze.getName ()))
              continue;
            JSONObject m = new JSONObject (new String (readAll (zis), StandardCharsets.UTF_8));
            return FORMAT.equals (m.optString ("format")) ? m : null;
          }
      }
    catch (IOException e)
      {
        // not one of our archives
      }
    catch (JSONException e)
      {
        // not one of our archives
      }
    finally
      {
        try
          {
            zis.close ();
          }
        catch (IOException e)
          {
            // pass
          }
      }
    return null;
  }

  /** The category ids the ZIP's manifest declares (empty when it is not one of ours).  */
  public static List<String>
  categoriesIn (File file)
  {
    List<String> out = new ArrayList<String> ();
    JSONObject m = manifestOf (file);
    if (m == null)
      return out;
    JSONArray ids = m.optJSONArray ("categories");
    if (ids != null)
      for (int i = 0; i < ids.length (); i++)
        out.add (ids.optString (i));
    return out;
  }

  /**
   * Restore the selected categories the spooled ZIP contains — merged, never
   * wiped.  The caller has checked {@link #emacsRunning()} when
   * {@code DATA_HOME} is selected.  {@code progress} may be null.
   */
  public static Result
  importZip (Context context, File spool, Set<Cat> cats, final Progress progress)
    throws IOException
  {
    JSONObject manifest = manifestOf (spool);
    if (manifest == null)
      throw new IOException (context.getString (R.string.shiroikuma_eim_import_none));
    List<String> present = new ArrayList<String> ();
    JSONArray ids = manifest.optJSONArray ("categories");
    if (ids != null)
      for (int i = 0; i < ids.length (); i++)
        present.add (ids.optString (i));

    List<Cat> restoring = new ArrayList<Cat> ();
    for (Cat c : Cat.values ())
      if (cats.contains (c) && present.contains (c.id))
        restoring.add (c);

    Result result = new Result ();
    result.categories = restoring.size ();
    if (restoring.isEmpty ())
      {
        result.summary = context.getString (R.string.shiroikuma_eim_import_nothing);
        return result;
      }

    long homeFiles = 0;
    long homeBytes = 0;
    JSONObject counts = manifest.optJSONObject ("counts");
    if (counts != null)
      {
        JSONObject home = counts.optJSONObject (Cat.DATA_HOME.id);
        if (home != null)
          {
            homeFiles = home.optLong ("files", 0);
            homeBytes = home.optLong ("bytes", 0);
          }
      }

    StringBuilder summary = new StringBuilder ();
    File root = homeRoot (context);
    ZipInputStream zis = new ZipInputStream (new BufferedInputStream (new FileInputStream (spool)));
    try
      {
        ZipEntry ze;
        while ((ze = zis.getNextEntry ()) != null)
          {
            String name = ze.getName ();
            if (ze.isDirectory ())
              continue;
            if (SETTINGS_ENTRY.equals (name) && restoring.contains (Cat.SETTINGS))
              {
                String label = context.getString (Cat.SETTINGS.labelRes);
                int n = mergeSettings (context, new String (readAll (zis), StandardCharsets.UTF_8));
                result.files += n;
                appendLine (summary, label + ": " + n);
                if (progress != null)
                  progress.onProgress (label, 1, 1, 0, 0);
              }
            else if (HOME_ENTRY.equals (name) && restoring.contains (Cat.DATA_HOME))
              {
                final String label = context.getString (Cat.DATA_HOME.labelRes);
                final long totalFiles = homeFiles;
                final long totalBytes = homeBytes;
                TarReader.Stats stats = TarReader
                  .extract (zis, root, new TarReader.Filter () {
                      @Override
                      public boolean
                      accept (String rel)
                      {
                        // only HOME, never a stale dump from another build
                        return (rel.equals (HOME_SUBDIR) || rel.startsWith (HOME_SUBDIR + "/"))
                          && !isDumpFile (rel);
                      }
                    }, new TarReader.Listener () {
                      @Override
                      public void
                      onEntry (String rel, long filesDone, long bytesDone)
                      {
                        if (progress != null)
                          progress.onProgress (label, filesDone, Math.max (filesDone, totalFiles),
                                               bytesDone, Math.max (bytesDone, totalBytes));
                      }
                    });
                result.files += stats.files;
                result.bytes += stats.bytes;
                result.skipped += stats.skipped;
                appendLine (summary, context.getString (R.string.shiroikuma_eim_summary_files, label,
                                                        stats.files, humanSize (stats.bytes)));
                if (progress != null)
                  progress.onProgress (label, stats.files, stats.files, stats.bytes, stats.bytes);
              }
          }
      }
    finally
      {
        try
          {
            zis.close ();
          }
        catch (IOException e)
          {
            // pass
          }
      }
    if (summary.length () == 0)
      summary.append (context.getString (R.string.shiroikuma_eim_import_nothing));
    result.summary = summary.toString ();
    return result;
  }

  private static byte[]
  readAll (InputStream in) throws IOException
  {
    ByteArrayOutputStream bos = new ByteArrayOutputStream ();
    byte[] buffer = new byte[16 * 1024];
    int n;
    while ((n = in.read (buffer)) > 0)
      bos.write (buffer, 0, n);
    return bos.toByteArray ();
  }

  // ---------------------------------------------------------------------------------------------
  // settings.json (type-tagged, like every sister app)
  // ---------------------------------------------------------------------------------------------

  /** Only files that may travel: the UI prefs.  Automation and export-dir prefs never.  */
  private static final String[] EXPORTED_PREFS = { ShiroikumaPrefs.UI_PREFS };

  static String
  dumpSettings (Context context)
  {
    JSONObject obj = new JSONObject ();
    try
      {
        for (String file : EXPORTED_PREFS)
          obj.put (file, dumpPrefs (context.getApplicationContext ()
                                    .getSharedPreferences (file, Context.MODE_PRIVATE)));
      }
    catch (JSONException e)
      {
        // pass
      }
    return obj.toString ();
  }

  /** Merge settings.json back in; only the exportable files are honoured.  Returns keys written.  */
  static int
  mergeSettings (Context context, String json)
  {
    int n = 0;
    try
      {
        JSONObject obj = new JSONObject (json);
        for (String file : EXPORTED_PREFS)
          {
            JSONObject dump = obj.optJSONObject (file);
            if (dump != null)
              n += mergePrefs (context.getApplicationContext ()
                               .getSharedPreferences (file, Context.MODE_PRIVATE), dump);
          }
      }
    catch (JSONException e)
      {
        // a malformed settings entry must not cost the rest of the restore
      }
    return n;
  }

  private static JSONObject
  dumpPrefs (SharedPreferences sp) throws JSONException
  {
    JSONObject obj = new JSONObject ();
    for (Map.Entry<String, ?> e : sp.getAll ().entrySet ())
      {
        Object v = e.getValue ();
        JSONObject entry = new JSONObject ();
        if (v instanceof Boolean)
          entry.put ("t", "bool").put ("v", v);
        else if (v instanceof Integer)
          entry.put ("t", "int").put ("v", v);
        else if (v instanceof Long)
          entry.put ("t", "long").put ("v", v);
        else if (v instanceof Float)
          entry.put ("t", "float").put ("v", ((Float) v).doubleValue ());
        else if (v instanceof String)
          entry.put ("t", "string").put ("v", v);
        else if (v instanceof Set)
          {
            JSONArray arr = new JSONArray ();
            for (Object o : (Set<?>) v)
              arr.put (String.valueOf (o));
            entry.put ("t", "set").put ("v", arr);
          }
        else
          continue;
        obj.put (e.getKey (), entry);
      }
    return obj;
  }

  /** Merge a dump back in, per key (never a wipe), {@code commit()}ed.  Accepts the short tags too.  */
  private static int
  mergePrefs (SharedPreferences sp, JSONObject obj)
  {
    int n = 0;
    SharedPreferences.Editor editor = sp.edit ();
    for (Iterator<String> it = obj.keys (); it.hasNext ();)
      {
        String key = it.next ();
        JSONObject entry = obj.optJSONObject (key);
        if (entry == null)
          continue;
        String type = entry.optString ("t");
        if (type.equals ("bool") || type.equals ("b"))
          editor.putBoolean (key, entry.optBoolean ("v"));
        else if (type.equals ("int") || type.equals ("i"))
          editor.putInt (key, entry.optInt ("v"));
        else if (type.equals ("long") || type.equals ("l"))
          editor.putLong (key, entry.optLong ("v"));
        else if (type.equals ("float") || type.equals ("f"))
          editor.putFloat (key, (float) entry.optDouble ("v"));
        else if (type.equals ("string") || type.equals ("s"))
          editor.putString (key, entry.optString ("v"));
        else if (type.equals ("set"))
          {
            JSONArray arr = entry.optJSONArray ("v");
            Set<String> set = new HashSet<String> ();
            if (arr != null)
              for (int i = 0; i < arr.length (); i++)
                set.add (arr.optString (i));
            editor.putStringSet (key, set);
          }
        else
          continue;
        n++;
      }
    editor.commit ();
    return n;
  }
}
