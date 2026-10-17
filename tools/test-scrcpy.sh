#!/usr/bin/env bash
export SDL_RENDER_DRIVER=vulkan

exec /usr/bin/scrcpy \
  --new-display=464x1028 \
  --start-app=studio.deimos.pinyonshift \
  --stay-awake \
  --window-borderless \
  --window-width=464 \
  --window-height=1028 \
  --max-fps=120 \
  -m 1028 \
  --video-codec=h264 \
  --video-buffer=0 \
  --audio-source=playback \
  --audio-codec=opus \
  --audio-bit-rate=320K \
  --audio-buffer=80 \
  "$@"
