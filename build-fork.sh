#!/usr/bin/env bash
# Build the signed release APK of shiroikuma-emacs (白い熊 GNU Emacs — GNU Emacs
# master for Android, installed as shiroikuma.emacs beside the stock org.gnu.emacs),
# copy it to
#   ~/tmp/shiroikuma-emacs_<versionName>_arm64-v8a.apk
# and bump BUILD_NUMBER in fork.properties.
#
# This is NOT a Gradle project: upstream builds the APK with java/Makefile.in
# (aapt / javac / d8 / jarsigner / apksigner from the SDK build-tools) after
# ./configure --with-android=…  The fork hunk in java/Makefile.in reads the
# FORK_* variables this script sets; unset, every one of them reproduces upstream.
#
# Versioning (global git-versioning skill; upstream's literals are READ, never edited):
#   versionName = <AC_INIT version>+<base commit date>.<HH-MM>.g<sha8>+<NNN>
#                 pin = `git merge-base HEAD master`, committer time in UTC
#   versionCode = <"Version-code:" trailer of java/AndroidManifest.xml.in> + BUILD_NUMBER
#
# Options:
#   --no-deliver   build only: no copy to ~/tmp, no BUILD_NUMBER bump (toolchain proof)
#   --reconfigure  re-run ./configure even though config.status exists
#
# Measured 2026-09-13 on the 24-core build host: one-time deps download ≈ 13 min
# (SourceForge, 20–150 KB/s), configure ≈ 2 min, full make ≈ 6 min, warm no-op ≈ 35 s.
# Run it in the background and poll all the same — a cold run is still ~20 min.
set -euo pipefail
cd "$(dirname "$0")"

deliver=yes
reconfigure=no
for arg in "$@"; do
  case "$arg" in
    --no-deliver)  deliver=no ;;
    --reconfigure) reconfigure=yes ;;
    *) echo "build-fork.sh: unknown option '$arg'" >&2; exit 2 ;;
  esac
done

cyan()   { printf '\033[1;36m>>> %s\033[0m\n' "$*"; }
yellow() { printf '\033[1;33m>>> %s\033[0m\n' "$*"; }
die()    { printf '\033[1;31m>>> %s\033[0m\n' "$*" >&2; exit 1; }

# --- Toolchain (this machine) -----------------------------------------------
# JDK 11 ONLY: configure.ac passes `--release 7` to javac, which JDK 21 rejects.
export JAVA_HOME=/usr/lib/jvm/zulu11
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/android-sdk"
BT="$SDK/build-tools/37.0.0"
NDK="$SDK/ndk/27.3.13750724"
ANDROID_JAR="$SDK/platforms/android-37.1/android.jar"
# minSdk 29 (the "-29-" SourceForge variant): the API level in the compiler name
# becomes ANDROID_MIN_SDK and so the APK name, emacs-<ver>-29-arm64-v8a.apk.
ANDROID_CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
ABI=arm64-v8a

for f in "$JAVA_HOME/bin/javac" "$JAVA_HOME/bin/jarsigner" "$BT/aapt" "$BT/apksigner" \
         "$BT/d8" "$BT/zipalign" "$ANDROID_JAR" "$ANDROID_CC"; do
  [ -e "$f" ] || die "toolchain file missing: $f"
done
for t in autoconf makeinfo tic m4 curl git shasum; do
  command -v "$t" >/dev/null || die "host tool missing: $t"
done

# --- Signing (keystore.properties, gitignored; see keystore.properties_sample) --
[ -f keystore.properties ] \
  || die "keystore.properties is missing — copy keystore.properties_sample and fill it in"
prop() { sed -n "s/^$1=//p" keystore.properties | head -1; }
export FORK_KEYSTORE="$(prop storeFile)"
export FORK_KEYSTORE_ALIAS="$(prop keyAlias)"
export FORK_KEYSTORE_PASS="$(prop storePassword)"
export FORK_KEY_PASS="$(prop keyPassword)"
[ -n "$FORK_KEYSTORE" ] && [ -n "$FORK_KEYSTORE_ALIAS" ] && [ -n "$FORK_KEYSTORE_PASS" ] \
  || die "keystore.properties must define storeFile, keyAlias and storePassword"
[ -f "$FORK_KEYSTORE" ] || die "keystore not found: $FORK_KEYSTORE"
[ -n "$FORK_KEY_PASS" ] || export FORK_KEY_PASS="$FORK_KEYSTORE_PASS"

# --- Version -------------------------------------------------------------------
BUILD_NUMBER=$(sed -n 's/^BUILD_NUMBER=//p' fork.properties | head -1)
LAST_BUILT=$(sed -n 's/^LAST_BUILT_VERSION_CODE=//p' fork.properties | head -1)
[ -n "$BUILD_NUMBER" ] || die "fork.properties has no BUILD_NUMBER"
: "${LAST_BUILT:=0}"

# Upstream's version, from AC_INIT — never hand-edited here.
VERSION=$(sed -n 's/^AC_INIT(\[GNU Emacs\], \[\([^]]*\)\].*/\1/p' configure.ac)
[ -n "$VERSION" ] || die "could not read the AC_INIT version from configure.ac"

# Upstream's reserved version-code slot for this version (F-Droid trailer comment);
# the manifest's android:versionCode="30" is a placeholder upstream never moves.
BASE_CODE=$(sed -n 's/^Version-code: *\([0-9]*\).*/\1/p' java/AndroidManifest.xml.in | head -1)
if [ -z "$BASE_CODE" ]; then
  # admin/admin.el's formula: %02d%02d%02d000 of major.minor.patch.
  IFS=. read -r vmaj vmin vpat <<<"$VERSION"
  BASE_CODE=$(printf '%02d%02d%02d000' "${vmaj:-0}" "${vmin:-0}" "${vpat:-0}")
  yellow "no Version-code: trailer in java/AndroidManifest.xml.in; computed $BASE_CODE from $VERSION"
fi
VERSION_CODE=$((BASE_CODE + BUILD_NUMBER))

# The upstream-base pin (global git-versioning skill): merge-base of HEAD and the
# mirror branch, its committer time in UTC.  Degrades gracefully — a missing master
# drops the pin, a failed timestamp lookup keeps the bare sha; never fails the build.
base=$(git merge-base HEAD master 2>/dev/null | cut -c1-8 || true)
pin=""
if [ ${#base} -eq 8 ]; then
  stamp=$(TZ=UTC git show -s --format=%cd --date=format-local:%Y-%m-%d.%H-%M "$base" 2>/dev/null || true)
  if [ ${#stamp} -eq 16 ]; then pin="+$stamp.g$base"; else pin="+g$base"; fi
fi
VERSION_NAME="${VERSION}${pin}+$(printf '%03d' "$BUILD_NUMBER")"

if [ "$VERSION_CODE" -le "$LAST_BUILT" ]; then
  die "versionCode $VERSION_CODE would not exceed the last delivered $LAST_BUILT — an installer reads that as a downgrade. Raise BUILD_NUMBER in fork.properties; reset it to 1 only when the Version-code base itself moves."
fi

cyan "versionName $VERSION_NAME"
cyan "versionCode $VERSION_CODE  (base $BASE_CODE + BUILD_NUMBER $BUILD_NUMBER)"

# --- Third-party dependencies (network, ~150 MB, once) -------------------------
# Upstream's script downloads its own ndk-build ports of every optional library and
# writes emacs_deps/search-path.txt with ABSOLUTE paths (it is `sh`-shebanged but
# uses `==`, so run it with bash).  emacs_deps/ is gitignored.
if [ ! -f emacs_deps/search-path.txt ]; then
  cyan "downloading Android dependencies into emacs_deps/"
  mkdir -p emacs_deps
  (cd emacs_deps && bash ../admin/download-android-deps.sh 64)
fi

# sqlite3: the deps script clones AOSP sqlite but never applies the patch java/INSTALL
# ("PATCH FOR SQLITE3") requires — the static module must export its include dir, and
# Bionic must not be told it has posix_fallocate.  Without it configure finds the module,
# fails "sqlite3.h file not found" and silently drops --with-sqlite3 (found 2026-09-13).
sq=emacs_deps/sqlite/dist
if [ -f "$sq/Android.mk" ] && ! grep -q '^LOCAL_EXPORT_C_INCLUDES' "$sq/Android.mk"; then
  cyan "applying java/INSTALL's sqlite3 patch to $sq"
  sed -i '0,/^LOCAL_MODULE:= libsqlite_static_minimal$/s//LOCAL_EXPORT_C_INCLUDES += $(LOCAL_PATH)\n&/' "$sq/Android.mk"
  sed -i 's|^# define HAVE_POSIX_FALLOCATE 1$|/* # define HAVE_POSIX_FALLOCATE 1 */|' "$sq/sqlite3.c"
fi

# --- configure -------------------------------------------------------------------
# `autogen.sh autoconf` only: the bare `./autogen.sh` also installs upstream's
# commit-msg / pre-commit hooks into .git/hooks, which would police our fork commits.
[ -x configure ] || ./autogen.sh autoconf

if [ ! -f config.status ] || [ "$reconfigure" = yes ]; then
  cyan "configuring"
  ./configure --with-android="$ANDROID_JAR" \
    ANDROID_CC="$ANDROID_CC" \
    SDK_BUILD_TOOLS="$BT" \
    JAVAC="$JAVA_HOME/bin/javac" \
    JARSIGNER="$JAVA_HOME/bin/jarsigner" \
    --with-shared-user-id=com.termux \
    --without-android-debug \
    --with-native-compilation=no \
    --with-gif --with-gnutls --with-harfbuzz --with-jpeg --with-png --with-selinux \
    --with-sqlite3 --with-tiff --with-tree-sitter --with-webp --with-xml2 \
    --with-ndk-path="$(cat emacs_deps/search-path.txt)"
fi

# --- make ------------------------------------------------------------------------
start_stamp=$(mktemp)
make all -j"$(nproc)" \
  FORK_VERSION_NAME="$VERSION_NAME" \
  FORK_VERSION_CODE="$VERSION_CODE"

# The APK must be one THIS run produced — a failed make leaves the previous one in place.
OUT=$(find java -maxdepth 1 -name "emacs-${VERSION}-*-${ABI}.apk" -newer "$start_stamp" | head -1)
rm -f "$start_stamp"
[ -n "$OUT" ] || die "make finished but no fresh java/emacs-${VERSION}-*-${ABI}.apk was produced"

cyan "built $OUT"
# (badging never prints sharedUserId; the manifest dump does.)
"$BT/aapt" dump badging "$OUT" | grep -E "^package:" || true
"$BT/aapt" dump xmltree "$OUT" AndroidManifest.xml | grep -o 'android:sharedUserId[^ ]*="[^"]*"' || true
"$BT/apksigner" verify --print-certs "$OUT" | grep -i 'SHA-256' || true

if [ "$deliver" = no ]; then
  yellow "--no-deliver: left in $OUT; ~/tmp untouched, BUILD_NUMBER stays $BUILD_NUMBER"
  exit 0
fi

# --- deliver ---------------------------------------------------------------------
mkdir -p "$HOME/tmp"
APK="$HOME/tmp/shiroikuma-emacs_${VERSION_NAME}_${ABI}.apk"
[ ! -e "$APK" ] || die "$APK already exists — never overwrite a build; raise BUILD_NUMBER"
cp -n "$OUT" "$APK"

sed -i "s/^BUILD_NUMBER=.*/BUILD_NUMBER=$((BUILD_NUMBER + 1))/" fork.properties
if grep -q '^LAST_BUILT_VERSION_CODE=' fork.properties; then
  sed -i "s/^LAST_BUILT_VERSION_CODE=.*/LAST_BUILT_VERSION_CODE=$VERSION_CODE/" fork.properties
else
  printf '\n# Highest versionCode this repo has ever delivered; build-fork.sh refuses to go at or below it.\nLAST_BUILT_VERSION_CODE=%s\n' "$VERSION_CODE" >> fork.properties
fi

cyan "$APK"
cyan "versionCode $VERSION_CODE"
cyan "BUILD_NUMBER bumped to $((BUILD_NUMBER + 1))"
