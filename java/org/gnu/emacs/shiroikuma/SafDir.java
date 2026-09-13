/* The export directory: a SAF tree over DocumentsContract.  -*- java -*-

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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The export directory — a folder picked through the Storage Access
 * Framework — and everything the page, the panel and the automation side
 * do with it, over plain {@link DocumentsContract} (no AndroidX
 * {@code DocumentFile} in this build).
 *
 * <p>A picked tree is a Uri string — plain app data, ours to keep — <b>plus
 * a persisted grant</b>, which the platform issues to one install on one
 * device and drops when that install goes away.  So the stored Uri and the
 * grant come apart: {@link #dirUri} answers what was chosen (so the picker
 * can open right there), {@link #usableDir} whether it works now.
 */
public final class SafDir
{
  private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

  private SafDir ()
  {
  }

  /** A child document of the tree, as the panel and the "Last export" row need it.  */
  public static final class Entry
  {
    public final Uri uri;
    public final String name;
    public final long lastModified;
    public final long size;

    Entry (Uri uri, String name, long lastModified, long size)
    {
      this.uri = uri;
      this.name = name;
      this.lastModified = lastModified;
      this.size = size;
    }
  }

  // --- the stored directory ---------------------------------------------------------------------

  /** The tree Uri last chosen, grant or no grant; null when none was ever chosen.  */
  public static Uri
  dirUri (Context context)
  {
    String stored = ShiroikumaPrefs.eximport (context).getString (ShiroikumaPrefs.KEY_DIR_URI, null);
    if (stored == null || stored.isEmpty ())
      return null;
    try
      {
        return Uri.parse (stored);
      }
    catch (Exception e)
      {
        return null;
      }
  }

  public static void
  setDirUri (Context context, Uri uri)
  {
    ShiroikumaPrefs.eximport (context).edit ()
      .putString (ShiroikumaPrefs.KEY_DIR_URI, uri == null ? null : uri.toString ())
      .commit ();
  }

  /**
   * Persist a folder the picker just returned: take the persistable
   * read+write grant so it survives reboots, then remember the Uri.
   */
  public static void
  storeDir (Context context, Uri uri)
  {
    if (uri == null)
      return;
    reclaim (context, uri);
    setDirUri (context, uri);
  }

  /** True when this install currently holds a persisted read+write grant for {@code uri}.  */
  public static boolean
  hasGrant (Context context, Uri uri)
  {
    if (uri == null)
      return false;
    for (UriPermission p : context.getContentResolver ().getPersistedUriPermissions ())
      {
        if (p.getUri ().equals (uri) && p.isReadPermission () && p.isWritePermission ())
          return true;
      }
    return false;
  }

  /**
   * Hold on to a tree this install may access.  Returns whether a persisted
   * grant is now held; a Uri from another install answers false, which is
   * the cue to keep it as a remembered folder, never to drop it.
   */
  public static boolean
  reclaim (Context context, Uri uri)
  {
    if (uri == null)
      return false;
    if (hasGrant (context, uri))
      return true;
    try
      {
        context.getContentResolver ()
          .takePersistableUriPermission (uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                         | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return true;
      }
    catch (Exception e)
      {
        return false;
      }
  }

  /** The configured tree Uri when it is granted AND still a directory; else null.  */
  public static Uri
  usableDir (Context context)
  {
    Uri uri = dirUri (context);
    if (uri == null || !hasGrant (context, uri))
      return null;
    Cursor c = null;
    try
      {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree (uri,
                                                                DocumentsContract.getTreeDocumentId (uri));
        c = context.getContentResolver ().query (doc, new String[] { Document.COLUMN_MIME_TYPE },
                                                 null, null, null);
        if (c != null && c.moveToFirst () && Document.MIME_TYPE_DIR.equals (c.getString (0)))
          return uri;
        return null;
      }
    catch (Exception e)
      {
        return null;
      }
    finally
      {
        if (c != null)
          c.close ();
      }
  }

  // --- naming -----------------------------------------------------------------------------------

  /**
   * Best-effort real filesystem path of a tree (primary storage only; an
   * sd-card volume has no stable mount path and answers null).  Needs no
   * grant — that is how a merely remembered folder can still be named.
   */
  public static String
  absolutePathOf (Uri treeUri, String fileName)
  {
    if (treeUri == null || !EXTERNAL_STORAGE_AUTHORITY.equals (treeUri.getAuthority ()))
      return null;
    String docId;
    try
      {
        docId = DocumentsContract.getTreeDocumentId (treeUri);
      }
    catch (Exception e)
      {
        return null;
      }
    if (docId == null || !docId.startsWith ("primary:"))
      return null;
    String rel = docId.substring ("primary:".length ());
    while (rel.startsWith ("/"))
      rel = rel.substring (1);
    while (rel.endsWith ("/"))
      rel = rel.substring (0, rel.length () - 1);
    String base = Environment.getExternalStorageDirectory ().getAbsolutePath ();
    String path = rel.isEmpty () ? base : base + "/" + rel;
    return fileName == null ? path : path + "/" + fileName;
  }

  /** The usable directory as a path (or its last segment), or null when unset/ungranted.  */
  public static String
  dirLabel (Context context)
  {
    Uri uri = usableDir (context);
    if (uri == null)
      return null;
    String abs = absolutePathOf (uri, null);
    return abs != null ? abs : lastSegment (uri);
  }

  /** A remembered-but-ungranted folder's name (restored from a backup, or reinstalled), or null.  */
  public static String
  rememberedDirLabel (Context context)
  {
    if (usableDir (context) != null)
      return null;
    Uri uri = dirUri (context);
    if (uri == null)
      return null;
    String abs = absolutePathOf (uri, null);
    return abs != null ? abs : lastSegment (uri);
  }

  private static String
  lastSegment (Uri uri)
  {
    String last = uri.getLastPathSegment ();
    if (last == null)
      return uri.toString ();
    int colon = last.lastIndexOf (':');
    return colon >= 0 && colon + 1 < last.length () ? last.substring (colon + 1) : last;
  }

  // --- children ---------------------------------------------------------------------------------

  /** Our backups in the usable directory, newest first (empty when unset).  */
  public static List<Entry>
  listExports (Context context)
  {
    List<Entry> out = new ArrayList<Entry> ();
    Uri tree = usableDir (context);
    if (tree == null)
      return out;
    Cursor c = null;
    try
      {
        Uri children = DocumentsContract
          .buildChildDocumentsUriUsingTree (tree, DocumentsContract.getTreeDocumentId (tree));
        c = context.getContentResolver ()
          .query (children, new String[] { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                                           Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED,
                                           Document.COLUMN_SIZE },
                  null, null, null);
        if (c == null)
          return out;
        while (c.moveToNext ())
          {
            String name = c.getString (1);
            String mime = c.getString (2);
            if (Document.MIME_TYPE_DIR.equals (mime) || !EmacsBackup.isExportFileName (name))
              continue;
            Uri uri = DocumentsContract.buildDocumentUriUsingTree (tree, c.getString (0));
            out.add (new Entry (uri, name, c.isNull (3) ? 0 : c.getLong (3),
                                c.isNull (4) ? 0 : c.getLong (4)));
          }
      }
    catch (Exception e)
      {
        return out;
      }
    finally
      {
        if (c != null)
          c.close ();
      }
    Collections.sort (out, new Comparator<Entry> () {
        @Override
        public int
        compare (Entry a, Entry b)
        {
          int byTime = Long.valueOf (b.lastModified).compareTo (Long.valueOf (a.lastModified));
          return byTime != 0 ? byTime : b.name.compareTo (a.name);
        }
      });
    return out;
  }

  /** Our newest backup in the usable directory, or null.  */
  public static Entry
  newestExport (Context context)
  {
    List<Entry> all = listExports (context);
    return all.isEmpty () ? null : all.get (0);
  }

  // --- writing ----------------------------------------------------------------------------------

  /** Create {@code name} under the tree; returns the document Uri.  */
  public static Uri
  createFile (Context context, Uri tree, String mime, String name) throws FileNotFoundException
  {
    Uri parent = DocumentsContract.buildDocumentUriUsingTree (tree,
                                                              DocumentsContract.getTreeDocumentId (tree));
    Uri doc = DocumentsContract.createDocument (context.getContentResolver (), parent, mime, name);
    if (doc == null)
      throw new FileNotFoundException ("cannot create " + name);
    return doc;
  }

  /** Rename a document; returns its new Uri.  */
  public static Uri
  rename (Context context, Uri doc, String newName) throws FileNotFoundException
  {
    Uri renamed = DocumentsContract.renameDocument (context.getContentResolver (), doc, newName);
    if (renamed == null)
      throw new FileNotFoundException ("cannot rename to " + newName);
    return renamed;
  }

  /** Delete a document, quietly.  */
  public static void
  delete (Context context, Uri doc)
  {
    if (doc == null)
      return;
    try
      {
        DocumentsContract.deleteDocument (context.getContentResolver (), doc);
      }
    catch (Exception e)
      {
        // pass — a partial we could not remove is still named in the failure text
      }
  }

  /** Size and display name of a document, or null.  */
  public static Entry
  stat (Context context, Uri doc)
  {
    Cursor c = null;
    try
      {
        c = context.getContentResolver ()
          .query (doc, new String[] { Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
                                      Document.COLUMN_SIZE }, null, null, null);
        if (c != null && c.moveToFirst ())
          return new Entry (doc, c.getString (0), c.isNull (1) ? 0 : c.getLong (1),
                            c.isNull (2) ? 0 : c.getLong (2));
        return null;
      }
    catch (Exception e)
      {
        return null;
      }
    finally
      {
        if (c != null)
          c.close ();
      }
  }

  /** The persistable-grant flags a tree picker intent must carry.  */
  public static Intent
  treePicker (Context context)
  {
    Intent intent = new Intent (Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                     | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                     | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    Uri initial = dirUri (context);
    if (initial != null)
      intent.putExtra (DocumentsContract.EXTRA_INITIAL_URI, initial);
    return intent;
  }

  /** A file picker for a backup archive.  */
  public static Intent
  filePicker ()
  {
    Intent intent = new Intent (Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory (Intent.CATEGORY_OPENABLE);
    intent.setType ("*/*");
    intent.putExtra (Intent.EXTRA_MIME_TYPES,
                     new String[] { "application/zip", "application/octet-stream", "*/*" });
    return intent;
  }

  static ContentResolver
  resolver (Context context)
  {
    return context.getContentResolver ();
  }
}
