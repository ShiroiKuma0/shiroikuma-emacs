/* Gate of the sister-app automation surface.  -*- java -*-

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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The gate in front of the external-automation surface — the
 * {@code StateExportReceiver} broadcasts and the {@code AutomationProvider}
 * data door of the sister-app contract <b>v2</b> (a verbatim port of
 * raikidoban's {@code AutomationAuth}, framework Java 7).
 *
 * <p>Device-local by design: these values live in their OWN
 * SharedPreferences file, which is not one of {@link EmacsBackup.Cat}'s
 * categories, so the token never travels inside a backup ZIP.
 *
 * <p>v2: {@code automation_enabled} defaults to <b>true</b> and the token is
 * opt-in through {@code automation_require_token}, default <b>false</b> — a
 * phone being restored from a wipe has nobody to turn anything on.  A token
 * handed to an app that does not require one is IGNORED, never refused.
 * The whole decision lives in {@link #refuse} and nowhere else.
 */
public final class AutomationAuth
{
  public static final String PREFS_FILE = "shiroikuma_automation";
  private static final String KEY_ENABLED = "automation_enabled";
  private static final String KEY_REQUIRE_TOKEN = "automation_require_token";
  private static final String KEY_TOKEN = "automation_token";

  private AutomationAuth ()
  {
  }

  private static SharedPreferences
  prefs (Context context)
  {
    return context.getApplicationContext ().getSharedPreferences (PREFS_FILE, Context.MODE_PRIVATE);
  }

  /** The master switch.  <b>Default ON</b> (v2).  */
  public static boolean
  isEnabled (Context context)
  {
    return prefs (context).getBoolean (KEY_ENABLED, true);
  }

  public static void
  setEnabled (Context context, boolean enabled)
  {
    prefs (context).edit ().putBoolean (KEY_ENABLED, enabled).commit ();
  }

  /** Whether a caller must also present the token.  <b>Default OFF</b> (v2).  */
  public static boolean
  isTokenRequired (Context context)
  {
    return prefs (context).getBoolean (KEY_REQUIRE_TOKEN, false);
  }

  public static void
  setTokenRequired (Context context, boolean required)
  {
    prefs (context).edit ().putBoolean (KEY_REQUIRE_TOKEN, required).commit ();
  }

  /**
   * The one gate.  Returns {@code null} to proceed, otherwise the exact
   * {@code ERROR:} line to answer with — "automation disabled" and "bad
   * token" stay distinct because they debug differently.  When the token is
   * not required, {@code candidate} is not even looked at.
   */
  public static String
  refuse (Context context, String candidate)
  {
    if (!isEnabled (context))
      return "ERROR:automation disabled";
    if (isTokenRequired (context) && !isTokenValid (context, candidate))
      return "ERROR:bad token";
    return null;
  }

  /** The shared secret — 24 random bytes, hex; generated on first read.  */
  public static String
  token (Context context)
  {
    String stored = prefs (context).getString (KEY_TOKEN, null);
    if (stored != null && !stored.isEmpty ())
      return stored;
    return regenerateToken (context);
  }

  public static String
  regenerateToken (Context context)
  {
    byte[] bytes = new byte[24];
    new SecureRandom ().nextBytes (bytes);
    StringBuilder sb = new StringBuilder (bytes.length * 2);
    for (byte b : bytes)
      {
        sb.append (Character.forDigit ((b >> 4) & 0xF, 16));
        sb.append (Character.forDigit (b & 0xF, 16));
      }
    String token = sb.toString ();
    prefs (context).edit ().putString (KEY_TOKEN, token).commit ();
    return token;
  }

  /** Abbreviated form for the settings row — {@code 80922d8c…4c49a87c}.  */
  public static String
  abbreviate (String token)
  {
    if (token == null)
      return "";
    if (token.length () <= 20)
      return token;
    return token.substring (0, 8) + "…" + token.substring (token.length () - 8);
  }

  /** Constant-time comparison ({@link MessageDigest#isEqual}).  */
  public static boolean
  isTokenValid (Context context, String candidate)
  {
    if (candidate == null || candidate.isEmpty ())
      return false;
    return MessageDigest.isEqual (candidate.getBytes (StandardCharsets.UTF_8),
                                  token (context).getBytes (StandardCharsets.UTF_8));
  }
}
