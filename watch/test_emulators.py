"""Drive every Pebble emulator and decode screenshots with zxing.

Usage (Windows, ozvenv python): test_emulators.py <watch dir> <out dir> <platforms,comma> demo|setup
  demo  - DEMO build: barcode, rotated barcode, QR test view must decode on each platform
  setup - normal build: the first-run "Get the phone app" QR must decode
"""
import os
import subprocess
import sys
import time

import zxingcpp
from PIL import Image

WATCH = sys.argv[1]               # Windows path of watch/
OUT = sys.argv[2]                 # where screenshots go (Windows path)
PLATFORMS = sys.argv[3].split(",")
os.makedirs(OUT, exist_ok=True)
SCRIPT = os.path.join(WATCH, "_s.sh")
WSL_WATCH = "/mnt/c" + WATCH[2:].replace("\\", "/") if WATCH[1] == ":" else WATCH
WSL_OUT = "/mnt/c" + OUT[2:].replace("\\", "/")


def sh(cmd, timeout=240):
    with open(SCRIPT, "w", newline="\n") as f:
        f.write("export PATH=/root/.local/bin:$PATH\ncd " + WSL_WATCH + "\n" + cmd + "\n")
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    r = subprocess.run(["wsl", "-d", "Ubuntu-24.04", "-u", "root", "--", "bash", WSL_WATCH + "/_s.sh"],
                       capture_output=True, text=True, timeout=timeout, env=env)
    return (r.stdout + r.stderr).strip()


def shot(p, name):
    sh(f"pebble screenshot --emulator {p} --no-open {WSL_OUT}/{name}.png >/dev/null 2>&1")
    path = os.path.join(OUT, name + ".png")
    im = Image.open(path).convert("RGB")
    res = [(r.text, str(r.format)) for r in zxingcpp.read_barcodes(im)]
    return im, res


def press(p, button="select", wait=1.5):
    sh(f"pebble emu-button click {button} --emulator {p}")
    time.sleep(wait)


def run(demo):
    sh("pebble kill >/dev/null 2>&1; pebble clean >/dev/null 2>&1; "
       + ("DEMO=1 " if demo else "") + "pebble build >/dev/null 2>&1")
    report = {}
    for p in PLATFORMS:
        for attempt in range(3):  # emulator start is flaky ("Connection refused")
            sh("pebble kill >/dev/null 2>&1")
            out = sh(f"timeout 200 pebble install --emulator {p} 2>&1 | tail -1", timeout=260)
            print(p, out)
            if "succeeded" in out:
                break
        time.sleep(4)
        if demo:
            im, res = shot(p, f"{p}_0")
            tries = 0
            while not any(f == "Code 128" for _, f in res) and tries < 3:  # open the app if needed
                press(p)
                im, res = shot(p, f"{p}_0")
                tries += 1
            out = [res]
            for i in (1, 2):
                press(p)
                im, r = shot(p, f"{p}_{i}")
                out.append(r)
            press(p)  # back to the normal view for the next run
            report[p] = out
        else:
            time.sleep(6)
            im, res = shot(p, f"{p}_setup")
            if not res:  # app not opened by install: open it, wait for the phone timeout
                press(p)
                press(p, wait=8)
                im, res = shot(p, f"{p}_setup")
            report[p] = [res]
        print(p, report[p], flush=True)
    sh("pebble kill >/dev/null 2>&1")
    return report


mode = sys.argv[4]
run(mode == "demo")
os.remove(SCRIPT)
