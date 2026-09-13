---
name: build-apk
description: Build the signed release APK of shiroikuma-emacs (白い熊 GNU Emacs — our Android fork of GNU Emacs master, app id shiroikuma.emacs on Termux's shared UID) with ./build-fork.sh, ALWAYS in the background (a cold build is ~20 minutes, a warm one under a minute), then deliver it automatically via the global /after-build skill (adb push if the phone is reachable, else scp to skhw — no prompt). Always build first without asking permission to build. Use whenever 白い熊 asks to build the app, build the APK, make a release build, or build and send to the phone, and after any functional code change.
---

# Build the 白い熊 GNU Emacs release APK and deliver it

> **ALWAYS build, then ALWAYS deliver — no asking (白い熊's standing authorization, 2026-07-09).**
> After ANY functional change, build **immediately** and deliver. Do not stop at a compile-check, do
> not offer to build, do not ask how to transfer it. Build-and-deliver does **not** commit or push —
> a commit/push still waits for 白い熊's explicit "Push". (Skip the build only for non-functional
> edits — docs, comments.)

## Build environment (this machine)

This is **not a Gradle project**. Upstream builds the APK with `java/Makefile.in` (aapt / javac /
d8 / jarsigner / apksigner from the SDK build-tools) after `./configure --with-android=…`, and the
fork's `# --- shiroikuma fork ---` block in that Makefile reads `FORK_*` make variables for the
installed id, version and signing. `./build-fork.sh` sets all of it — never call `make` or
`./configure` by hand for a shippable build.

- **JDK 11 only** (`/usr/lib/jvm/zulu11`): `configure.ac` passes `--release 7` to javac, which
  JDK 21 rejects. The script exports `JAVA_HOME`/`PATH` itself.
- SDK `~/android-sdk`: build-tools `37.0.0`, `platforms/android-37.1/android.jar`, NDK
  `27.3.13750724`, compiler `aarch64-linux-android29-clang` (one ABI, arm64-v8a, minSdk 29).
- Host tools: `autoconf`, `m4`, `makeinfo`, `tic`, `curl`, `git`, `shasum`.
- Third-party libraries: `emacs_deps/` (gitignored), fetched once by the script through
  `admin/download-android-deps.sh 64` (network, ~150 MB from SourceForge — slow). If a dependency
  refuses to build, drop **that** `--with-…` flag from the configure line in `build-fork.sh`, rerun
  with `--reconfigure`, and say so in the handover; `--with-selinux` and `--with-webp` are the
  fragile ones.

## Steps

1. **Note the output filename / version.**
   - `grep -n '^AC_INIT' configure.ac` — upstream's version (e.g. `32.0.50`); never hand-edited.
   - `grep -n '^Version-code:' java/AndroidManifest.xml.in` — upstream's reserved code base
     (e.g. `320050000`); never hand-edited.
   - `grep -E '^BUILD_NUMBER|^LAST_BUILT' fork.properties` — the `N` used for THIS build (the
     script bumps it afterwards) and the floor it must exceed.
   - The pin: `git merge-base HEAD master | cut -c1-8` and that commit's UTC committer time.
   - APK will be `shiroikuma-emacs_<AC_INIT>+<date>.<HH-MM>.g<sha8>+<NNN>_arm64-v8a.apk`
     (`N` zero-padded to three digits in the name), e.g.
     `shiroikuma-emacs_32.0.50+2026-09-13.02-35.gf0430371+001_arm64-v8a.apk`.
   - versionCode for this build = `<Version-code> + N` (plain), e.g. `320050001`.

2. **Build** — from the repo root, **always `run_in_background`**, then poll the log every few
   minutes and never abandon it. Measured 2026-09-13 on this 24-core host: the one-time deps
   download ≈ 13 min (SourceForge at 20–150 KB/s), `./configure` ≈ 2 min, the full `make` ≈ 6 min
   (host Emacs, byte-compiling all of lisp, the NDK cross build of `libemacs.so` + every dependency,
   the APK), a warm no-op rebuild ≈ 35 s — so ~20 min cold, ~9 min with `emacs_deps/` cached:
   ```bash
   ./build-fork.sh > <session scratchpad>/build-fork_<stamp>.log 2>&1
   ```
   - The script prints `>>> versionName …` / `>>> versionCode …` first, `>>> built java/emacs-…apk`
     plus the `aapt dump badging` package line when make finishes, then `>>> <~/tmp path>` and
     `>>> BUILD_NUMBER bumped to …` after the copy. Confirm all of them.
   - It refuses to run without `keystore.properties`, refuses a `versionCode` at or below
     `LAST_BUILT_VERSION_CODE`, refuses to overwrite an existing `~/tmp` APK, and only accepts an
     APK newer than the make it just ran (a failed make leaves the previous one in `java/`).
   - `./build-fork.sh --no-deliver` builds without the copy and without the bump (toolchain proof,
     experiments). `--reconfigure` re-runs `./configure` (after changing flags or deps).
   - The log goes to the session scratchpad or the repo's gitignored `.scratch/` — **never** to
     `~/tmp`, which holds only the final APK.
   - **Verify the signature** once per session:
     `~/android-sdk/build-tools/37.0.0/apksigner verify --print-certs <apk> | grep SHA-256` must
     show `50:b4:7e:8f:09:b8:78:1f:cc:c9:98:df:3f:c5:c0:2d:e0:dd:96:70:a3:d3:7e:6c:ac:ba:9f:4e:76:31:96:04`
     — the one key of the whole `com.termux` family. Any other fingerprint cannot install over the
     family and must not leave the PC.

3. **Deliver via the global `/after-build` skill** — no exceptions, no asking. It runs `/adb-check`
   UNSANDBOXED, `adb push`es **this repo's** newest `~/tmp/shiroikuma-emacs_*.apk` to `/sdcard/tmp/`
   if the phone is reachable, otherwise `scp`s it to `skhw:~/tmp/`, then announces what landed.
   `~/tmp/` is shared with parallel chats building sister apps — always pick the
   `shiroikuma-emacs_*` APK, never merely the newest file there. Never `adb install` — 白い熊
   installs from the on-device file manager.

4. **Never delete or prune older APKs** — not in `~/tmp/`, not in `/sdcard/tmp/`, not upstream's
   `java/emacs-*.apk`. Every build carries a unique `+NNN`; older builds stay where they are so 白い熊
   can roll back.

## On-phone notes (tell 白い熊 in the handover when relevant)

- The fork is a **new app** beside the stock `org.gnu.emacs` (same shared UID `com.termux`, same
  key). Until the Phase 3 patch list lands (DocumentsProvider authority + the `"org.gnu.emacs"`
  Java literals), the package **cannot install** next to the stock one
  (`INSTALL_FAILED_CONFLICTING_PROVIDER`).
- `HOME` is `/data/data/shiroikuma.emacs/files`; migrate once from Termux with
  `cp -a /data/data/org.gnu.emacs/files/. /data/data/shiroikuma.emacs/files/`.

## Signing

Release signing is non-interactive: `build-fork.sh` reads `keystore.properties` (gitignored, at the
repo root; template `keystore.properties_sample`) and exports `FORK_KEYSTORE`,
`FORK_KEYSTORE_ALIAS`, `FORK_KEYSTORE_PASS`, `FORK_KEY_PASS` for `java/Makefile.in`, whose
`SIGN_EMACS` (jarsigner, `-storepass:env`) and `SIGN_EMACS_V2` (apksigner, `--ks-pass env:`) never
put the password on a command line. The keystore is
`~/.android-keystores/shiroikuma-emacs-termux.jks`, alias `Emacs keystore` (**with the space** —
always quoted), PKCS12 RSA-2048, created 2022-12-25, valid to 2296: it is upstream GNU Emacs's own
public `java/emacs.keystore`, chosen by 白い熊 as the one key for the whole `com.termux` shared-UID
family. Password recorded in `~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org`, backup in that
directory's `android-keystores/`. If `keystore.properties` is missing the script stops — restore it
rather than working around it (an APK signed with anything else cannot join the family).

## Versioning (how the numbers are formed)

- Upstream's `AC_INIT` version and the manifest's `Version-code:` trailer are the base; a rebase
  brings new values in automatically. **Never hand-edit them.**
- `BUILD_NUMBER` in `fork.properties` is **our** increment, bumped on every delivered build. It is
  **monotonic**: reset to `1` only when the `Version-code:` base itself moves (a new Emacs major
  version), never on a sync that merely re-pins the sha — `versionCode = base + N` is all an
  installer compares. `LAST_BUILT_VERSION_CODE` in the same file is the floor the script enforces.
- `versionName = "<AC_INIT>+<base date>.<HH-MM>.g<sha8>+<NNN>"` (global `git-versioning` skill;
  pin = `git merge-base HEAD master`, committer time in UTC); `versionCode = <Version-code> + N`.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of
the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
