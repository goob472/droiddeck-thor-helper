#!/system/bin/sh
# Runs the write scripts that WriteScriptTest generates (app/build/device-scripts) on an emulator or
# rooted device, as root and as the helper app's own user, and checks bytes, owner and SELinux label.
#
#   ./gradlew testReleaseUnitTest --tests '*WriteScriptTest*'
#   adb push app/build/device-scripts /data/local/tmp/ddh && adb push tools/check_write_scripts.sh /data/local/tmp/ddh/
#   adb shell su 0 sh /data/local/tmp/ddh/check_write_scripts.sh
D=/data/local/tmp/ddh
APP=/data/data/com.goob.ddhelper
T=$APP/files/ddh-write-test
UID_APP=$(stat -c %u $APP)
WANT_OWN=$(stat -c %U $APP/files)
WANT_LBL=$(stat -c %C $APP/files)
fail=0

check() { # name path sum who
  got=$(sha256sum "$2" 2>/dev/null | cut -d' ' -f1)
  own=$(stat -c %U "$2" 2>/dev/null); lbl=$(stat -c %C "$2" 2>/dev/null)
  bad=""
  [ "$got" = "$3" ] || bad="$bad bytes"
  [ "$own" = "$WANT_OWN" ] || bad="$bad owner=$own"
  [ "$lbl" = "$WANT_LBL" ] || bad="$bad label=$lbl"
  # Every folder the script created must also carry the app's owner and label.
  p=$(dirname "$2")
  while [ "$p" != "$APP/files" ]; do
    [ "$(stat -c %U "$p")" = "$WANT_OWN" ] || bad="$bad dir-owner:$p"
    [ "$(stat -c %C "$p")" = "$WANT_LBL" ] || bad="$bad dir-label:$p=$(stat -c %C "$p")"
    p=$(dirname "$p")
  done
  left=$(find $T -name '*.ddh-tmp' -o -name 'sh*.tmp' | wc -l)
  [ "$left" = 0 ] || bad="$bad leftovers=$left"
  if [ -z "$bad" ]; then echo "PASS $1 as uid $4"; else echo "FAIL $1 as uid $4:$bad"; fail=1; fi
}

while IFS="$(printf '\t')" read -r name path sum; do
  for who in 0 $UID_APP; do
    rm -rf $T
    [ "$name" = overwrite ] && sh $D/new-nested.sh >/dev/null 2>&1
    su $who sh $D/$name.sh || echo "  script exited $?"
    check "$name" "$path" "$sum" "$who"
  done
done < $D/expected.tsv
rm -rf $T
exit $fail
