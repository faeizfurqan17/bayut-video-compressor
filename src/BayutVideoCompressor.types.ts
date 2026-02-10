/**
 * Bayut Video Compressor Types
 * Hardware-accelerated video compression for React Native
 */

/**
 * Codec to use for compression.
 * - 'h264': Widest compatibility, fast hardware encoding
 * - 'hevc': 50% smaller output, modern HW encoders (iOS 11+, Android 5+)
 */
export type CompressionCodec = 'h264' | 'hevc';

/**
 * Speed preset for compression.
 * - 'ultrafast': Prioritize speed over compression ratio
 * - 'fast': Good balance of speed and quality
 * - 'balanced': Best compression ratio, slower
 */
export type CompressionSpeed = 'ultrafast' | 'fast' | 'balanced';

/**
 * Options for video compression.
 */
export interface CompressOptions {
  /**
   * Target bitrate in bits per second.
   * Default: auto-calculated based on resolution.
   * Example: 2_500_000 for 2.5 Mbps
   */
  bitrate?: number;

  /**
   * Maximum output dimension (width or height).
   * The video will be scaled proportionally.
   * Default: 1080
   */
  maxSize?: number;

  /**
   * Video codec to use.
   * Default: 'h264'
   */
  codec?: CompressionCodec;

  /**
   * Compression speed preset.
   * Default: 'ultrafast'
   */
  speed?: CompressionSpeed;

  /**
   * Minimum file size in MB before compression applies.
   * Videos smaller than this will be returned as-is.
   * Default: 0 (always compress)
   */
  minimumFileSizeForCompress?: number;

  /**
   * Callback to receive the cancellation ID.
   * Use this ID with `cancel()` to stop compression.
   */
  getCancellationId?: (id: string) => void;

  /**
   * Divider for progress events.
   * 0 = every frame, 5 = every 5%.
   * Default: 0
   */
  progressDivider?: number;
}

/**
 * Payload for compression progress events.
 */
export type CompressionProgressPayload = {
  /** Progress value from 0.0 to 1.0 */
  progress: number;
  /** Unique compression session ID */
  uuid: string;
};

/**
 * Video metadata information.
 */
export interface VideoMetadata {
  /** Video width in pixels */
  width: number;
  /** Video height in pixels */
  height: number;
  /** Duration in seconds */
  duration: number;
  /** File size in bytes */
  size: number;
  /** Video bitrate in bps */
  bitrate?: number;
  /** File extension */
  extension: string;
}

/**
 * Module event types.
 */
export type BayutVideoCompressorModuleEvents = {
  onCompressProgress: (params: CompressionProgressPayload) => void;
};

/**
 * Output image format.
 */
export type ImageOutputType = 'jpg' | 'png';

/**
 * Options for image compression.
 */
export interface ImageCompressOptions {
  /**
   * Maximum width boundary for the output image.
   * Default: 1280
   */
  maxWidth?: number;

  /**
   * Maximum height boundary for the output image.
   * Default: 1280
   */
  maxHeight?: number;

  /**
   * JPEG compression quality (0.0 - 1.0).
   * Ignored when output is 'png'.
   * Default: 0.8
   */
  quality?: number;

  /**
   * Output image format.
   * Default: 'jpg'
   */
  output?: ImageOutputType;

  /**
   * Input type.
   * Default: 'uri'
   */
  input?: 'uri' | 'base64';
}
