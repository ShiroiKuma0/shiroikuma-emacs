<div align="center">

<img src="design/shiroikuma-emacs-icon.svg" width="120" alt="白い熊 GNU Emacs icon" />

# 白い熊 GNU Emacs

**GNU Emacs `master`, built for Android with everything switched on, living on Termux's UID.**

A fork of [GNU Emacs](https://github.com/emacs-mirror/emacs) (the mirror of Savannah's `master`) with **major additions**: the full native dependency set (GnuTLS, HarfBuzz, tree-sitter, SQLite, libxml2, GIF/JPEG/PNG/TIFF/WebP, SELinux), Termux's shared user ID so every Termux CLI tool is reachable from Emacs, the 白い熊 GNU Emacs UI page with Export/Import of the whole `~` as a tar-in-zip backup, the sister-app backup-automation contract, the black/yellow traced icon, and a reproducible signed build pipeline pinned to the exact upstream commit it was built from.

Installs **side-by-side** with the stock GNU Emacs (app id `shiroikuma.emacs`, next to `org.gnu.emacs`). The whole family — Termux, Termux API, Termux X11, Termux GUI and 白い熊 GNU Emacs — shares Android UID `com.termux` and is signed with one key, so every member must come from these forks.

**📥 Latest release: [`32.0.50+2026-09-13.02-35.gf0430371+005`](https://github.com/ShiroiKuma0/shiroikuma-emacs/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-emacs/releases)

</div>

---

## 🐧 Emacs `master`, the whole dependency set, arm64-v8a, Android 10+

This is the development tip of GNU Emacs — not a release tarball — configured the way the
SourceForge `termux/` packages are, with every optional library compiled in: **GnuTLS** (TLS for
`package.el`, Gnus, `url`, `eww`), **HarfBuzz** (proper shaping for CJK, Arabic, Indic and ligature
fonts), **tree-sitter** (all the `*-ts-mode`s), **SQLite** (`sqlite-open` and friends, `emacsql`),
**libxml2** (`eww`, `shr`, `nxml`), **GIF / JPEG / PNG / TIFF / WebP** image display and
**SELinux** (Tramp, `file-selinux-context`). One ABI — `arm64-v8a` — with `minSdk 29`, i.e. Android 10
and later. Native compilation is off (`--with-native-compilation=no`).

The version string tells you exactly which upstream commit is inside:
`32.0.50+2026-09-13.02-35.gf0430371+005` is `AC_INIT`'s `32.0.50` **+** the UTC committer time and
the 8-char sha of the `master` commit the fork is rebased on **+** the build counter. Upstream's
`32.0.50` stands still for the whole development cycle; the pin does not.

---

## 🐚 On Termux's UID — every Termux tool is an Emacs tool

The APK is built with `--with-shared-user-id=com.termux` and signed with the Termux family's key, so
the process **runs as the same Linux user as Termux**. `/data/data/com.termux/files/usr` — git,
ripgrep, Python, Node, gcc, LaTeX, whatever `pkg install` put there — is readable and executable
from inside Emacs: `M-x shell`, `eshell`, `compile`, `vc`, `grep`, `magit`, `flymake`, `eglot`
language servers, all of it. Put the prefix on `PATH` in `~/early-init.el` (or `init.el`):

```elisp
(setenv "PATH" (format "%s:%s" "/data/data/com.termux/files/usr/bin" (getenv "PATH")))
(push "/data/data/com.termux/files/usr/bin" exec-path)
```

`HOME` is `/data/data/shiroikuma.emacs/files`. Coming from the stock app? Copy your `~` over once
from Termux: `cp -a /data/data/org.gnu.emacs/files/. /data/data/shiroikuma.emacs/files/`.

---

## 🧰 The 白い熊 GNU Emacs UI page

A second launcher entry — **白い熊 GNU Emacs UI** — also reachable from Android's *App info › App
settings* and from a long-press on the Emacs icon. It replaces the stock options screen with a
house black/yellow page, framework-only (no support libraries):

- **Export / Import** — see below.
- **Emacs options** — the three stock rows re-implemented: *Restart with `-Q`*, *Restart with
  `--debug-init`*, *Delete dump file*.
- **Appearance** — *Black system bars* (status and navigation bars painted black behind Emacs, on
  by default) and *Keep screen on while Emacs is in front*. Fonts, colours, the cursor and the
  keyboard are Emacs's own — set them in Lisp.
- **Reset** — forgets the appearance switches and the export directory, nothing else.

---

## 📦 Export / Import — your whole `~` in one zip

Choose an export directory once (any folder the system file picker offers; shown red until set),
tick the categories, tap **Export**: one `shiroikuma-emacs_<yyyy-MM-dd_HH-mm-ss>.zip` written as
`.part` and renamed on completion. Inside: `manifest.json`, `settings.json` (the page's switches)
and **`data.home.tar`** — a POSIX ustar archive of the entire Emacs home directory (`~/.emacs.d`,
init files, packages, everything) with modes, mtimes, symlinks and GNU long names preserved; the
`emacs-*.pdmp` dump is left out. **Import** restores just the parts you tick; if Emacs is running,
the page shuts it down, restores in a fresh process and offers to start Emacs again. The archive is
plain enough to restore by hand: `unzip -p x.zip data.home.tar | tar -C /data/data/shiroikuma.emacs -x`.

---

## 🤖 Backup automation for sister apps

The same export runs headlessly for 白い熊's other apps (sister-app backup-automation contract v2):
a broadcast door (`shiroikuma.emacs.action.EXPORT_STATE` / `LIST_CATEGORIES` / `CANCEL_EXPORT`)
and a data door (content provider `shiroikuma.emacs.automation` with `describe` / `export` /
`import` / `cancel`, caller pinned by package name, UID and signing certificate), both served by a
`dataSync` foreground service with progress broadcasts and a notification. The UI page has the
switch (*Automation export*, on by default) and an optional authorization token. This is what lets
保存復元 (the shiroikuma-jiyusagyoban automation) and 応用管理 back the app up on a schedule.

---

## 🎨 The traced icon and the 白い熊 name

The launcher art is the gnu-E swirl inside a disc outline, traced as yellow (`#FFFF00`) line art on
black; the UI entry adds a wrench. Both are generated from `design/*.svg` by
`tools/icon/emit_launcher.py` (adaptive icons, mipmap PNGs, the DocumentsProvider sidebar icon, the
monochrome notification glyph). The app, its options page, its background service, its Files
sidebar root and its notification channel all carry the 白い熊 GNU Emacs name; upstream's
copyright, code namespaces, log tags and intent actions are untouched.

---

## 👪 Family

The `com.termux` shared-UID family, all signed with one key:

- [shiroikuma-termux](https://github.com/ShiroiKuma0/shiroikuma-termux) — Termux
- [shiroikuma-termux-api](https://github.com/ShiroiKuma0/shiroikuma-termux-api) — Termux API
- [shiroikuma-termux-x11](https://github.com/ShiroiKuma0/shiroikuma-termux-x11) — Termux X11
- [shiroikuma-termux-gui](https://github.com/ShiroiKuma0/shiroikuma-termux-gui) — Termux GUI
- [shiroikuma-emacs](https://github.com/ShiroiKuma0/shiroikuma-emacs) — 白い熊 GNU Emacs (this repo)

---

## Built on GNU Emacs

A fork of [GNU Emacs](https://www.gnu.org/software/emacs/), tracked through the
[emacs-mirror/emacs](https://github.com/emacs-mirror/emacs) mirror of Savannah's `master` (app id
`shiroikuma.emacs`, so it coexists with the official `org.gnu.emacs` build). Emacs is the
extensible, customizable, self-documenting real-time display editor of the GNU Project and the Free
Software Foundation; the Android port is upstream's own (`java/`, `src/android*.c`). The code
remains under the [GNU GPL v3 or later](COPYING). Upstream's plain-text [`README`](README),
[`INSTALL`](INSTALL) and [`java/INSTALL`](java/INSTALL) are left as they are.

## Building

This is **not a Gradle project**: upstream packages the APK from `java/Makefile.in` (aapt, javac,
d8, jarsigner, apksigner) after `./configure --with-android=…`, and the fork's
`# --- shiroikuma fork ---` block in that Makefile takes the installed id, version and signing
from `FORK_*` variables. `build-fork.sh` sets all of them; never run `make` or `./configure` by hand
for a shippable build.

Toolchain (as used for the releases): **JDK 11** (`configure.ac` compiles the Java side with
`--release 7`, which newer JDKs reject), Android SDK build-tools `37.0.0`, `platforms/android-37.1`,
**NDK r27** (`27.3.13750724`), compiler `aarch64-linux-android29-clang`; host `autoconf`, `m4`,
`makeinfo`, `tic`, `curl`, `git`, `shasum`.

```bash
git clone --branch custom https://github.com/ShiroiKuma0/shiroikuma-emacs.git
cd shiroikuma-emacs
cp keystore.properties_sample keystore.properties   # then fill in storeFile / storePassword / keyAlias / keyPassword

# Signed release APK -> ~/tmp/shiroikuma-emacs_<versionName>_arm64-v8a.apk, then BUILD_NUMBER is bumped
./build-fork.sh
# Build only: leave the APK in java/, no copy, no bump (toolchain proof, experiments)
./build-fork.sh --no-deliver
# Re-run ./configure (after changing configure flags or the dependencies)
./build-fork.sh --reconfigure
```

`build-fork.sh` reads `fork.properties` and `keystore.properties`, computes the version
(`<AC_INIT>+<base date>.<HH-MM>.g<sha8>+<NNN>`, `versionCode` = the manifest's `Version-code:`
trailer + `BUILD_NUMBER`), fetches the third-party ndk-build ports once into the gitignored
`emacs_deps/` (`admin/download-android-deps.sh 64`, ~150 MB) and applies the sqlite3 patch that
`java/INSTALL` requires, runs `./autogen.sh autoconf`, configures with
`--with-shared-user-id=com.termux --without-android-debug --with-native-compilation=no` plus
`--with-gif --with-gnutls --with-harfbuzz --with-jpeg --with-png --with-selinux --with-sqlite3
--with-tiff --with-tree-sitter --with-webp --with-xml2`, and runs `make all`. A cold build is about
20 minutes (the deps download is the slow part); a warm rebuild well under a minute.

`keystore.properties` is gitignored and never committed; the passwords reach jarsigner/apksigner
through the environment only. Every member of the `com.termux` family has to be signed with the
**same** key, or Android refuses to install it beside the others.
