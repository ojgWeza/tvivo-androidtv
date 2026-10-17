#!/bin/bash
# Runs UX slices 2-4 sequentially on codex luna/high; stops on first failure.
cd /d/HCode/Tvivo || exit 1
for s in 2 3 4; do
  codex exec -m gpt-6-luna -c model_reasoning_effort=high --sandbox danger-full-access -C D:/HCode/Tvivo "Read and carry out D:/HCode/Tvivo/.tmp-codex-android-ux-s${s}-handoff.md exactly." < /dev/null > .tmp-codex-exec-ux-s${s}.log 2>&1 || { echo "slice $s failed"; exit 1; }
  [ -f .codex-android-ux-s${s}-report.md ] || { echo "slice $s no report"; exit 1; }
done
echo chain done
