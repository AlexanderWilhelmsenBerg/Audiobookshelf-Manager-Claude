"""Arm one bounded, phone-resident Perfetto recording for the debug car playback suite."""

import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import subprocess
import sys
import time


SESSION = "bookwave-car-lab"
DIRECTORY = "/data/misc/perfetto-traces"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="specific phone from adb devices")
    parser.add_argument("--adb", default="adb")
    actions = parser.add_subparsers(dest="action", required=True)
    arm = actions.add_parser("arm", help="arm over USB, then unplug and tap a test case")
    arm.add_argument("--seconds", type=int, default=300, help="capture window or incident wait (5–600 seconds)")
    arm.add_argument("--scheduler", action="store_true", help="also record system CPU scheduling for stall analysis")
    mode = arm.add_mutually_exclusive_group()
    mode.add_argument("--start-now", action="store_true", help="start over USB, including on older BookWave builds")
    mode.add_argument("--on-incident", action="store_true", help="save rolling prehistory and five seconds after a fault")
    actions.add_parser("stop", help="flush this capture early after reconnecting")
    pull = actions.add_parser("pull", help="retrieve a completed capture")
    pull.add_argument("file", help="filename printed by arm")
    pull.add_argument("--output", type=Path, default=Path.cwd())
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]

    def shell(*command, check=True, config=None):
        return subprocess.run(
            [*adb, "shell", *command], input=config, text=True, capture_output=True, check=check, timeout=35,
        )

    def recording():
        return SESSION in shell("perfetto", "--query").stdout

    if args.action == "stop":
        if shell("perfetto", f"--is_detached={SESSION}", check=False).returncode == 0:
            shell("perfetto", f"--attach={SESSION}", "--stop")
        elif recording():
            shell("trigger_perfetto", "org.homebord.bookwave.debug.car-lab-incident")
            for _ in range(10):
                time.sleep(1)
                if not recording():
                    break
            else:
                sys.exit("the incident capture has not finished flushing")
        else:
            sys.exit("no active BookWave recording")
        print("Recording stopped and flushed.")
        return
    if args.action == "pull":
        if not re.fullmatch(r"bookwave-car-\d{8}T\d{12}Z\.perfetto-trace", args.file):
            parser.error("use the filename printed by arm")
        if recording():
            parser.error("wait for the recording to finish or run stop before pulling")
        destination = args.output / args.file
        # Reserve a new local file and retain it only after a successful, nonempty pull.
        with destination.open("xb"):
            pass
        try:
            subprocess.run([*adb, "pull", f"{DIRECTORY}/{args.file}", os.fspath(destination)], check=True)
            if destination.stat().st_size == 0:
                raise ValueError("empty trace: the trigger may have expired")
        except (OSError, ValueError, subprocess.CalledProcessError):
            destination.unlink()
            raise
        print(destination)
        return

    if not 5 <= args.seconds <= 600:
        parser.error("--seconds must be between 5 and 600")
    sources = shell("perfetto", "--query").stdout
    if SESSION in sources:
        parser.error("a BookWave capture is already armed or recording")
    bluetooth = "android.bluetooth_tracing" in sources
    if not bluetooth:
        print("Bluetooth system producer unavailable; use the Bluetooth events in BookWave JSON.", file=sys.stderr)
    duration = args.seconds * 1000
    timing = f"duration_ms: {duration}" if args.start_now else f"""
        trigger_config {{
          trigger_mode: START_TRACING
          trigger_timeout_ms: 900000
          triggers {{ name: "org.homebord.bookwave.debug.car-lab" stop_delay_ms: {duration} }}
        }}"""
    if args.on_incident:
        timing = f"""
            trigger_config {{
              trigger_mode: STOP_TRACING
              trigger_timeout_ms: {duration}
              triggers {{ name: "org.homebord.bookwave.debug.car-lab-incident" stop_delay_ms: 5000 }}
            }}"""
    sched = '''
        ftrace_events: "sched/sched_switch"
        ftrace_events: "sched/sched_waking"
        compact_sched { enabled: true }
    ''' if args.scheduler else ""
    streaming = "" if args.on_incident else """
        write_into_file: true
        file_write_period_ms: 1000
        max_file_size_bytes: 201326592
    """
    config = f"""
        buffers {{ size_kb: 16384 fill_policy: RING_BUFFER }}
        {streaming}
        incremental_state_config {{ clear_period_ms: 5000 }}
        unique_session_name: "{SESSION}"
        {timing}
        data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
          atrace_categories: "audio"
          atrace_apps: "org.homebord.bookwave.debug"
          {sched}
        }} }} }}
        data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ scan_all_processes_on_start: true }} }} }}
    """
    if bluetooth:
        config += 'data_sources { config { name: "android.bluetooth_tracing" } }'
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    filename = f"bookwave-car-{stamp}.perfetto-trace"
    launch = "--background-wait" if args.on_incident else f"--detach={SESSION}"
    shell("perfetto", launch, "--txt", "-c", "-", "-o", f"{DIRECTORY}/{filename}", config=config)
    if not recording():
        sys.exit("Perfetto did not report an active BookWave recording")
    if args.on_incident:
        print(f"Watching for an incident for {args.seconds} seconds. Tap a test case now. The tail is five seconds.")
    else:
        print("Recording now." if args.start_now else "Armed. Tap a test case within 15 minutes.")
        print(f"Capture window: {args.seconds} seconds, up to 192 MiB.")
    print("You may unplug USB.")
    print(f"After capture, reconnect and use pull with filename {filename}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.stderr or f"ADB command failed (exit {error.returncode})")
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        sys.exit(str(error))
