#!/system/bin/sh
# Qualification fixture v1. No touch is sent until capabilities and the observed display are admitted.
set -u
fail() { printf 'COMPOSEDOC_TAP_ERROR=%s\n' "$1"; exit 1; }
command -v timeout >/dev/null 2>&1 || fail timeout-unavailable
timeout 1 /system/bin/sh -c 'exit 0' || fail timeout-unsupported
help=$(/system/bin/input 2>&1)
case "$help" in *"tap <x> <y>"*) ;; *) fail input-tap-grammar-unavailable ;; esac
case "$help" in *"-d"*) mode=display ;; *) mode=default ;; esac
if [ "${1-}" = --probe ]; then
    printf 'COMPOSEDOC_TAP_CAPABILITY=%s\n' "$mode"
    exit 0
fi
[ "$#" -eq 3 ] || fail argument-count
for value in "$1" "$2"; do
    printf '%s\n' "$value" | grep -Eq '^[0-9]+([.][0-9]+)?$' || fail coordinate
done
printf '%s\n' "$3" | grep -Eq '^[0-9]+$' || fail display
if [ "$mode" = display ]; then
    timeout 5 /system/bin/input touchscreen -d "$3" tap "$1" "$2" 2>&1
    result=$?
elif [ "$3" = 0 ]; then
    # Older input supports only its default display. The caller must observe display 0; never force another.
    timeout 5 /system/bin/input touchscreen tap "$1" "$2" 2>&1
    result=$?
else
    fail nondefault-display-unsupported
fi
printf '\nCOMPOSEDOC_TAP_EXIT=%s\n' "$result"
exit "$result"
