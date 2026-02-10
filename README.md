# expo-image-and-video-compressor

⚡ Hardware-accelerated image & video compression for React Native / Expo.

- **Video**: H.264 & HEVC encoding via MediaCodec (Android) and VideoToolbox (iOS)
- **Blazing fast**: GL pipeline with frame dropping, hardware encoder at max operating rate
- **Tiny API**: One function call — `compress(uri, options)`

## Installation

```bash
npm install expo-image-and-video-compressor
# or
yarn add expo-image-and-video-compressor
```

> Requires Expo SDK 51+ with expo-modules-core. Works in both managed and bare workflows.

## Quick Start

```ts
import { compress } from 'expo-image-and-video-compressor';

// Compress a video
const compressedUri = await compress(videoUri, {
  bitrate: 2_500_000, // 2.5 Mbps
  maxSize: 1080,       // max dimension
  codec: 'h264',
  speed: 'ultrafast',
}, (progress) => {
  console.log(`${Math.round(progress * 100)}%`);
});
```

## API

### `compress(fileUrl, options?, onProgress?)`

Compresses a video file with hardware acceleration.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `bitrate` | `number` | auto | Target bitrate in bps |
| `maxSize` | `number` | `1080` | Max width or height in pixels |
| `codec` | `'h264' \| 'hevc'` | `'h264'` | Output codec |
| `speed` | `'ultrafast' \| 'fast' \| 'medium'` | `'ultrafast'` | Encoding speed preset |
| `minimumFileSizeForCompress` | `number` | `0` | Skip compression if file is smaller (bytes) |
| `getCancellationId` | `(id: string) => void` | — | Receive a cancellation ID |

**Returns**: `Promise<string>` — URI of the compressed file.

### `cancel(uuid)`

Cancel an ongoing compression using the ID from `getCancellationId`.

### `getMetadata(fileUrl)`

Get video metadata (duration, dimensions, bitrate, etc).

### `activateBackgroundTask(onExpired?)`

Keep compression running when the app is backgrounded (iOS).

### `deactivateBackgroundTask()`

End the background task.

## Features

- 🎥 **H.264 & HEVC** hardware encoding
- 🚀 **Frame dropping** — automatically drops frames for high-fps sources (e.g. 52fps → 30fps)
- 📐 **Auto-scaling** — respects aspect ratio with `maxSize`
- 🔄 **Rotation handling** — preserves portrait/landscape orientation
- 🎵 **Audio pass-through** — copies audio without re-encoding
- 📊 **Progress tracking** — real-time compression progress
- ❌ **Cancellation** — cancel compression mid-flight
- 🔋 **Background support** — iOS background task support

## License

MIT
