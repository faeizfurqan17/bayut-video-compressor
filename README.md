# expo-image-and-video-compressor

⚡ Hardware-accelerated image & video compression for React Native / Expo.

- **Video**: H.264 & HEVC encoding via MediaCodec (Android) and VideoToolbox (iOS)
- **Image**: JPEG/PNG compression with resize, orientation fix, and EXIF preservation
- **Blazing fast**: GL pipeline with frame dropping, hardware encoder at max operating rate
- **Tiny API**: Two functions — `compress(uri)` for video, `compressImage(uri)` for images

## Installation

```bash
npm install expo-image-and-video-compressor
# or
yarn add expo-image-and-video-compressor
```

> Requires Expo SDK 51+ with expo-modules-core. Works in both managed and bare workflows.

## Quick Start

### Video Compression

```ts
import { compress } from 'expo-image-and-video-compressor';

const compressedUri = await compress(videoUri, {
  bitrate: 2_500_000,
  maxSize: 1080,
  codec: 'h264',
  speed: 'ultrafast',
}, (progress) => {
  console.log(`${Math.round(progress * 100)}%`);
});
```

### Image Compression

```ts
import { compressImage } from 'expo-image-and-video-compressor';

const compressedUri = await compressImage(imageUri, {
  maxWidth: 1080,
  maxHeight: 1920,
  quality: 0.8,
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

---

### `compressImage(fileUrl, options?)`

Compresses an image file with resize and quality control.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `maxWidth` | `number` | `1280` | Max output width in pixels |
| `maxHeight` | `number` | `1280` | Max output height in pixels |
| `quality` | `number` | `0.8` | JPEG quality (0.0 - 1.0), ignored for PNG |
| `output` | `'jpg' \| 'png'` | `'jpg'` | Output format |
| `input` | `'uri' \| 'base64'` | `'uri'` | Input type |

**Returns**: `Promise<string>` — URI of the compressed image. Returns original if compression doesn't reduce size.

---

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
- 🖼️ **Image compression** — JPEG/PNG with resize and quality control
- 🚀 **Frame dropping** — automatically drops frames for high-fps sources
- 📐 **Auto-scaling** — respects aspect ratio with `maxSize`/`maxWidth`/`maxHeight`
- 🔄 **Orientation handling** — preserves portrait/landscape for both images and video
- 📷 **EXIF preservation** — copies metadata from source to compressed images
- 🎵 **Audio pass-through** — copies audio without re-encoding
- 📊 **Progress tracking** — real-time compression progress
- ❌ **Cancellation** — cancel compression mid-flight
- 🔋 **Background support** — iOS background task support

## License

MIT
