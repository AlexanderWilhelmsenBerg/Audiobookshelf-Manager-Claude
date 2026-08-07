#!/usr/bin/env bash
# Produces the audiobook the contract capture scans.
#
# PRODUCT_SPEC 22.5 requires a captured fixture before the adapter relies on a response shape, and the
# shapes `LIB-001` needs — a library item, its audio files, its chapters, its cover — only exist once a
# server has actually scanned a file. A fresh Audiobookshelf container has no media, so an empty
# `{"libraries": []}` was all the earlier capture could record.
#
# The files are generated with the Audiobookshelf image's own ffmpeg rather than the host's, so the only
# prerequisite is the image the capture already pulls. Nothing is copied from anywhere: the audio is
# eight seconds of digital silence with metadata and two chapters attached, and the cover is a flat
# rectangle of one colour. Between them that is enough for the scanner to produce a complete item.
#
# Usage: seed-contract-media.sh <media-dir> [image]

set -euo pipefail

MEDIA_DIR="${1:?usage: seed-contract-media.sh <media-dir> [image]}"
IMAGE="${2:-ghcr.io/advplyr/audiobookshelf:2.36.0}"

BOOK_DIR="Marisol Holt/The Salt Harbour"
TRACK="01 - The Salt Harbour.mp3"
# The scanner adopts a `cover.*` file sitting beside the audio, which is the only way this fixture book
# gets one: the container is created with `scannerFindCovers` off, so nothing is fetched from the
# internet, and an item with no cover answers `GET /api/items/{id}/cover` with a 404. That 404 is what
# the first capture recorded, and a 404 is not the shape LIB-004 needs to know.
COVER="cover.jpg"

mkdir -p "$MEDIA_DIR/$BOOK_DIR"

if [ -s "$MEDIA_DIR/$BOOK_DIR/$TRACK" ] && [ -s "$MEDIA_DIR/$BOOK_DIR/$COVER" ]; then
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
  ffmpeg -nostdin -y -loglevel error \
    -f lavfi -i color=c=0x1F3A5F:s=512x512 \
    -frames:v 1 \
    "/media/'"$BOOK_DIR"'/'"$COVER"'"
  chmod -R a+rw /media
'

echo "  seeded $MEDIA_DIR/$BOOK_DIR/$TRACK" >&2
echo "  seeded $MEDIA_DIR/$BOOK_DIR/$COVER" >&2
