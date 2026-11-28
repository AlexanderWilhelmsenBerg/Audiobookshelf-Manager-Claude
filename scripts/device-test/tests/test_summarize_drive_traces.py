"""Run with python -B -m unittest discover -s scripts/device-test/tests."""

import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("drive_summary", Path(__file__).parents[1] / "summarize-drive-traces.py")
drive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(drive)
T = drive.utc("2026-11-28T14:00:00Z")
S = drive.SECOND


class DriveSummaryTest(unittest.TestCase):
    def test_combines_daily_and_manual_milestones_and_preserves_source_files(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            def e(at, event, details=""):
                return {"atUtc": drive.stamp(T+at), "event": event, "details": details}
            events = [e(0, "car-bluetooth-window"),
                      e(50_000, "headset-output-test", "case=EarlyTakeBack500"),
                      e(100_000, "headset-take-back", "stage=Early outcome=Transferable"),
                      e(120_000, "headset-take-back", "stage=Early outcome=Requested private=secret-fixture"),
                      e(S, "projection-change", "projection=Projection"),
                      e(8*S, "headset-take-back", "stage=Early outcome=TrackReturned"),
                      e(40*S, "projection-change", "projection=NotConnected")]
            doc = {"timeline": events, "attempts": [{"startedAtUtc": drive.stamp(T), "attempt": 2,
                    "endedAtUtc": drive.stamp(T+40*S), "endReason": "projection-disconnect", "events": events}],
                   "playbackTestSuite": {"runs": [{"runId": 4, "startUtc": drive.stamp(T-S),
                    "endUtc": drive.stamp(T+42*S), "headsetOutputAtStart": "case=EarlyTakeBack500",
                    "events": [e(9*S, "early-take-back-StreamReturned", {"private": "secret-fixture"})]}]}}
            raw = folder / "bookwave-diagnostics-test.json"
            raw.write_text(json.dumps(doc))
            report = drive.summarize(folder)
            a, b = report["windows"]
            self.assertEqual(("connect", "disconnect"), (a["phase"], b["phase"]))
            self.assertEqual(([2], [4], ["EarlyTakeBack500"]), (a["driveIds"], a["runIds"], a["cases"]))
            self.assertEqual({"offeredToRequestedMs": 20, "requestedToTrackVerifiedMs": 7880,
                              "requestedToA2dpPlayingMs": 8880}, a["earlyTakeBackDelays"])
            self.assertEqual("missing", a["bluetooth"]["coverage"])
            self.assertNotIn("secret-fixture", json.dumps(report))
            with self.assertRaises(ValueError):
                drive.save_report(report, raw)
            self.assertEqual(doc, json.loads(raw.read_text()))
            generated = folder / "bookwave-drive-summary.json"
            drive.save_report(report, generated)
            drive.save_report(report, generated)
            self.assertEqual(report, drive.summarize(folder))

    def test_matches_clock_only_with_an_unambiguous_independent_connection(self):
        bt = drive.Bluetooth()
        bt.links = [(T + 3600*S, "connected", 1)]
        self.assertEqual((-3600, "matched-app-and-HCI-connections", 1),
                         drive.hci_alignment(bt, [(T+20_000, "acl")], None))
        bt.links.append((T, "connected", 2))
        self.assertIsNone(drive.hci_alignment(bt, [(T, "acl")], None)[0])
        self.assertEqual((0, "explicit", 0), drive.hci_alignment(bt, [], 0))
        self.assertEqual("partial", drive.coverage_of([(T, T+S)], T-S, T+S))
        self.assertEqual("outside", drive.coverage_of([(T, T+S)], T-2*S, T-S))

    def test_reads_rotated_h4_records_and_first_media_after_accepted_restart(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            def acl(payload):
                l2 = struct.pack("<HH", len(payload), 0x41) + payload
                return b"\x02" + struct.pack("<HH", 0x2042, len(l2)) + l2
            def media(seq):
                return acl(struct.pack(">BBHII", 0x80, 96, seq, seq*1024, 1234) + b"\xfa\xbe")
            def h4(path, rows):
                with path.open("wb") as f:
                    f.write(b"btsnoop\0" + struct.pack(">II", 1, 1002))
                    for delta, direction, payload in rows:
                        f.write(struct.pack(">IIIIQ", len(payload), len(payload), direction, 0,
                                            T + delta + drive.SNOOP_EPOCH) + payload)
            older, newer = folder / "btsnoop_hci.log.last", folder / "btsnoop_hci.log"
            h4(older, [(i*25_000-500_000, 0, media(i)) for i in range(20)])
            h4(newer, [(0, 0, acl(b"\x10\x09\x04")), (5_000, 1, acl(b"\x12\x09")),
                       (500_000, 0, acl(b"\x20\x07\x04")), (510_000, 1, acl(b"\x22\x07")),
                       (520_000, 0, media(20)), (545_000, 0, media(21))])
            bt = drive.Bluetooth()
            bt.read(older)
            bt.read(newer)
            r = drive.radio_window(bt, bt.exchanges(), 0, T, T-500_000, T+545_000, T+20_000)
            self.assertEqual(["SUSPEND", "START"], [s["action"] for s in r["headsetSignalling"]])
            self.assertEqual(520, r["headsetSignalling"][1]["firstOutboundMediaMs"])
            self.assertEqual(500, r["nextOutboundPacketAfterRequestMs"])
            self.assertEqual(545, r["largestPacketGap"]["ms"])
            key, rows = next(iter(bt.media.items()))
            bt.media[(0x43, *key[1:])] = rows
            self.assertEqual("ambiguous", drive.radio_window(bt, [], 0, T, T-S, T+S)["referenceStream"])

    def test_perfetto_uses_clock_snapshots_limits_recording_coverage_and_drops_private_labels(self):
        def row(kind, at, name, dur=0):
            return dict(kind=kind, ts=str(at), name=name.encode().hex(), dur=str(dur))
        clocks = [{"kind": "clock", "a": "1000000", "b": str(T*1000)},
                  {"kind": "bounds", "a": "0", "b": "5000000000"},
                  {"kind": "start", "a": "1000000", "b": "0"}]
        marks = [row("mark", 1000000, "BookWave/4/HeadsetRoute/private-name"),
                 row("mix", 2000000, '{ pid=13 event="beginInterval" devices="AUDIO_DEVICE_OUT_BLUETOOTH_A2DP" }'),
                 row("loop", 3000000, "doSomeWork", 100000000)]
        with patch.object(drive, "query", side_effect=[clocks, [{"pid": "13"}], marks]):
            ranges, events = drive.perfetto("processor", "trace", [{"lo": T-S, "hi": T+S}])
        self.assertEqual("partial", drive.coverage_of(ranges, T-S, T+S))
        self.assertEqual([(T+1000, "mixer", "BookWave/A2DP/begin", 0), (T+2000, "player", "doSomeWork", 100)], events)


if __name__ == "__main__":
    unittest.main()
