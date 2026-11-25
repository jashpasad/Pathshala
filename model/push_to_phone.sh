#!/usr/bin/env bash
# Copy the model into PathShala's app-specific storage. No storage permission is needed
# and the app itself never touches the network.  Usage: push_to_phone.sh <model.pte> <tokenizer.model>
set -euo pipefail
PTE=${1:?model.pte}; TOK=${2:?tokenizer.model}
DIR=/sdcard/Android/data/ai.pathshala.app/files/models
adb shell mkdir -p "$DIR"
adb push "$PTE" "$DIR/"
adb push "$TOK" "$DIR/tokenizer.model"
adb shell ls -l "$DIR"
echo "Open PathShala -> Engine -> 'Scan & load'."
