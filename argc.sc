#!/bin/bash
ARGS=""; for a in "$@"; do ARGS="$ARGS,$(printf '%s' "$a"|base64 -w 0)"; done; ARGS="$ARGS" exec amm "$0";
!#
val args = System.getenv("ARGS").split(",").toList.drop(1).map(a => String(java.util.Base64.getDecoder().decode(a)))

//your code...
println(args)
