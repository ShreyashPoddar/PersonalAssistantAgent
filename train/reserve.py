"""Keeps one DGX card reserved for PAA (owner, 2026-11-28: "hoard these two GPUs always as they are ours").

    python train/reserve.py --out <dir>      # launched by the DGX poller (step: reserve) when PAA has < 2 cards

Claims all but 1.5 GiB of the card's memory and then only waits. The log line "holding the card for PAA" makes the
DGX policy treat it as a hold: kept while PAA is within its guaranteed 2 cards, and released (the poller ends it)
the moment one of PAA's own GPU jobs is ready and needs the card. It does no compute.
"""
import argparse
import signal
import sys
import time
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    Path(a.out).mkdir(parents=True, exist_ok=True)
    signal.signal(signal.SIGUSR1, lambda *_: sys.exit(0))   # asked for the card: give it back at once
    import torch
    if not torch.cuda.is_available():
        sys.exit("no GPU")
    free, _ = torch.cuda.mem_get_info()
    keep = torch.empty(max(0, int(free - 1.5 * 2**30)), dtype=torch.uint8, device="cuda")
    print(f"holding the card for PAA ({keep.numel() / 2**30:.1f} GiB) until PAA's next job needs it", flush=True)
    while True:
        time.sleep(300)


if __name__ == "__main__":
    main()
