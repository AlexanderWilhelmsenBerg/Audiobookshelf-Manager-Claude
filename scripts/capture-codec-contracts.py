#!/usr/bin/env python3
"""Capture direct-play negotiation on a fresh disposable Audiobookshelf instance."""

import argparse
import json
import pathlib
import re
import secrets
import subprocess
import time
import urllib.request

SAMPLES = {
    "vorbis": ("ogg", "libvorbis"),
    "opus": ("opus", "libopus"),
    "opus-ogg": ("ogg", "libopus"),
    "adts-aac": ("aac", "aac"),
}
PREVIOUS_TYPES = ["audio/mpeg", "audio/mp4", "audio/flac"]
SUPPORTED_TYPES = [*PREVIOUS_TYPES, "audio/ogg", "audio/aac"]
DEVICE = {
    "clientName": "ShelfPlayer",
    "clientVersion": "0.0.0",
    "deviceId": "capture",
    "manufacturer": "capture",
    "model": "capture",
    "sdkVersion": 36,
}


def seed(media_dir, ffmpeg):
    """Generate only original audio, one tone in a distinct directory per format."""
    for name, (extension, codec) in SAMPLES.items():
        directory = media_dir / name
        directory.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            [
                ffmpeg, "-nostdin", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000",
                "-c:a", codec, "-metadata", f"title={name}", "-t", "3",
                str(directory / f"sample.{extension}"),
            ],
            check=True,
        )


class Server:
    def __init__(self, url):
        self.url = url.rstrip("/")
        self.token = None

    def request(self, path, method="GET", body=None):
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if path == "/login":
            headers["x-return-tokens"] = "true"
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.url + path, data, headers, method=method)
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.headers.get_content_type() == "application/json":
                return json.load(response)
            return None

    def initialize(self):
        status = self.request("/status")
        if status["isInit"]:
            raise SystemExit("Start with a fresh disposable Audiobookshelf instance.")
        login = {"username": "codec-capture", "password": secrets.token_urlsafe(24)}
        self.request("/init", "POST", {"newRoot": login})
        account = self.request("/login", "POST", login)
        self.token = account.get("accessToken") or account["user"]["accessToken"]
        return status["serverVersion"]

    def library_items(self, media_dir):
        self.request("/api/libraries", "POST", {
            "name": "Generated codec samples",
            "folders": [{"fullPath": media_dir}],
            "mediaType": "book",
        })
        library = self.request("/api/libraries")["libraries"][0]["id"]
        self.request(f"/api/libraries/{library}/scan", "POST")
        for _ in range(60):
            results = self.request(f"/api/libraries/{library}/items")["results"]
            ids = {result["media"]["metadata"]["title"]: result["id"] for result in results}
            if all(sample in ids for sample in SAMPLES):
                return ids
            time.sleep(1)
        raise SystemExit(f"Expected these generated items: {', '.join(SAMPLES)}.")

    def play(self, item_id, types):
        session = self.request(f"/api/items/{item_id}/play", "POST", {
            "deviceInfo": DEVICE,
            "supportedMimeTypes": types,
            "mediaPlayer": "exo-player",
            "forceDirectPlay": False,
            "forceTranscode": False,
        })
        session_id = session["id"]
        try:
            # Retain only the fields this client needs as negotiation evidence.
            def track_contract(track):
                url = track["contentUrl"].replace(item_id, "<volatile>").replace(session_id, "<volatile>")
                return {
                    "contentUrl": re.sub(r"(/file/)\d+", r"\1<volatile>", url),
                    "mimeType": track["mimeType"],
                }

            return {
                "playMethod": session["playMethod"],
                "audioTracks": [track_contract(track) for track in session["audioTracks"]],
            }
        finally:
            self.request(f"/api/session/{session_id}/close", "POST", {"currentTime": 0, "timeListened": 0})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    seed_command = commands.add_parser("seed")
    seed_command.add_argument("media_dir", type=pathlib.Path)
    seed_command.add_argument("--ffmpeg", default="ffmpeg")
    capture_command = commands.add_parser("capture")
    capture_command.add_argument("base_url")
    capture_command.add_argument("server_media_dir")
    capture_command.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()
    if args.command == "seed":
        seed(args.media_dir, args.ffmpeg)
        return

    server = Server(args.base_url)
    version = server.initialize()
    ids = server.library_items(args.server_media_dir)
    output = {
        "serverVersion": version,
        "supportedMimeTypes": SUPPORTED_TYPES,
        "samples": {
            sample: {
                "previous": server.play(ids[sample], PREVIOUS_TYPES),
                "expanded": server.play(ids[sample], SUPPORTED_TYPES),
            }
            for sample in SAMPLES
        },
    }
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
