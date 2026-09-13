# Changelog

This file carries the history of the **白い熊 GNU Emacs** fork (`shiroikuma-emacs`) — everything
built on top of stock GNU Emacs, one section per fork release, newest first, each naming the
upstream commit it is rebased on. Upstream GNU Emacs ships no `CHANGELOG.md`; its own history is
`etc/NEWS` and the `ChangeLog.*` files, which are left untouched.

## 白い熊 GNU Emacs 32.0.50+2026-09-13.02-35.gf0430371+005 — 2026-09-13

**Upstream base:** [emacs-mirror/emacs](https://github.com/emacs-mirror/emacs) branch `master`,
commit `f0430371c8d5b1e172670cdbc59f4d298be64a29` (2026-09-13 02:35 UTC, *"elec-pair.el: fix typo
in comment"*), `AC_INIT` version `32.0.50`, F-Droid `Version-code:` slot `320050000`.
`versionName 32.0.50+2026-09-13.02-35.gf0430371+005`, `versionCode 320050005`, `arm64-v8a`,
`minSdk 29` (Android 10+), `targetSdk 37`. First fork release — this section lists everything
built on top of stock.

### Major features

- **GNU Emacs `master` for Android with the full dependency set**, configured the way the
  SourceForge `termux/` packages are: `--with-gnutls`, `--with-harfbuzz`, `--with-tree-sitter`,
  `--with-sqlite3`, `--with-xml2`, `--with-gif --with-jpeg --with-png --with-tiff --with-webp`,
  `--with-selinux`, all from upstream's own ndk-build ports (`admin/download-android-deps.sh 64`);
  `--without-android-debug`, `--with-native-compilation=no`. One ABI, `arm64-v8a`, built with
  `aarch64-linux-android29-clang` (`minSdk 29`).
- **Termux's shared user ID** (`./configure --with-shared-user-id=com.termux`) and the Termux
  family's signing key, so the Emacs process runs as the same Linux user as Termux and every tool
  in `/data/data/com.termux/files/usr` is readable and executable from `shell`, `eshell`,
  `compile`, `vc`, `grep`, `eglot`, `magit` and the rest. `HOME` is
  `/data/data/shiroikuma.emacs/files`; the stock app's home migrates with one
  `cp -a /data/data/org.gnu.emacs/files/. /data/data/shiroikuma.emacs/files/` from Termux.
- **Installs side-by-side with the stock `org.gnu.emacs`** as `shiroikuma.emacs` — the installed
  id is renamed at packaging time (aapt `--rename-manifest-package`) while the `org.gnu.emacs`
  Java namespace, which every JNI class path in `src/android*.c` depends on, stays as upstream has
  it. Every runtime use of the package name was made to follow the *installed* id (see *Identity &
  packaging*), which is what removes `INSTALL_FAILED_CONFLICTING_PROVIDER` and lets both apps
  coexist.
- **The 白い熊 GNU Emacs UI page** (`org.gnu.emacs.shiroikuma.ShiroikumaUiActivity`) replacing the
  stock options screen, with Export/Import of the whole `~`, the stock options, Appearance and
  Reset (see *UI & theming*).
- **Export / Import of settings and the entire Emacs home directory** as one zip with an in-tree
  tar engine, and the **sister-app backup-automation contract v2** so 保存復元 (the
  shiroikuma-jiyusagyoban automation) and 応用管理 can back the app up headlessly (see *Backup &
  automation*).
- **Upstream-pinned versioning**: `versionName = <AC_INIT>+<base commit date>.<HH-MM>.g<sha8>+<NNN>`
  (UTC committer time and 8-char sha of `git merge-base HEAD master`), `versionCode` = upstream's
  reserved `Version-code:` trailer + `BUILD_NUMBER` (`320050000 + N`, plain integer, monotonic
  across syncs — reset only when the base itself moves). Upstream's `AC_INIT` literal and the
  manifest's placeholder `android:versionCode="30"` are read, never edited, so a rebase never
  conflicts.

### UI & theming

- **Black/yellow traced launcher icon**: the gnu-E swirl inside a disc outline as `#FFFF00` line
  art on black (`design/shiroikuma-emacs-icon.svg`); the options entry adds a wrench
  (`design/shiroikuma-emacs-ui-icon.svg`). `tools/icon/emit_launcher.py` generates everything from
  the two SVG models: the adaptive icons (`java/res/mipmap-v26/shiroikuma_icon.xml`,
  `shiroikuma_ui_icon.xml`, with `drawable/shiroikuma_background.xml`, `shiroikuma_foreground.xml`,
  `shiroikuma_ui_foreground.xml`), the legacy mipmap PNGs (`mipmap/shiroikuma_icon.png`,
  `shiroikuma_ui_icon.png`), the DocumentsProvider sidebar icon (`drawable/shiroikuma.png`) and the
  monochrome notification glyph (`drawable/shiroikuma_notification.xml`). Upstream's
  `emacs_icon.*`, `emacs.png` and `emacs_wrench.png` stay in the tree, unreferenced.
- **Our name everywhere user-visible** (`java/res/values/shiroikuma_strings.xml`, upstream's
  `strings.xml` untouched): app label *白い熊 GNU Emacs*, the options activities *白い熊 GNU Emacs
  UI*, the service *白い熊 GNU Emacs service*, the Files/SAF root title *白い熊 GNU Emacs* with
  summary *白い熊 GNU Emacs home directory*, the notification channel *白い熊 GNU Emacs background
  service*. Upstream's copyright and attribution, code namespaces, log tags, intent actions and
  extras are untouched.
- **The 白い熊 GNU Emacs UI page** — a plain `Activity` with programmatic views, framework Java 7
  only (`--release 7`: anonymous classes, no lambdas, no AndroidX; SAF via `DocumentsContract`,
  dialogs via `android.app.AlertDialog`), house look (`ShiroikumaLook`: black ground, `#FFFF00`
  ink, dimmed yellow captions, red warnings; theme `ShiroikumaStyle` in
  `java/res/values/shiroikuma_style.xml`; bordered dialog background
  `drawable/shiroikuma_dialog_bg.xml`). Sections:
  - **Export / Import** — the *Export / Import…* entry row; *Export directory* (red *Not set — tap
    to choose a folder* until chosen; *Access lost with the install — tap to choose it again* when
    the SAF grant is gone); *Last export* (name, size and date of the newest backup in that
    directory, queried on resume on a background thread); *Automation export* switch (on by
    default); *Use authorization token?* switch (off by default) with the token row — copy to
    clipboard, *Regenerate* with a confirming dialog — shown while on.
  - **Emacs options** — the three stock rows re-implemented on the page: *Restart with `-Q`*,
    *Restart with `--debug-init`* (both via upstream's own `System.exit(0)` restart trick) and
    *Delete dump file* (removes `emacs-*.pdmp`, forgets `EmacsApplication.dumpFileName`, toasts
    *Dump file removed.* / *There is no dump file to remove.*). Upstream's
    `EmacsPreferencesActivity` and `res/xml/preferences.xml` stay compiled but unreferenced.
  - **Appearance** — *Black system bars* (default on: `ShiroikumaPrefs.applyWindowPrefs` paints the
    status and navigation bars black behind `EmacsActivity`) and *Keep screen on while Emacs is in
    front* (default off: `FLAG_KEEP_SCREEN_ON`); caption noting that fonts, colours, the cursor and
    the keyboard are Emacs's own.
  - **Reset** — *Reset this page* with a confirming dialog: clears the `shiroikuma_ui` and
    `shiroikuma_eximport` preferences only; the automation switch, the token and `~` are left alone.
- **Three ways into the page**: the *白い熊 GNU Emacs UI* launcher entry
  (`EmacsLauncherPreferencesActivity extends ShiroikumaUiActivity`, now enabled on every Android
  version rather than only before Nougat); Android Settings › App info › *App settings*
  (`android.intent.action.APPLICATION_PREFERENCES` on the page); and a static launcher shortcut on
  a long-press of the Emacs icon (`java/res/xml/shortcuts.xml`, `android.app.shortcuts` meta-data
  on `EmacsActivity`).
- **Preferences files**: `shiroikuma_ui` (exported: `black_bars`, `keep_screen_on`),
  `shiroikuma_eximport` (device-local: the export directory's SAF tree URI, the `pending_import`
  relaunch marker and its item list), `shiroikuma_automation` (device-local: `automation_enabled`,
  `automation_require_token`, a lazily generated 48-hex `automation_token`). All writes `commit()`.

### Backup & automation

- **Export / Import panel** (`ExportImportPanel`, the family panel): a settable export directory
  (SAF tree picker, persisted grant), category checkboxes with *Select all*, pill buttons
  *Export* / *Import* / *Browse…* / *Close* / *Start Emacs*, progress lines (*Exporting…* /
  *Importing…*, `files n/N · size`), chain-closing result dialogs (*Export finished* with the
  written path, size and category count; *Import finished* with the per-category summary and the
  hint that Emacs reads the restored files on its next start; *Export failed* / *Import failed*
  with the reason), and refusals for a foreign zip (*Not a 白い熊 GNU Emacs backup.*) and an
  empty selection.
- **Backup engine** (`EmacsBackup`): one `shiroikuma-emacs_<yyyy-MM-dd_HH-mm-ss>.zip`, written as
  `<name>.part` and renamed on completion (the partial is deleted on any failure or cancel).
  Entries: `manifest.json` first (app id, version, format, categories), `settings.json` (the
  `shiroikuma_ui` preferences as type-tagged JSON), `data.home.tar`. Two top-level categories,
  both on by default: `settings` (*Settings*) and `data.home` (*Home directory (~/.emacs.d, init
  files, packages)*). DEFLATE at `BEST_SPEED`. `emacsRunning()` (`EmacsService.SERVICE != null`)
  guards the home restore.
- **In-tree tar engine** (`TarWriter` / `TarReader`): POSIX ustar with GNU `././@LongLink` records
  for long names and link targets, modes and mtimes from `Os.lstat`, symlinks kept as symlinks,
  sockets and devices skipped (and counted in the summary), `files/emacs-*.pdmp` excluded. The
  archive is `tar -C <dataDir> -cf - files`, restorable by hand with
  `unzip -p x.zip data.home.tar | tar -C /data/data/shiroikuma.emacs -x`.
- **Import while Emacs runs**: the page explains (*Emacs is running — the home directory cannot be
  restored under a running Emacs*), spools the zip, sets the `pending_import` marker, relaunches
  itself with `System.exit(0)`, restores in the fresh process (*Restoring…*, do not start Emacs) and
  then offers *Start Emacs*.
- **SAF helper** (`SafDir`): `usableDir` (re-checks the persisted grant), `createFile` (MIME
  `application/octet-stream` for the `.part`, so the provider does not append `.zip`), `rename`,
  `delete`, `stat`, `absolutePathOf` for the reply strings.
- **Sister-app backup-automation contract v2**, §1 broadcast door — `StateExportReceiver`
  (exported, no permission by design): `shiroikuma.emacs.action.EXPORT_STATE` (gated by
  `AutomationAuth`; `items` absent = the default categories, unknown item → `ERROR:unknown item
  <id>`; a `path` extra is honoured only with All-files access, otherwise exactly
  `ERROR:no-storage-access`; no `path` → the configured export directory, else
  `ERROR:no-directory`; mints a job and starts the foreground service, mapping a refused start to
  `ERROR:no-foreground-start` / `ERROR:cannot start export service: <Class>`),
  `shiroikuma.emacs.action.LIST_CATEGORIES` (answered in `onReceive`) and
  `shiroikuma.emacs.action.CANCEL_EXPORT` (silent). Replies are fresh broadcasts with `setPackage`
  and `FLAG_INCLUDE_STOPPED_PACKAGES`, `reply_id` echoed verbatim, `result` =
  `OK:<abs path>|<bytes>|<human>|<n> categories`; exactly one terminal reply per request.
- **§2a data door** — `AutomationProvider` (authority `shiroikuma.emacs.automation`, exported):
  caller verified by `AutomationCallers` (exact package name → UID cross-check → pinned SHA-256 of
  the signing certificate; callers `shiroikuma.oyokanri` and `shiroikuma.jiyusagyoban`), then the
  token gate. `describe` (header JSON from PackageManager plus the category enum — runs before
  `Application.onCreate`, never touches `EmacsNative`), `export` / `import` over a duplicated file
  descriptor (`OK:<job_id>`; `import` refuses `ERROR:Emacs is running; quit it (C-x C-c) or
  force-stop, then retry` when `data.home` is wanted under a running Emacs), `cancel`
  (`OK:cancelled`). Refusals are returned, never thrown.
- **`BackupService`** (`foregroundServiceType="dataSync"`, unexported, one job at a time,
  notification channel `shiroikuma_backup` — *白い熊 GNU Emacs backup* — with *Backing up for a
  sister app…* / *Restoring from a sister app…* / *Receiving the archive…*, a 15-minute partial
  wakelock): §1 writes the `.part` and renames on completion; §2a export streams into the
  descriptor through a counting stream (`OK:<bytes>|<human>|<n> categories`); §2a import spools to
  `getCacheDir()/automation-import-<job>.zip`, validates, merges, commits `shiroikuma_ui` and
  replies `OK:<n> categories restored; …`. `AutomationJobs` holds job ids and cancel flags;
  `AutomationAuth` the switch and token (`refuse (Context, token)`).
- **Progress broadcasts** (`AutomationProgress`, the one §3 sender): only with a `progress_action`
  and a `reply_package`; `reply_id` (+ `job_id` on the descriptor door), `app`, `item` = category
  id, numbers-first `text` (`区分 1/2 — Settings`, `ファイル n/N · x MB / y MB`), `current` / `total`,
  `unit`, `bytes` / `bytes_total`; at least 500 ms apart, the final line always sent, a heartbeat
  re-sending the last line every 20 s.
- **Manifest**: `<queries>` for the two callers, the receiver, the provider, the service, and the
  three capability integers `shiroikuma.automation.contract = 2`, `shiroikuma.automation.format =
  1`, `shiroikuma.automation.min_format = 1` as `<meta-data>` (readable by 応用管理 without
  waking the app).

### Identity & packaging

- **`java/Makefile.in` fork block** (`# --- shiroikuma fork ---`): `FORK_APPLICATION_ID`
  (default `shiroikuma.emacs`; passed as aapt `--rename-manifest-package` to the two packaging
  invocations only, never to the one generating `R.java`, so `org.gnu.emacs.R` stays),
  `FORK_VERSION_NAME` / `FORK_VERSION_CODE` (aapt `--version-name` / `--version-code`
  `--replace-version`), `FORK_KEYSTORE` / `FORK_KEYSTORE_ALIAS` / `FORK_KEYSTORE_PASS` /
  `FORK_KEY_PASS` (jarsigner `-storepass:env`, apksigner `--ks-pass env:` — the passwords never
  appear on a command line). Every variable defaults to upstream's behaviour when unset.
  `JAVA_FILES` also globs `org/gnu/emacs/shiroikuma/`. `EmacsConfig` gains
  `APPLICATION_ID`, the installed id, for code that runs before any `Context` exists.
- **Installed-id patch list** — every runtime use of the package name follows the installed id,
  never the namespace: `EmacsApplication` (`getPackageName ()`), `EmacsNoninteractive`
  (`EmacsConfig.APPLICATION_ID`, no Context yet), `EmacsDesktopNotification`
  (`context.getPackageName ()` for `RemoteViews` / `setPackage`), `EmacsDocumentsProvider`
  (`buildChildDocumentsUri` authority; root title, summary and icon), `EmacsService`
  (`buildDocumentUri` authority, the `package:` All-files-access settings URI, channel name,
  notification title and small icon), `EmacsWindowManager` (full `getClassName ()` comparison
  against `EmacsMultitaskActivity.class.getName ()` — `getShortClassName ()` only abbreviates
  inside the manifest package). The DocumentsProvider authority in the manifest is
  `shiroikuma.emacs`. The three `org.gnu.emacs` strings that remain are names, not the package:
  the `org.gnu.emacs.DISMISSED` intent action, the `org.gnu.emacs.STARTUP_ARGUMENTS` extra and the
  `org.gnu.emacs.EmacsNoninteractive` class loaded by reflection.
- **One key for the whole `com.termux` family**: `keystore.properties` (gitignored; template
  `keystore.properties_sample`) points at the family keystore; `build-fork.sh` refuses to run
  without it. Upstream's tracked `java/emacs.keystore` stays as upstream has it but no longer
  signs.
- **Fork header prepended to the plain-text `README`** above upstream's text (what the fork
  changes, fork home and releases); `README.md` (this repo page) and `CHANGELOG.md` added.
  `CLAUDE.md` replaces upstream's `@AGENTS.md` pointer for the fork layer (upstream's `AGENTS.md`
  itself is left untouched); `.claude/skills/build-apk` and `.claude/skills/upstream-new-version`
  document the build and the proceed-gated upstream sync.
- **`.gitignore`**: `keystore.properties`, root `*.jks`, `emacs_deps/`, `.scratch/`,
  `fork.properties.bak`, `.claude/settings.local.json`, `java/org/gnu/emacs/shiroikuma/*.class`;
  `.claude/` un-ignored from upstream's leading `.*` rule so the agent docs are tracked.

### Build pipeline

- **`build-fork.sh`** — the one reproducible entry point: JDK 11 (`/usr/lib/jvm/zulu11`, because
  `configure.ac` compiles Java with `--release 7`), SDK build-tools `37.0.0`,
  `platforms/android-37.1/android.jar`, NDK r27 (`27.3.13750724`, 16 KB page-size support),
  `aarch64-linux-android29-clang`; checks every toolchain file and host tool up front; reads
  `keystore.properties` and `fork.properties`; computes `versionName` / `versionCode` and refuses a
  `versionCode` at or below `LAST_BUILT_VERSION_CODE`; fetches the dependencies once into
  `emacs_deps/` (`admin/download-android-deps.sh 64`, run with bash since the `sh`-shebanged
  script uses `==`); runs `./autogen.sh autoconf` (never bare `./autogen.sh`, which would install
  upstream's `commit-msg` / `pre-commit` hooks); configures only when `config.status` is missing
  or `--reconfigure` is given; `make all -j$(nproc)` with the `FORK_*` variables; verifies the APK
  is one this run produced; prints the badging, the `sharedUserId` and the signing certificate;
  copies to `~/tmp/shiroikuma-emacs_<versionName>_arm64-v8a.apk` (never overwriting) and bumps
  `BUILD_NUMBER` + `LAST_BUILT_VERSION_CODE`. `--no-deliver` builds without the copy and the bump.
- **The sqlite3 patch**: `admin/download-android-deps.sh` clones AOSP sqlite but never applies the
  patch `java/INSTALL` (*PATCH FOR SQLITE3*) requires — without it `configure` finds the module,
  fails with `sqlite3.h file not found` and silently drops `--with-sqlite3`. `build-fork.sh` now
  adds `LOCAL_EXPORT_C_INCLUDES += $(LOCAL_PATH)` to `libsqlite_static_minimal` in
  `emacs_deps/sqlite/dist/Android.mk` and comments out `HAVE_POSIX_FALLOCATE` in `sqlite3.c`, so
  SQLite is really compiled in.
- **`fork.properties`**: `BUILD_NUMBER` (our per-build `N`, monotonic) and
  `LAST_BUILT_VERSION_CODE` (the floor the script enforces), with the versioning rules documented
  in the file.
- Measured on the 24-core build host: one-time deps download ≈ 13 min, `./configure` ≈ 2 min, the
  full `make` ≈ 6 min, warm no-op rebuild ≈ 35 s.
