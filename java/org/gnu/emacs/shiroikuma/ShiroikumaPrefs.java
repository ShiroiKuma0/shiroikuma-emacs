/* Preferences of the 白い熊 GNU Emacs UI page.  -*- java -*-

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
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * The two SharedPreferences files the page owns.
 *
 * <ul>
 * <li>{@code shiroikuma_ui} — the exported "Settings" category: the
 * Appearance switches.  Stock Emacs keeps no SharedPreferences of its own
 * (everything else is Lisp), so this is the whole of the category.</li>
 * <li>{@code shiroikuma_eximport} — device-local, never exported: the SAF
 * tree Uri of the export directory and the pending-import marker of the
 * relaunch trick (see {@link ShiroikumaUiActivity}).</li>
 * </ul>
 *
 * Every write is {@code commit()}: the page is regularly followed by
 * {@code System.exit(0)} (the stock restart options, the import relaunch),
 * and {@code apply()} would lose the race.
 */
public final class ShiroikumaPrefs
{
  public static final String UI_PREFS = "shiroikuma_ui";
  public static final String KEY_BLACK_BARS = "black_bars";
  public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";

  public static final String EXIMPORT_PREFS = "shiroikuma_eximport";
  public static final String KEY_DIR_URI = "dir_uri";
  public static final String KEY_PENDING_IMPORT = "pending_import";
  public static final String KEY_PENDING_ITEMS = "pending_items";

  private ShiroikumaPrefs ()
  {
  }

  public static SharedPreferences
  ui (Context context)
  {
    return context.getApplicationContext ().getSharedPreferences (UI_PREFS, Context.MODE_PRIVATE);
  }

  public static SharedPreferences
  eximport (Context context)
  {
    return context.getApplicationContext ().getSharedPreferences (EXIMPORT_PREFS,
                                                                  Context.MODE_PRIVATE);
  }

  public static boolean
  blackBars (Context context)
  {
    return ui (context).getBoolean (KEY_BLACK_BARS, true);
  }

  public static void
  setBlackBars (Context context, boolean on)
  {
    ui (context).edit ().putBoolean (KEY_BLACK_BARS, on).commit ();
  }

  public static boolean
  keepScreenOn (Context context)
  {
    return ui (context).getBoolean (KEY_KEEP_SCREEN_ON, false);
  }

  public static void
  setKeepScreenOn (Context context, boolean on)
  {
    ui (context).edit ().putBoolean (KEY_KEEP_SCREEN_ON, on).commit ();
  }

  /**
   * The one hook in upstream code: called from {@code EmacsActivity.onCreate}
   * after {@code setContentView} (EmacsMultitaskActivity inherits it), so an
   * Emacs frame honours the Appearance switches.  Cheap enough to run on
   * every frame; touches nothing when both switches are at their upstream
   * behaviour.
   */
  @SuppressWarnings ("deprecation")
  public static void
  applyWindowPrefs (Activity activity)
  {
    Window window = activity.getWindow ();
    if (window == null)
      return;
    if (blackBars (activity))
      {
        window.setStatusBarColor (Color.BLACK);
        window.setNavigationBarColor (Color.BLACK);
        /* Android 15+ enforces edge-to-edge for targetSdk 35+ and ignores
           the two colours above: the bars show the window ground, so
           paint that black (Emacs's own view covers the rest).  */
        if (Build.VERSION.SDK_INT >= 35)
          window.setBackgroundDrawable (new android.graphics.drawable.ColorDrawable (Color.BLACK));
        if (Build.VERSION.SDK_INT >= 30)
          {
            WindowInsetsController c = window.getInsetsController ();
            if (c != null)
              c.setSystemBarsAppearance (0,
                                         WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                         | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
          }
        else
          clearLightBarFlags (window.getDecorView ());
      }
    if (keepScreenOn (activity))
      window.addFlags (WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @SuppressWarnings ("deprecation")
  private static void
  clearLightBarFlags (View decor)
  {
    int flags = decor.getSystemUiVisibility ();
    flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    decor.setSystemUiVisibility (flags);
  }

  /** The Reset row: our two files, nothing else (automation prefs and ~ are left alone).  */
  public static void
  reset (Context context)
  {
    ui (context).edit ().clear ().commit ();
    eximport (context).edit ().clear ().commit ();
  }
}
