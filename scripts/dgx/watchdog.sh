#!/usr/bin/env bash
# Keep the PAA queue poller alive with no one on campus: (re)start it in tmux "paa-queue" if it is gone.
# Install once on the DGX (user crontab, no sudo; HOME passed explicitly because the account has no real home):
#   (crontab -l 2>/dev/null; echo "*/10 * * * * HOME=/raid/workspace/$USER bash /raid/workspace/$USER/paa/PAA/scripts/dgx/watchdog.sh"; \
#    echo "@reboot HOME=/raid/workspace/$USER bash /raid/workspace/$USER/paa/PAA/scripts/dgx/watchdog.sh") | crontab -
ROOT="${PAA_HOME:-$HOME/paa}"
HERE="$(cd "$(dirname "$0")" && pwd)"
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
tmux has-session -t paa-queue 2>/dev/null && exit 0
tmux new-session -d -s paa-queue "export HOME=$HOME; bash $HERE/poll.sh >> $ROOT/queue.log 2>&1"
echo "$(date '+%F %T') watchdog: restarted paa poller" >> "$ROOT/watchdog.log"
