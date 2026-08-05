#!/usr/bin/env bash
# Produces the audiobook the contract capture scans.
#
# PRODUCT_SPEC 22.5 requires a captured fixture before the adapter relies on a response shape, and the
# shapes `LIB-001` needs — a library item, its audio files, its chapters — only exist once a server has
# actually scanned a file. A fresh Audiobookshelf container has no media, so an empty
# `{"libraries": []}` was all the earlier capture could record.
#
# The file is generated with the Audiobookshelf image's own ffmpeg rather than the host's, so the only
# prerequisite is the image the capture already pulls. No audio is copied from anywhere: it is eight
# seconds of digital silence with metadata and two chapters attached, which is enough for the scanner to
# produce a complete item.
#
# Usage: seed-contract-media.sh <media-dir> [image]

set -euo pipefail

MEDIA_DIR="${1:?usage: seed-contract-media.sh <media-dir> [image]}"
IMAGE="${2:-ghcr.io/advplyr/audiobookshelf:2.36.0}"

BOOK_DIR="Marisol Holt/The Salt Harbour"
TRACK="01 - The Salt Harbour.mp3"

mkdir -p "$MEDIA_DIR/$BOOK_DIR"

if [ -s "$MEDIA_DIR/$BOOK_DIR/$TRACK" ]; then
  echo "  media already present at $MEDIA_DIR/$BOOK_DIR" >&2
  exit 0
fi

# `-t` sits with the *output* options on purpose. As an input option after `-i anullsrc` it applies to
# the next input — the metadata file — and the silence generator then runs unbounded, which produces a
# multi-gigabyte file instead of an eight-second one.
docker run --rm -v "$MEDIA_DIR:/media" --entrypoint sh "$IMAGE" -c '
  set -e
  printf "%s\n" \
    ";FFMETADATA1" \
    "title=The Salt Harbour" \
    "artist=Marisol Holt" \
    "album=The Salt Harbour" \
    "composer=Ada Fenwick" \
    "date=2024" \
    "genre=Fiction" \
    "[CHAPTER]" "TIMEBASE=1/1000" "START=0" "END=4000" "title=Chapter One" \
    "[CHAPTER]" "TIMEBASE=1/1000" "START=4000" "END=8000" "title=Chapter Two" \
    > /tmp/meta.txt
  ffmpeg -nostdin -y -loglevel error \
    -f lavfi -i anullsrc=r=22050:cl=mono \
    -f ffmetadata -i /tmp/meta.txt \
    -map 0:a -map_metadata 1 -map_chapters 1 \
    -c:a libmp3lame -b:a 32k -t 8 \
    "/media/'"$BOOK_DIR"'/'"$TRACK"'"
  chmod -R a+rw /media
'

echo "  seeded $MEDIA_DIR/$BOOK_DIR/$TRACK" >&2
