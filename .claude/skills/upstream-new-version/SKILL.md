---
name: upstream-new-version
description: Sync the shiroikuma-emacs fork onto the current tip of emacs-mirror/emacs master — fast-forward our master mirror, present the proceed-gated Android-relevant upstream-changes table, rebase custom, keep BUILD_NUMBER running, build the next +NNN. Use when 白い熊 says upstream Emacs has moved, asks to check/update/sync to upstream, or to rebase custom onto the latest Emacs master. ALWAYS present the proceed-gated table BEFORE rebasing; never push until 白い熊 says "Push".
---

# Sync shiroikuma-emacs onto the current upstream Emacs master

This fork tracks [emacs-mirror/emacs](https://github.com/emacs-mirror/emacs) `master` — the
byte-identical GitHub mirror of Savannah's `master`, i.e. the development tip that the SourceForge
`termux/` Android packages are built from. `master` here mirrors that tip **fast-forward only**;
`custom` carries our patches and is rebased onto it.

**We follow the branch tip, not release tags** (git tracking — see the global `git-versioning`
skill). Emacs `master` takes hundreds of commits a month while `AC_INIT`'s version literal
(`32.0.50`) stands still for the whole development cycle, so the fork versionName pins the upstream
base: `<AC_INIT>+<base date>.<HH-MM>.g<sha8>+<NNN>`. A sync happens whenever `upstream/master` has
moved past our `master`; how *often* we sync is 白い熊's call (a full rebuild is ~6–9 minutes here).

> **Never `git push` or `git commit` unprompted.** After the rebase + build you stop and let 白い熊
> test; you push only on their explicit **"Push"** (`custom` needs `--force-with-lease` after a
> rebase; `master` fast-forwards with a plain push).

## Branch / remote model

| Branch | Role | Update mode |
| --- | --- | --- |
| `master` | Mirrors `upstream/master`. No fork work here. | `git merge --ff-only upstream/master` |
| `custom` | Our patches; the working/dev branch and the GitHub default branch. | rebased onto `master` each sync |

`origin` = `git@github.com:ShiroiKuma0/shiroikuma-emacs.git` (push). `upstream` =
`https://github.com/emacs-mirror/emacs.git` (fetch only; push URL `DISABLED`).

## Steps

1. **Check whether upstream has moved** (run `git status` unsandboxed; the tree must be clean):
   ```bash
   git fetch upstream
   if git merge-base --is-ancestor upstream/master master; then
     echo ">>> already current: master is at or above upstream/master"
   else
     old=$(git rev-parse master)        # capture BEFORE any fast-forward — the table needs it
     echo ">>> upstream moved: $(git rev-list --count master..upstream/master) new commit(s)"
     git show master:configure.ac          | sed -n 's/^AC_INIT(\[GNU Emacs\], \[\([^]]*\)\].*/\1/p'
     git show upstream/master:configure.ac | sed -n 's/^AC_INIT(\[GNU Emacs\], \[\([^]]*\)\].*/\1/p'
     git show master:java/AndroidManifest.xml.in          | sed -n 's/^Version-code: *\([0-9]*\).*/\1/p'
     git show upstream/master:java/AndroidManifest.xml.in | sed -n 's/^Version-code: *\([0-9]*\).*/\1/p'
   fi
   ```
   "New version" = `upstream/master` is **not** an ancestor of our `master`. Report the old/new
   `AC_INIT` version, the old/new `Version-code:` base and the commit count. If nothing is newer,
   stop and report "already current" with the current pin.

2. **⛔ PROCEED GATE — present the upstream changes as a table, then STOP.** 白い熊's standing
   requirement: **before** anything is rebased, show what the new upstream commits actually bring.

   Emacs `master` is a firehose (hundreds of commits a month, most of them Lisp, docs and
   translations that touch nothing we build differently), so the table is **restricted to the
   Android-relevant paths**, and everything else is **summarised by count**:
   ```bash
   android='java/ src/android src/sfnt lisp/term/android-win.el admin/download-android-deps.sh configure.ac m4/ndk-build.m4 cross/ exec/'
   git log --oneline --no-merges $old..upstream/master -- $android          # what really landed for us
   git log --merges --format='%s' $old..upstream/master -- $android          # merged branches (rare on Emacs)
   git diff --stat $old..upstream/master -- $android                         # where the weight is
   git diff $old..upstream/master -- etc/NEWS | grep -n -i -A3 'android'     # NEWS entries about Android
   git diff $old..upstream/master -- java/Makefile.in java/AndroidManifest.xml.in   # OUR patch sites
   git rev-list --count $old..upstream/master                                 # total, for the summary row
   git log --format='%s' $old..upstream/master | grep -c -i -E 'translat|update from gnulib|^; \*|typo'   # noise
   ```
   Also check whether `admin/download-android-deps.sh` changed a tarball hash (→ `emacs_deps/`
   must be refetched: `rm -rf emacs_deps` and the next build downloads again) and whether
   `configure.ac`'s Android section moved any option we pass in `build-fork.sh`.

   Present a **descriptive markdown table** — one row per change, in plain language, not raw
   commit subjects:

   | Area | Change | What it means for us |
   | --- | --- | --- |
   | Java (`java/`) | … | … |
   | C port (`src/android*`, `src/sfnt*`) | … | … |
   | Lisp (`android-win.el`) | … | … |
   | Build (`configure.ac`, `java/Makefile.in`, deps script, `cross/`, `exec/`) | … | … |
   | NEWS (Android entries) | … | … |
   | Everything else | `N` commits: Lisp/doc/translation/gnulib — none touch the Android port | — |

   **Flag every row touching a file our patches own** — they are the likely conflict sites:
   `java/Makefile.in` (the `# --- shiroikuma fork ---` block, the two aapt packaging calls, the
   `EmacsConfig` generator, the `$(APK_NAME)` rule), `java/AndroidManifest.xml.in` (labels, icons,
   the DocumentsProvider authority, `Version-code:`), `.gitignore`, `CLAUDE.md`, and after Phase 3/4
   the Java files in the de-branding patch list (`EmacsApplication`, `EmacsNoninteractive`,
   `EmacsDesktopNotification`, `EmacsDocumentsProvider`, `EmacsService`, `EmacsWindowManager`) and
   `java/org/gnu/emacs/shiroikuma/`. Flag as well any commit that changes **`AC_INIT`** or the
   **`Version-code:`** trailer — that is the one event that resets `BUILD_NUMBER` (step 5).

   Also state the stack size (`git rev-list --count master..custom`) and the plan.

   **Then stop and wait for 白い熊's explicit go-ahead.** Do not move `master`, do not rebase, do not
   build until they say proceed. If they decline, nothing has been touched.

3. **Fast-forward `master`** (mirror; no fork work lives here) and take a safety branch:
   ```bash
   git checkout master
   git merge --ff-only upstream/master
   git branch custom-pre-$(date +%Y-%m-%d) custom      # the pre-rebase state, kept until "Push" lands
   ```
   Do **not** push `master` yet — every push waits for "Push" (step 8).

4. **Rebase `custom`:**
   ```bash
   git checkout custom
   git rebase master
   ```
   Resolve conflicts so **all** our customizations survive (table below). Reconcile, don't drop: if
   upstream restructured a file we patch, port our change to the new structure rather than forcing
   the old diff. Keep **upstream's** literals — `AC_INIT`, the manifest's `versionCode="30"` and
   `Version-code:` trailer — our layer reads them and never edits them. **If conflicts are
   significant, stop and plan with 白い熊** before continuing. If the rebase goes irrecoverable,
   `git rebase --abort` (only `master` has moved, safely).

5. **Do NOT reset `BUILD_NUMBER`** — unless the `Version-code:` base itself moved. `versionCode =
   <Version-code> + N` is all an installer compares; the fresh date/sha in `versionName` is
   cosmetic, so resetting `N` on an ordinary sync would send `versionCode` *backwards* and make
   the sync a downgrade. `build-fork.sh` enforces this against `LAST_BUILT_VERSION_CODE` in
   `fork.properties`. Only when upstream bumped `AC_INIT` (and with it the trailer — a new Emacs
   major version, once a year or so) set `BUILD_NUMBER=1`; the new base's codes all exceed the old
   line's.

6. **Verify our customizations are intact after the rebase:**

   | What | Expected | Where |
   | --- | --- | --- |
   | Fork make block | `# --- shiroikuma fork ---` … `# --- end shiroikuma fork ---` with `FORK_APPLICATION_ID ?= shiroikuma.emacs`, `FORK_AAPT_ARGS`, `FORK_*PASS`, `SIGN_EMACS`/`SIGN_EMACS_V2` | `java/Makefile.in` |
   | aapt packaging | `$(FORK_AAPT_ARGS)` on **both** `$(AAPT) p … -F $@` calls, **not** on the `$(RESOURCE_FILE)` (R.java) rule | `java/Makefile.in` |
   | Java glob | `$(wildcard $(srcdir)/org/gnu/emacs/shiroikuma/*.java)` in `JAVA_FILES` | `java/Makefile.in` |
   | `EmacsConfig.APPLICATION_ID` | the extra line in the `cf-stamp-1` echo | `java/Makefile.in` |
   | Signing rule | `$(APK_NAME): emacs.apk-in $(FORK_KEYSTORE)` + `"$(FORK_KEYSTORE_ALIAS)"` | `java/Makefile.in` |
   | Build script + counter | `build-fork.sh` (executable), `fork.properties` with `BUILD_NUMBER` + `LAST_BUILT_VERSION_CODE` | repo root |
   | Ignore rules | the `### shiroikuma-emacs fork ###` block: `!/.claude/`, `/keystore.properties`, `/*.jks`, `/emacs_deps/`, `/.scratch/`, `shiroikuma/*.class` | `.gitignore` |
   | Committed agent files | `CLAUDE.md` (ours, not upstream's `@AGENTS.md` pointer), `.claude/skills/` tracked; only `settings.local.json` ignored | `git ls-files CLAUDE.md .claude` |
   | Label / icon / links / UI page (Phases 2–4, once landed) | `白い熊 GNU Emacs`, `shiroikuma_icon.*`, our GitHub links, `java/org/gnu/emacs/shiroikuma/` | `java/AndroidManifest.xml.in`, `java/res/`, the patch list |
   | Sample keystore file | `keystore.properties_sample` tracked | repo root |

   **Rebase grep guard** — every site that must follow the installed id rather than the Java
   namespace (`--rename-manifest-package` rewrites only `package=` and relative class names), and
   the fork layer as a whole:
   ```bash
   grep -n '"org\.gnu\.emacs\|getShortClassName\|getIdentifier' java/org/gnu/emacs/*.java
   git diff master..custom --stat
   ```
   Before Phase 3 the grep lists upstream's literals (informational); after it, every hit must be
   either `EmacsConfig.APPLICATION_ID` or a documented exception — a **new** hit means upstream
   added a hard-coded package name that needs porting. `git diff --stat` must show only our files
   (`java/Makefile.in`, `.gitignore`, `CLAUDE.md`, `.claude/skills/`, `build-fork.sh`,
   `fork.properties`, `keystore.properties_sample`, plus the Phase 2–4 additions).

   Sanity check the make block still parses: `make -n -C java all FORK_VERSION_CODE=1 | head`
   (after a configure) or the standalone test in the build skill.

7. **Build the next `+NNN`** via the **build-apk** skill (`./build-fork.sh`, in the background,
   ~6–9 minutes; `--reconfigure` if `configure.ac` or the deps changed), then deliver it via
   the global **`/after-build`** skill (no transfer prompt). Its versionName carries the new pin.

8. **Stop.** Let 白い熊 test. On their explicit **"Push"**, and only then:
   ```bash
   git push origin master                        # ff, safe
   git push --force-with-lease origin custom     # rebased history
   git branch -D custom-pre-<date>               # the safety branch, once the push has landed
   ```
   Then update `CHANGELOG.md`'s pending section with an "Upstream since <old sha8>" paragraph
   distilled from the step-2 table (the global `/publish-version` skill publishes it).

## Notes

- Keep our changes a **small, legible layer** on top of upstream — prefer rebasing (linear history)
  over merging, so the customization set stays easy to audit and replay.
- `etc/NEWS` is upstream's; never edit it. Our changelog is the root `CHANGELOG.md`.
- The stale `~/git/emacs` is a reference clone — never sync or build there.
- `emacs_deps/` survives a rebase (gitignored); refetch only when `admin/download-android-deps.sh`
  changed a hash or version.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of
the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
