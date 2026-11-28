"""Reduce BookWave exports, Android btsnoop and Perfetto to a local, time-aligned drive report."""

import argparse
import bisect
import collections
import csv
import datetime as dt
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile


SECOND = 1_000_000  # All internal timestamps are UTC microseconds.
SNOOP_EPOCH = 0x00DCDDB30F2F8000
OUTCOMES = set("Armed Transferable Requested Selected Failed TimedOut Unavailable Ineligible "
               "HeadsetRouteUnknown AlreadySelected HeadsetRouteAbsent Cancelled CallBlocked PermissionRequired "
               "Disabled TrackReturned StreamStopped StreamReturned".split())
CASES = set("Normal CaptureBlocked ShortBuffer Combined Disconnect500 Disconnect1000 ConnectTakeBackOff "
            "ConnectTakeBackOn EarlyTakeBack500 EarlyTakeBack1000".split())


def utc(value):
    if not value:
        return None
    instant = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.tzinfo is None:
        raise ValueError("BookWave timestamp needs a UTC offset")
    return round(instant.timestamp() * SECOND)


def stamp(t):
    return dt.datetime.fromtimestamp(t / SECOND, dt.timezone.utc).isoformat(timespec="milliseconds")


def ms(value):
    return round(value / 1000, 1)


def fields(details):
    if isinstance(details, dict):
        return details
    return dict(re.findall(r'\b(\w+)=([^\s,}]+)', str(details)))


def app_events(folder):
    # Keep only fixed event labels and whitelisted test settings, never raw diagnostic lines.
    events, anchors, cases, runs, drives, sync = set(), set(), {}, [], [], set()
    warnings = []

    def row(e):
        t = utc(e.get("atUtc"))
        if t is None:
            return
        name = e.get("event", "")
        f = fields(e.get("details", ""))
        if name == "car-bluetooth-window":
            anchors.add((t, "car-link"))
            sync.add((t, "hands-free"))
        elif name == "projection-change" and f.get("projection") in {"Projection", "NotConnected"}:
            anchors.add((t, "projection-on" if f["projection"] == "Projection" else "projection-off"))
        elif name == "bluetooth-event" and f.get("event") == "acl-connected":
            sync.add((t, "acl"))
        elif name == "headset-output-test" and f.get("case") in CASES:
            cases[t] = f["case"]
        elif name in {"headset-interrupted", "headset-playing", "audio-output-flush", "audio-sink-flush",
                      "player-discontinuity"}:
            events.add((t, name))
        elif name == "route-boundary" and f.get("edge") in {"RouteSafetyPause", "ProjectionDeparture", "ProjectionArrival"}:
            events.add((t, f["edge"]))
        elif name == "track-progress" and f.get("event") in {"NoOutputProgress", "ProgressReturned"}:
            events.add((t, f["event"]))
        elif name == "recovery-step" and f.get("event") in {"TargetAbsent", "TargetReturned", "Reasserted", "ProbeReady"}:
            events.add((t, f["event"]))
        elif name == "output-proof" and f.get("confirmation") == "Verified":
            events.add((t, "track-proof-verified"))
        elif name == "headset-take-back" and f.get("outcome") in OUTCOMES:
            early = f.get("stage") == "Early"
            events.add((t, ("early:" if early else "take-back:") + f["outcome"]))
            if early and f["outcome"] == "Armed":
                anchors.add((t, "armed"))
        elif name.startswith("early-take-back-") and name[16:] in OUTCOMES:
            events.add((t, "early:" + name[16:]))
            if name[16:] == "Armed":
                anchors.add((t, "armed"))

    for path in sorted(folder.glob("bookwave*.json")):
        try:
            doc = json.loads(path.read_text(encoding="utf-8-sig"))
            if not isinstance(doc, dict):
                continue
            for e in doc.get("timeline", []):
                row(e)
            for drive in doc.get("attempts", []):
                start = utc(drive.get("startedAtUtc"))
                end = utc(drive.get("endedAtUtc"))
                drives.append((start, end, drive.get("attempt")))
                for e in drive.get("events", []):
                    row(e)
                confirmed = utc(drive.get("connectedAtUtc"))
                if confirmed and not any(e.get("event") == "projection-change" for e in drive.get("events", [])):
                    anchors.add((confirmed, "connected"))
                if start and drive.get("startEvidence") == "car-bluetooth":
                    anchors.add((start, "car-link"))
                    sync.add((start, "hands-free"))
                if end and drive.get("endReason") in {"projection-disconnect", "projection-disconnected", "projection-ended"}:
                    anchors.add((end, "projection-off"))
            for run in doc.get("playbackTestSuite", {}).get("runs", []):
                start, end = utc(run.get("startUtc")), utc(run.get("endUtc") or run.get("lastObservedUtc"))
                case = fields(run.get("headsetOutputAtStart", "")).get("case")
                if start:
                    runs.append((start, end, run.get("runId"), case if case in CASES else None))
                for e in run.get("events", []):
                    row(e)
        except (ValueError, OSError, TypeError, AttributeError):
            warnings.append("An unreadable BookWave export was skipped.")
    return sorted(events), sorted(anchors), cases, runs, drives, sorted(sync), warnings


def boundaries(anchors, runs):
    windows = []
    for t, label in sorted(anchors):
        phase = "disconnect" if label == "projection-off" else "connect"
        if windows and phase == windows[-1][0]:
            previous = windows[-1][1]
            if any(old == label and abs(t - at) < SECOND for at, old in previous):
                continue
            # A physical link, app arming and projected session can be observations of one arrival.
            if phase == "connect" and t - previous[0][0] <= 30 * SECOND and label not in {k for _, k in previous}:
                previous.append((t, label))
                continue
        windows.append((phase, [(t, label)]))
    for start, end, _, _ in runs:
        if not any(start <= points[-1][0] and (end is None or points[0][0] <= end) for _, points in windows):
            windows.append(("run-start", [(start, "manual-run")]))
    return sorted(windows, key=lambda w: w[1][0][0])


class Bluetooth:
    """Classic HCI H4 only. Remember CID direction and signalling transactions, discard media payloads."""

    def __init__(self):
        self.links, self.signals, self.media, self.ranges = [], [], collections.defaultdict(list), []
        self.fragments, self.pending, self.psm = {}, {}, {}
        self.drops = 0
        self.partial = False

    def read(self, path):
        first = last = None
        with path.open("rb") as f:
            if f.read(16) != b"btsnoop\0" + struct.pack(">II", 1, 1002):
                raise ValueError("expected Android btsnoop H4")
            while header := f.read(24):
                if len(header) != 24:
                    self.partial = True
                    break
                original, size, flags, drops, raw_time = struct.unpack(">IIIIQ", header)
                if size > 1_048_576 or size > original:
                    raise ValueError("invalid btsnoop record length")
                data = f.read(size)
                if len(data) != size:
                    self.partial = True
                    break
                self.drops = max(self.drops, drops)
                t = raw_time - SNOOP_EPOCH
                first = t if first is None else first
                last = t
                if len(data) >= 7 and data[0] == 4 and data[1] in (3, 5) and data[3] == 0:
                    handle = struct.unpack_from("<H", data, 4)[0] & 0xfff
                    self.links.append((t, "connected" if data[1] == 3 else "disconnected", handle))
                    for table in (self.psm, self.pending, self.fragments):
                        for key in [k for k in table if k[0] == handle]:
                            del table[key]
                elif len(data) >= 5 and data[0] == 2:
                    self.acl(t, flags & 1, data)
        if first is not None:
            self.ranges.append((first, last))

    def acl(self, t, direction, data):
        ctl, size = struct.unpack_from("<HH", data, 1)
        if len(data) != size + 5:
            self.partial = True
            return
        handle = ctl & 0xfff
        key = handle, direction
        if (ctl >> 12) & 3 == 1:
            if key not in self.fragments:
                return
            self.fragments[key][3].extend(data[5:])
        elif size >= 4:
            length, cid = struct.unpack_from("<HH", data, 5)
            self.fragments[key] = (t, length, cid, bytearray(data[9:]))
        else:
            return
        at, length, cid, body = self.fragments[key]
        if len(body) < length:
            return
        del self.fragments[key]
        if len(body) != length:
            self.partial = True
        elif cid == 1:
            self.signalling(handle, direction, body)
        elif cid >= 64:
            protocol = self.psm.get((handle, direction, cid))
            if protocol not in (None, 0x19):
                return
            if not direction and len(body) >= 12 and body[0] >> 6 == 2 and (body[1] & 127) >= 96:
                seq, ssrc = struct.unpack_from(">H4xI", body, 2)
                self.media[handle, cid, ssrc, body[1] & 127].append((at, seq))
            elif 2 <= len(body) <= 8 and not (body[0] & 12) and body[1] in (7, 9):
                msg, tx = body[0] & 3, body[0] >> 4
                if (msg == 0 and len(body) >= 3 and not body[2] & 3 and body[2] >> 2) or (msg == 2 and len(body) == 2):
                    self.signals.append((at, handle, direction, msg, tx, body[1], protocol))

    def signalling(self, handle, direction, body):
        pos = 0
        while pos + 4 <= len(body):
            op, tx, size = struct.unpack_from("<BBH", body, pos)
            packet = body[pos + 4:pos + 4 + size]
            pos += 4 + size
            if op == 2 and len(packet) >= 4:
                self.pending[handle, direction, tx] = struct.unpack_from("<HH", packet)
            if op == 3 and len(packet) >= 8:
                dest, source, result, _ = struct.unpack_from("<4H", packet)
                request = self.pending.get((handle, 1 - direction, tx))
                if result == 0 and request and request[1] == source:
                    self.psm[handle, 1 - direction, dest] = self.psm[handle, direction, source] = request[0]

    def exchanges(self):
        commands, result = {}, []
        for t, h, direction, msg, tx, signal, protocol in sorted(set(self.signals), key=lambda r: r[0]):
            key = h, tx, signal
            if direction == 0 and msg == 0:
                commands[key] = t, protocol
            elif direction == 1 and msg == 2 and key in commands:
                start, service = commands.pop(key)
                if 0 <= t - start <= 2 * SECOND and (service == 0x19 or any(k[0] == h for k in self.media)):
                    result.append((start, t, h, "START" if signal == 7 else "SUSPEND"))
        return sorted(result)


def hci_alignment(bt, app_anchors, explicit):
    if explicit is not None:
        return explicit, "explicit", 0
    links = sorted({t for t, kind, _ in bt.links if kind == "connected"})
    scores = collections.Counter()
    for offset in range(-14 * 3600, 14 * 3600 + 1, 900):
        matched = set()
        for at, kind in app_anchors:
            target = at - offset * SECOND
            ix = bisect.bisect_right(links, target + 100_000) - 1
            if ix >= 0 and target - links[ix] <= (1 if kind == "acl" else 3) * SECOND:
                matched.add(ix)
        if matched:
            scores[offset] = len(matched)
    if scores:
        best = max(scores.values())
        offsets = [k for k in scores if scores[k] == best]
        if len(offsets) == 1:
            return offsets[0], "matched-app-and-HCI-connections", best
    return None, "unresolved; supply --hci-offset-seconds if independently known", 0


def radio_window(bt, exchanges, offset, anchor, lo, hi, request=None):
    if offset is None:
        return {"coverage": "unaligned" if bt.ranges else "missing"}
    shift = offset * SECOND
    a, start, end = anchor - shift, lo - shift, hi - shift
    coverage = coverage_of(bt.ranges, start, end)
    out = {"coverage": coverage}
    if coverage == "outside":
        return out
    # Identify the pre-existing outbound stream by activity before the car edge. Fail closed on two peers.
    before = collections.defaultdict(int)
    for (h, _, _, _), rows in bt.media.items():
        before[h] += bisect.bisect_left(rows, (a,)) - bisect.bisect_left(rows, (a - 2 * SECOND,))
    peers = [h for h, count in before.items() if count >= 8]
    if len(peers) != 1:
        out["referenceStream"] = "ambiguous" if peers else "unavailable"
        return out
    h = peers[0]
    # Raw handles can be reused after a disconnect. Stop attributing observations at that boundary.
    stop = next((t for t, kind, peer in sorted(bt.links) if peer == h and kind == "disconnected" and a <= t <= end), None)
    if stop is not None:
        end = stop
        out["referenceLinkDisconnectedMs"] = ms(stop - a)
    times = sorted({t for key, rows in bt.media.items() if key[0] == h
                    for t, _ in rows[bisect.bisect_left(rows, (start,)):bisect.bisect_left(rows, (end + 1,))]})
    out.update(referenceStream="pre-existing outbound media", packets=len(times))
    if len(times) >= 2:
        gap, left, right = max((y - x, x, y) for x, y in zip(times, times[1:]))
        out["largestPacketGap"] = {"ms": ms(gap), "fromMs": ms(left - a), "toMs": ms(right - a)}
    if request is not None:
        i = bisect.bisect_left(times, request - shift)
        if i < len(times):
            out["nextOutboundPacketAfterRequestMs"] = ms(times[i] - (request - shift))
    out["headsetSignalling"] = []
    for t, accepted, peer, action in exchanges:
        if peer != h or not start <= t <= end:
            continue
        item = {"action": action, "phoneCommandMs": ms(t - a), "headsetAcceptedMs": ms(accepted - a)}
        if action == "START":
            i = bisect.bisect_left(times, accepted)
            if i < len(times):
                item["firstOutboundMediaMs"] = ms(times[i] - a)
        out["headsetSignalling"].append(item)
    return out


def coverage_of(ranges, lo, hi):
    cursor = lo
    overlap = False
    for start, end in sorted(ranges):
        if end < lo or start > hi:
            continue
        overlap = True
        if start > cursor + 100_000:
            return "partial"
        cursor = max(cursor, end)
        if cursor >= hi:
            return "covered"
    return "partial" if overlap else "outside"


def find_processor(override):
    if override:
        return shutil.which(override) or (str(Path(override).resolve()) if Path(override).is_file() else None)
    for name in ("trace_processor_shell", "trace_processor"):
        if path := shutil.which(name):
            return path
    cache = Path.home() / ".local/share/perfetto/prebuilts"
    candidates = sorted((p for p in cache.glob("trace_processor_shell-*") if os.access(p, os.X_OK)),
                        key=lambda p: p.stat().st_mtime, reverse=True)
    return str(candidates[0]) if candidates else None


def query(processor, trace, sql):
    result = subprocess.run([processor, "query", str(trace), sql], capture_output=True, timeout=180)
    if result.returncode:
        raise ValueError("Perfetto query failed")
    return list(csv.DictReader(io.StringIO(result.stdout.decode("utf-8-sig"))))


APP_NAMES = "('org.homebord.bookwave.debug','org.homebord.bookwave')"
LAB_MARKERS = {"RouteSafetyPause", "ProjectionDeparture", "NoOutputProgress", "actual-speaker",
               "actual-projection-mix", "audio-sink-flush", "audio-output-flush", "player-discontinuity"}
MIX_EVENTS = {"beginInterval": "begin", "endInterval": "end", "refreshInterval": "refresh"}


def perfetto(processor, path, windows):
    """Read the trace's real-time snapshots before restricting expensive slice queries to drive windows."""
    clocks = query(processor, path, """
        SELECT 'clock' AS kind, ts AS a, clock_value AS b FROM clock_snapshot WHERE clock_id=1
        UNION ALL SELECT 'bounds',start_ts,end_ts FROM trace_bounds
        UNION ALL SELECT 'start',int_value,0 FROM metadata WHERE name='tracing_started_ns'
        """)
    snaps = sorted((int(r["a"]), int(r["b"]) - int(r["a"])) for r in clocks if r["kind"] == "clock")
    if not snaps:
        raise ValueError("No trace wall clock")
    def wall(t):
        i = bisect.bisect_right(snaps, (t, float("inf"))) - 1
        return (t + snaps[max(i, 0)][1]) // 1000

    bounds = next(r for r in clocks if r["kind"] == "bounds")
    first = max([int(bounds["a"])] + [int(r["a"]) for r in clocks if r["kind"] == "start" and r["a"].isdigit()])
    covered = [(wall(first), wall(int(bounds["b"])))]
    if not any(coverage_of(covered, w["lo"], w["hi"]) != "outside" for w in windows):
        return covered, []
    # Include both extremes of wall-clock drift. Convert the surviving rows with the contemporaneous snapshot.
    offsets = [offset for _, offset in snaps]
    limits = [(max(first, w["lo"] * 1000 - max(offsets)),
               min(int(bounds["b"]), (w["hi"] + 1) * 1000 - min(offsets))) for w in windows]
    spans = [(a, b) for a, b in limits if a <= b]
    if not spans:
        return covered, []
    # Merge overlapping spans before forming SQL. Long folders cannot overflow SQLite's expression depth.
    merged = []
    for a, b in sorted(spans):
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(b, merged[-1][1]))
        else:
            merged.append((a, b))
    spans_sql = ",".join(f"({a},{b})" for a, b in merged)
    pids = {r["pid"] for r in query(processor, path, f"SELECT pid FROM process WHERE name IN {APP_NAMES}")}
    rows = query(processor, path, f"""
        WITH times(a,b) AS (VALUES {spans_sql}),
        bookwave_counters(name) AS (
          SELECT DISTINCT replace(t.name,'audio.track.interval.','audio.track.nRdy.')
          FROM slice s JOIN track t ON t.id=s.track_id JOIN process p ON p.name IN {APP_NAMES}
          WHERE t.name GLOB 'audio.track.interval.*' AND instr(s.name,' pid='||p.pid||' ')>0)
        SELECT 'mix' AS kind,s.ts,0 AS dur,hex(s.name) AS name
          FROM times JOIN slice s ON s.ts BETWEEN a AND b JOIN track t ON t.id=s.track_id
          WHERE t.name GLOB 'audio.track.interval.*' AND s.name LIKE '{{ %'
        UNION ALL SELECT 'loop',s.ts,s.dur,hex(s.name)
          FROM times JOIN slice s ON s.ts BETWEEN a-5000000000 AND b AND s.ts+s.dur >= a
          JOIN thread_track tr ON tr.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
          WHERE p.name IN {APP_NAMES} AND s.name IN ('doSomeWork','drainAndFeed') AND s.dur>=50000000
        UNION ALL SELECT 'mark',s.ts,0,hex(s.name) FROM times JOIN slice s ON s.ts BETWEEN a AND b
          WHERE s.name GLOB 'BookWave/*/HeadsetRoute/*'
        UNION ALL SELECT 'empty',min(c.ts),0,'' FROM times JOIN counter c ON c.ts BETWEEN a AND b
          JOIN track t ON t.id=c.track_id JOIN bookwave_counters bc ON bc.name=t.name
          WHERE c.value=0 GROUP BY a,b,c.track_id
        """)
    events = set()
    for row in rows:
        name = bytes.fromhex(row["name"]).decode("utf-8", errors="replace")
        t, kind = wall(int(row["ts"])), row["kind"]
        if kind == "loop":
            events.add((t, "player", name, round(int(row["dur"]) / 1_000_000, 1)))
        elif kind == "empty":
            events.add((t, "mixer", "BookWave/zero-ready-frames", 0))
        elif kind == "mark":
            label = name.rsplit("/", 1)[-1]
            if label in LAB_MARKERS or (label.startswith("early-take-back-") and label[16:] in OUTCOMES):
                events.add((t, "marker", label, 0))
        else:
            f = {k: v.strip('"') for k, v in fields(name).items()}
            event = MIX_EVENTS.get(f.get("event"))
            device = f.get("devices", "")
            own = f.get("pid") in pids
            if not event or (not own and "REMOTE_SUBMIX" not in device):
                continue
            route = next((label for match, label in (("BLUETOOTH_A2DP", "A2DP"), ("BLE_", "LE"),
                           ("REMOTE_SUBMIX", "submix"), ("SPEAKER", "speaker")) if match in device), "other")
            events.add((t, "mixer", ("BookWave" if own else "system") + "/" + route + "/" + event, 0))
    return covered, sorted(events)


def positive_id(value):
    return value if type(value) is int and value > 0 else None


def prepare_windows(anchors, runs, drives, cases):
    result = []
    for phase, points in boundaries(anchors, runs):
        anchor, last = points[0][0], points[-1][0]
        lo, hi = anchor - 3 * SECOND, last + (5 if phase == "disconnect" else 16) * SECOND
        # The recorder may open a few milliseconds after a link event and close before the projection callback.
        nearby = lambda start, end: start is not None and start - SECOND <= last and (end is None or end + SECOND >= anchor)
        matches = [(start, end, i) for start, end, i in drives if nearby(start, end)]
        run_matches = [(start, end, i, case) for start, end, i, case in runs if nearby(start, end)]
        known = {case for _, _, _, case in run_matches if case}
        case_rows = sorted((t, case) for t, case in cases.items() if lo <= t <= hi)
        if case_rows:
            known.update(case for _, case in case_rows)
        elif matches:
            since = min(start for start, _, _ in matches)
            earlier = [(t, case) for t, case in cases.items() if since <= t <= anchor]
            if earlier:
                known.add(max(earlier)[1])
        result.append(dict(phase=phase, a=anchor, lo=lo, hi=hi, points=points, cases=sorted(known),
                           driveIds=sorted({i for _, _, i in matches if positive_id(i)}),
                           runIds=sorted({i for _, _, i, _ in run_matches if positive_id(i)})))
    return result


def take_back_delays(app):
    requested = next((t for t, label in app if label == "early:Requested"), None)
    if requested is None:
        return None, {}
    delays = {}
    offers = [t for t, label in app if label == "early:Transferable" and t <= requested]
    if offers:
        delays["offeredToRequestedMs"] = ms(requested - offers[-1])
    for label, key in (("TrackReturned", "requestedToTrackVerifiedMs"), ("StreamReturned", "requestedToA2dpPlayingMs")):
        found = next((t for t, event in app if t >= requested and event == "early:" + label), None)
        if found is not None:
            delays[key] = ms(found - requested)
    return requested, delays


SCHEMA = "bookwave-drive-summary-v1"


def summarize(folder, processor=None, hci_offset=None):
    events, anchors, cases, runs, drives, sync, warnings = app_events(folder)
    windows = prepare_windows(anchors, runs, drives, cases)
    if not windows:
        raise ValueError("No car connection, disconnect or manual-run timestamps in top-level BookWave JSON exports.")
    bt = Bluetooth()
    for path in (folder / "btsnoop_hci.log.last", folder / "btsnoop_hci.log"):
        if path.is_file():
            try:
                bt.read(path)
            except (OSError, ValueError):
                warnings.append("An unreadable Bluetooth capture was skipped.")
                bt.partial = True
    for rows in bt.media.values():
        rows[:] = sorted(set(rows))
    offset, method, matches = hci_alignment(bt, sync, hci_offset)
    exchanges = bt.exchanges()
    trace_ranges, trace_events = [], set()
    traces = sorted(folder.glob("*.perfetto-trace"))
    if traces and not processor:
        warnings.append("Perfetto Trace Processor unavailable. Pass --trace-processor to include system events.")
    if processor:
        for path in traces:
            try:
                ranges, marks = perfetto(processor, path, windows)
                trace_ranges.extend(ranges)
                trace_events.update(marks)
            except (ValueError, OSError, subprocess.TimeoutExpired):
                warnings.append("A system trace could not be aligned or queried.")
    report = {"schema": SCHEMA, "time": "UTC; event times are milliseconds from each BookWave anchor",
              "hciClock": {"addSecondsToRawHci": offset, "method": method, "matchedLinks": matches},
              "hciDroppedRecordsReported": bt.drops, "hciPartialRecords": bt.partial,
              "warnings": sorted(set(warnings)), "windows": []}
    for w in windows:
        a, lo, hi = w["a"], w["lo"], w["hi"]
        app = sorted({(t, label) for t, label in events if lo <= t <= hi} | set(w["points"]))
        markers = sorted((t, k, label, dur) for t, k, label, dur in trace_events
                         if t <= hi and t + dur * 1000 >= lo)
        slow = sorted((r for r in markers if r[1] == "player"), key=lambda r: -r[3])[:5]
        rest = [r for r in markers if r[1] != "player"]
        picked = sorted(slow + rest[:32])
        system = {"coverage": coverage_of(trace_ranges, lo, hi) if trace_ranges else
                  ("missing" if not traces else "unavailable"),
                  "events": [dict(ms=ms(t-a), kind=k, event=label, **({"durationMs": dur} if dur else {}))
                             for t, k, label, dur in picked], "omittedEvents": len(markers) - len(picked)}
        # Preserve all early milestones even if surrounding platform events are unusually busy.
        early = [r for r in app if r[1].startswith("early:")]
        other = [r for r in app if not r[1].startswith("early:")]
        kept = sorted(early + other[:32])
        requested, delays = take_back_delays(app)
        report["windows"].append(dict(phase=w["phase"], anchorUtc=stamp(a), driveIds=w["driveIds"], runIds=w["runIds"],
                                      cases=w["cases"], windowMs=[ms(lo-a), ms(hi-a)],
                                      bookwave=[dict(ms=ms(t-a), event=k) for t, k in kept],
                                      omittedBookWaveEvents=len(app)-len(kept), systemTrace=system,
                                      earlyTakeBackDelays=delays,
                                      bluetooth=radio_window(bt, exchanges, offset, a, lo, hi, requested)))
    return report


def save_report(report, output):
    if output.suffix.lower() != ".json":
        raise ValueError("Choose a .json filename for the generated summary.")
    if output.exists():
        try:
            previous = json.loads(output.read_text(encoding="utf-8-sig"))
        except (ValueError, OSError, UnicodeError):
            previous = None
        if not isinstance(previous, dict) or previous.get("schema") != SCHEMA:
            raise ValueError("Choose another --output; the existing file is not a generated drive summary.")
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=output.parent, suffix=".tmp", delete=False) as f:
        temp = Path(f.name)
        try:
            json.dump(report, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
            f.close()
            os.replace(temp, output)
        finally:
            temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, nargs="?", default=Path.cwd(), help="capture folder (default: current directory)")
    parser.add_argument("--output", type=Path, help="default: bookwave-drive-summary.json inside capture folder")
    parser.add_argument("--trace-processor", help="path to Perfetto trace_processor executable")
    parser.add_argument("--hci-offset-seconds", type=int, help="known seconds to ADD to raw Bluetooth timestamps to obtain UTC")
    args = parser.parse_args()
    if args.hci_offset_seconds is not None and abs(args.hci_offset_seconds) > 14 * 3600:
        parser.error("HCI clock offset must be within 14 hours")
    output = args.output or args.folder / "bookwave-drive-summary.json"
    try:
        report = summarize(args.folder, find_processor(args.trace_processor), args.hci_offset_seconds)
        save_report(report, output)
    except (ValueError, OSError) as err:
        parser.exit(1, f"{err}\n")
    print(f"{len(report['windows'])} drive windows, {output.stat().st_size:,} bytes -> {output}")
    for warning in report["warnings"]:
        print(warning, file=sys.stderr)


if __name__ == "__main__":
    main()
