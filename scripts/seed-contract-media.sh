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
# Usage:
#   seed-contract-media.sh <media-dir> [image]
#   seed-contract-media.sh docker-exec:<container> [image]
#
# The docker-exec form is for containerized CI runners. It generates the fixture directly inside the
# already-running Audiobookshelf container at /audiobooks, avoiding host-path, named-volume and
# Docker->Podman copy/attach compatibility boundaries.

set -euo pipefail

MEDIA_TARGET="${1:?usage: seed-contract-media.sh <media-dir|docker-exec:container> [image]}"
IMAGE="${2:-ghcr.io/advplyr/audiobookshelf:2.36.0}"

BOOK_DIR="Marisol Holt/The Salt Harbour"
TRACK="01 - The Salt Harbour.mp3"
COVER="cover.jpg"

# A second book in two files of different lengths. This makes audioTracks[].startOffset semantics
# observable across a real track boundary instead of leaving global-vs-per-track offsets ambiguous.
MULTI_DIR="Marisol Holt/The Tidewatch Cycle"
MULTI_ONE="01 - Tidewatch.mp3"
MULTI_TWO="02 - Tidewatch.mp3"

if [[ "$MEDIA_TARGET" == docker-exec:* ]]; then
  TARGET_CONTAINER="${MEDIA_TARGET#docker-exec:}"
  if [[ ! "$TARGET_CONTAINER" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; then
    echo "invalid docker container name: $TARGET_CONTAINER" >&2
    exit 2
  fi
  MEDIA_ROOT=/audiobooks
  MEDIA_DISPLAY="docker container $TARGET_CONTAINER:/audiobooks"

  if docker exec "$TARGET_CONTAINER" sh -c '
    test -s "/audiobooks/$1/$2" &&
    test -s "/audiobooks/$1/$3" &&
    test -s "/audiobooks/$4/$5"
  ' sh "$BOOK_DIR" "$TRACK" "$COVER" "$MULTI_DIR" "$MULTI_TWO"; then
    echo "  media already present at $MEDIA_DISPLAY/$BOOK_DIR" >&2
    exit 0
  fi

  GENERATOR=(
    docker exec
    -e BOOKWAVE_MEDIA_ROOT="$MEDIA_ROOT"
    -e BOOKWAVE_BOOK_DIR="$BOOK_DIR"
    -e BOOKWAVE_TRACK="$TRACK"
    -e BOOKWAVE_COVER="$COVER"
    -e BOOKWAVE_MULTI_DIR="$MULTI_DIR"
    -e BOOKWAVE_MULTI_ONE="$MULTI_ONE"
    -e BOOKWAVE_MULTI_TWO="$MULTI_TWO"
    "$TARGET_CONTAINER" sh -c
  )
else
  MEDIA_DIR="$MEDIA_TARGET"
  MEDIA_ROOT=/media
  MEDIA_DISPLAY="$MEDIA_DIR"

  mkdir -p "$MEDIA_DIR/$BOOK_DIR" "$MEDIA_DIR/$MULTI_DIR"

  if [ -s "$MEDIA_DIR/$BOOK_DIR/$TRACK" ] && [ -s "$MEDIA_DIR/$BOOK_DIR/$COVER" ] &&
    [ -s "$MEDIA_DIR/$MULTI_DIR/$MULTI_TWO" ]; then
    echo "  media already present at $MEDIA_DIR/$BOOK_DIR" >&2
    exit 0
  fi

  GENERATOR=(
    docker run --rm
    -v "$MEDIA_DIR:/media"
    -e BOOKWAVE_MEDIA_ROOT="$MEDIA_ROOT"
    -e BOOKWAVE_BOOK_DIR="$BOOK_DIR"
    -e BOOKWAVE_TRACK="$TRACK"
    -e BOOKWAVE_COVER="$COVER"
    -e BOOKWAVE_MULTI_DIR="$MULTI_DIR"
    -e BOOKWAVE_MULTI_ONE="$MULTI_ONE"
    -e BOOKWAVE_MULTI_TWO="$MULTI_TWO"
    --entrypoint sh "$IMAGE" -c
  )
fi

# -t sits with the output options on purpose. As an input option after -i anullsrc it applies to the
# next input — the metadata file — and the silence generator then runs unbounded.
"${GENERATOR[@]}" '
  set -e
  mkdir -p "$BOOKWAVE_MEDIA_ROOT/$BOOKWAVE_BOOK_DIR" "$BOOKWAVE_MEDIA_ROOT/$BOOKWAVE_MULTI_DIR"

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
    > /tmp/bookwave-contract-meta.txt

  ffmpeg -nostdin -y -loglevel error \
    -f lavfi -i anullsrc=r=22050:cl=mono \
    -f ffmetadata -i /tmp/bookwave-contract-meta.txt \
    -map 0:a -map_metadata 1 -map_chapters 1 \
    -c:a libmp3lame -b:a 32k -t 8 \
    "$BOOKWAVE_MEDIA_ROOT/$BOOKWAVE_BOOK_DIR/$BOOKWAVE_TRACK"

  ffmpeg -nostdin -y -loglevel error \
    -f lavfi -i color=c=0x1F3A5F:s=512x512 \
    -frames:v 1 \
    "$BOOKWAVE_MEDIA_ROOT/$BOOKWAVE_BOOK_DIR/$BOOKWAVE_COVER"

  printf "%s\n" \
    ";FFMETADATA1" "title=Tidewatch" "artist=Marisol Holt" \
    "album=The Tidewatch Cycle" "track=1" "date=2024" "genre=Fiction" \
    "[CHAPTER]" "TIMEBASE=1/1000" "START=0" "END=6000" "title=The Ebb" \
    > /tmp/bookwave-contract-meta1.txt

  printf "%s\n" \
    ";FFMETADATA1" "title=Tidewatch" "artist=Marisol Holt" \
    "album=The Tidewatch Cycle" "track=2" "date=2024" "genre=Fiction" \
    "[CHAPTER]" "TIMEBASE=1/1000" "START=0" "END=4000" "title=The Flood" \
    > /tmp/bookwave-contract-meta2.txt

  ffmpeg -nostdin -y -loglevel error \
    -f lavfi -i anullsrc=r=22050:cl=mono \
    -f ffmetadata -i /tmp/bookwave-contract-meta1.txt \
    -map 0:a -map_metadata 1 -map_chapters 1 \
    -c:a libmp3lame -b:a 32k -t 6 \
    "$BOOKWAVE_MEDIA_ROOT/$BOOKWAVE_MULTI_DIR/$BOOKWAVE_MULTI_ONE"

  ffmpeg -nostdin -y -loglevel error \
    -f lavfi -i anullsrc=r=22050:cl=mono \
    -f ffmetadata -i /tmp/bookwave-contract-meta2.txt \
    -map 0:a -map_metadata 1 -map_chapters 1 \
    -c:a libmp3lame -b:a 32k -t 4 \
    "$BOOKWAVE_MEDIA_ROOT/$BOOKWAVE_MULTI_DIR/$BOOKWAVE_MULTI_TWO"

  ffmpeg -nostdin -y -loglevel error \
    -f lavfi -i color=c=0x3A5F1F:s=512x512 \
    -frames:v 1 \
    "$BOOKWAVE_MEDIA_ROOT/$BOOKWAVE_MULTI_DIR/$BOOKWAVE_COVER"

  chmod -R a+rw "$BOOKWAVE_MEDIA_ROOT"
'

echo "  seeded $MEDIA_DISPLAY/$BOOK_DIR/$TRACK" >&2
echo "  seeded $MEDIA_DISPLAY/$BOOK_DIR/$COVER" >&2
echo "  seeded $MEDIA_DISPLAY/$MULTI_DIR (two files, 6s + 4s)" >&2
