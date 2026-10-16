#!/usr/bin/env bash
# Keep the PAA queue poller alive with no one on campus: (re)start it in tmux "paa-queue" if the session is gone
# (crash, reboot) or hung (no completed loop for 30 min — the 2026-10-13 network hang). If new tools crash-loop
# (3 restarts in 30 min), go back to the last commit that completed a loop ($ROOT/tools.good) and remember the bad
# one ($ROOT/tools.bad, skipped by self_update).
# Install once on the DGX (user crontab, no sudo; HOME passed explicitly because the account has no real home):
#   (crontab -l 2>/dev/null; echo "*/10 * * * * HOME=/raid/workspace/$USER bash /raid/workspace/$USER/paa/PAA/scripts/dgx/watchdog.sh"; \
#    echo "@reboot HOME=/raid/workspace/$USER bash /raid/workspace/$USER/paa/PAA/scripts/dgx/watchdog.sh") | crontab -
ROOT="${PAA_HOME:-$HOME/paa}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$(cd "$HERE/../.." && pwd)"
LOG="$ROOT/watchdog.log"
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"

if tmux has-session -t paa-queue 2>/dev/null; then
  alive=$(stat -c %Y "$ROOT/poller.alive" 2>/dev/null || echo 0)
  [ "$alive" = 0 ] && exit 0                                   # older poller without the heartbeat file
  [ $(( $(date +%s) - alive )) -lt 1800 ] && exit 0
  tmux kill-session -t paa-queue
  echo "$(date '+%F %T') watchdog: poller hung (no loop for 30 min), killed" >> "$LOG"
fi

# crash loop on new tools? -> back to the last good commit
recent=$(awk -v t="$(date -d '-30 min' '+%F %T')" '$0 >= t && /restarted paa poller/' "$LOG" 2>/dev/null | wc -l)
head=$(git -C "$TOOLS" rev-parse HEAD 2>/dev/null); good=$(cat "$ROOT/tools.good" 2>/dev/null)
if [ "$recent" -ge 3 ] && [ -n "$good" ] && [ "$head" != "$good" ]; then
  echo "$head" >> "$ROOT/tools.bad"
  git -C "$TOOLS" checkout -q --detach "$good" \
    && echo "$(date '+%F %T') watchdog: tools ${head:0:7} crash-looped; rolled back to ${good:0:7}" >> "$LOG"
fi

tmux new-session -d -s paa-queue "export HOME=$HOME; bash $HERE/poll.sh >> $ROOT/queue.log 2>&1"
echo "$(date '+%F %T') watchdog: restarted paa poller" >> "$LOG"
