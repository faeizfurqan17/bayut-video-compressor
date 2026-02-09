import { NativeModule, requireNativeModule } from 'expo';

import { BayutVideoCompressorModuleEvents, CompressOptions, VideoMetadata } from './BayutVideoCompressor.types';

declare class BayutVideoCompressorModule extends NativeModule<BayutVideoCompressorModuleEvents> {
  /**
   * Compress a video file with hardware acceleration.
   * @param fileUrl - File URI of the video to compress
   * @param options - Compression options (bitrate, maxSize, codec, speed)
   * @returns Promise resolving to the compressed file URI
   */
  compress(fileUrl: string, options: CompressOptions): Promise<string>;

  /**
   * Cancel an ongoing compression.
   * @param uuid - The cancellation ID from getCancellationId callback
   */
  cancel(uuid: string): void;

  /**
   * Get metadata for a video file.
   * @param fileUrl - File URI of the video
   * @returns Promise resolving to video metadata
   */
  getMetadata(fileUrl: string): Promise<VideoMetadata>;

  /**
   * Activate a background task to keep compression running when app is backgrounded.
   * iOS only - uses UIApplication background task.
   */
  activateBackgroundTask(): Promise<string>;

  /**
   * Deactivate the background task.
   */
  deactivateBackgroundTask(): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<BayutVideoCompressorModule>('BayutVideoCompressor');
