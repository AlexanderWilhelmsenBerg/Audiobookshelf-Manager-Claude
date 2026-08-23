# BookWave launcher assets

`generate-bookwave-launcher-assets.py` is the reproducible path from the approved transparent master at
`docs/assets/bookwave-logo-master.png` to Android launcher layers, the in-app header mark, and the separate
Google Play listing icon. It removes detached alpha specks, but does not redraw or reinterpret the artwork.

Use Python 3.12 and install only the pinned image dependencies in a local virtual environment:

```powershell
py -3.12 -m venv .venv-bookwave-assets
.\.venv-bookwave-assets\Scripts\python.exe -m pip install -r scripts\requirements-bookwave-launcher-assets.txt
.\.venv-bookwave-assets\Scripts\python.exe scripts\generate-bookwave-launcher-assets.py `
  docs\assets\bookwave-logo-master.png `
  app\src\main\res `
  --store-icon docs\assets\bookwave-play-store-icon.png
```

The store icon is deliberately not an adaptive-icon resource. It is a 512 × 512, 32-bit PNG on the
BookWave brand background for the Play Console listing, and the generator rejects output larger than
1 MiB. Android continues to use the density-specific WebP layers and adaptive-icon XML resources.
