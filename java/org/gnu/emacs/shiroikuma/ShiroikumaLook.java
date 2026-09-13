/* The house look of the 白い熊 GNU Emacs UI page.  -*- java -*-

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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The 白い熊 house look shared by the UI page, the Export / Import panel and
 * every dialog they raise: black ground, yellow ink, dim yellow summaries,
 * one warning red, bordered rounded panels and pill buttons.  Framework
 * Java 7 (the Android side of Emacs is compiled with {@code --release 7}):
 * no lambdas, no AndroidX.
 */
public final class ShiroikumaLook
{
  /** Black ground.  */
  public static final int BG = 0xFF000000;
  /** Yellow ink.  */
  public static final int INK = 0xFFFFFF00;
  /** Dim yellow (summaries, captions).  */
  public static final int DIM = 0xFFC8C800;
  /** The one thing on the page that is not yellow: a missing backup destination.  */
  public static final int WARN = 0xFFFF5252;

  private ShiroikumaLook ()
  {
  }

  public static int
  dp (Context context, float v)
  {
    return Math.round (v * context.getResources ().getDisplayMetrics ().density);
  }

  /** A label in the page font: size in sp, colour, optional bold.  */
  public static TextView
  text (Context context, CharSequence s, int sizeSp, int color, boolean bold)
  {
    TextView tv = new TextView (context);
    tv.setText (s);
    tv.setTextColor (color);
    tv.setTextSize (TypedValue.COMPLEX_UNIT_SP, sizeSp);
    if (bold)
      tv.setTypeface (tv.getTypeface (), Typeface.BOLD);
    return tv;
  }

  /** The bordered rounded panel every surface of our dialogs is drawn on.  */
  public static GradientDrawable
  panelBackground (Context context, int radiusDp)
  {
    GradientDrawable bg = new GradientDrawable ();
    bg.setColor (BG);
    bg.setStroke (Math.max (1, dp (context, 2)), INK);
    bg.setCornerRadius (dp (context, radiusDp));
    return bg;
  }

  /**
   * A round pill button: black fill, 1.5 dp yellow stroke, yellow text,
   * yellow ripple, no all-caps, no minimum width.
   */
  public static Button
  pill (Context context, String label, View.OnClickListener onClick)
  {
    Button b = new Button (context);
    b.setText (label);
    b.setAllCaps (false);
    b.setTextColor (INK);
    GradientDrawable bg = new GradientDrawable ();
    bg.setColor (BG);
    bg.setStroke (Math.max (1, dp (context, 1.5f)), INK);
    bg.setCornerRadius (dp (context, 50));
    b.setBackground (new RippleDrawable (ColorStateList.valueOf ((INK & 0x00FFFFFF) | 0x33000000),
                                         bg, null));
    b.setStateListAnimator (null);
    b.setMinWidth (0);
    b.setMinimumWidth (0);
    b.setMinHeight (0);
    b.setMinimumHeight (0);
    b.setPadding (dp (context, 20), dp (context, 8), dp (context, 20), dp (context, 8));
    b.setOnClickListener (onClick);
    b.setLayoutParams (new ViewGroup.MarginLayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT,
                                                         ViewGroup.LayoutParams.WRAP_CONTENT));
    return b;
  }

  /**
   * Paint a platform AlertDialog (title, message, buttons) in the house
   * colours.  The theme ({@code ShiroikumaDialog}) already sets the panel
   * and the list-item colour; this is the belt to that braces for the
   * views the theme attributes do not reach on every OEM skin.
   */
  public static void
  style (Dialog dialog)
  {
    if (dialog == null)
      return;
    Context ctx = dialog.getContext ();
    int titleId = ctx.getResources ().getIdentifier ("alertTitle", "id", "android");
    if (titleId != 0)
      {
        View v = dialog.findViewById (titleId);
        if (v instanceof TextView)
          ((TextView) v).setTextColor (INK);
      }
    View message = dialog.findViewById (android.R.id.message);
    if (message instanceof TextView)
      ((TextView) message).setTextColor (INK);
    styleButton (dialog.findViewById (android.R.id.button1));
    styleButton (dialog.findViewById (android.R.id.button2));
    styleButton (dialog.findViewById (android.R.id.button3));
  }

  private static void
  styleButton (View v)
  {
    if (v instanceof Button)
      ((Button) v).setTextColor (INK);
  }

  /** Create + show a builder's dialog and style it once shown.  */
  public static AlertDialog
  show (AlertDialog.Builder builder)
  {
    final AlertDialog dialog = builder.create ();
    dialog.setOnShowListener (new DialogInterface.OnShowListener () {
        @Override
        public void
        onShow (DialogInterface d)
        {
          style (dialog);
        }
      });
    dialog.show ();
    return dialog;
  }

  /** Our own dialogs own their surface, so the window behind them is transparent.  */
  public static void
  transparentWindow (Dialog dialog)
  {
    Window window = dialog.getWindow ();
    if (window != null)
      window.setBackgroundDrawable (new android.graphics.drawable.ColorDrawable (0));
  }

  public static void
  toast (Context context, CharSequence text)
  {
    Toast.makeText (context.getApplicationContext (), text, Toast.LENGTH_SHORT).show ();
  }
}
