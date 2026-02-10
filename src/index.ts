import { NativeEventEmitter, type NativeEventSubscription } from 'react-native';
import BayutVideoCompressorModule from './BayutVideoCompressorModule';
import type { CompressOptions, CompressionProgressPayload } from './BayutVideoCompressor.types';

export * from './BayutVideoCompressor.types';

const EventEmitter = new NativeEventEmitter(BayutVideoCompressorModule as any);

/**
 * Compress a video with hardware-accelerated encoding.
 *
 * @param fileUrl - File URI of the video to compress
 * @param options - Compression options
 * @param onProgress - Optional progress callback (0.0 - 1.0)
 * @returns Promise resolving to the compressed file URI
 *
 * @example
 * ```ts
 * import { compress } from 'expo-image-and-video-compressor';
 *
 * const result = await compress(videoUri, {
 *   bitrate: 2_500_000,
 *   maxSize: 1080,
 *   codec: 'h264',
 *   speed: 'ultrafast',
 * }, (progress) => {
 *   console.log(`${Math.round(progress * 100)}%`);
 * });
 * ```
 */
export async function compress(
    fileUrl: string,
    options?: CompressOptions,
    onProgress?: (progress: number) => void,
): Promise<string> {
    let subscription: NativeEventSubscription | undefined;
    let uuid: string | undefined;

    try {
        const opts: CompressOptions = {
            maxSize: 1080,
            codec: 'h264',
            speed: 'ultrafast',
            minimumFileSizeForCompress: 0,
            progressDivider: 0,
            ...options,
        };

        if (onProgress) {
            subscription = EventEmitter.addListener(
                'onCompressProgress',
                (event: CompressionProgressPayload) => {
                    if (event.uuid === uuid) {
                        onProgress(event.progress);
                    }
                },
            );
        }

        if (opts.getCancellationId) {
            const originalCallback = opts.getCancellationId;
            opts.getCancellationId = (id: string) => {
                uuid = id;
                originalCallback(id);
            };
        } else {
            opts.getCancellationId = (id: string) => {
                uuid = id;
            };
        }

        const result = await BayutVideoCompressorModule.compress(fileUrl, opts);
        return result;
    } finally {
        subscription?.remove();
    }
}

/**
 * Cancel an ongoing compression.
 * @param uuid - The cancellation ID received from getCancellationId
 */
export function cancel(uuid: string): void {
    BayutVideoCompressorModule.cancel(uuid);
}

/**
 * Get metadata for a video file.
 * @param fileUrl - File URI of the video
 */
export function getMetadata(fileUrl: string) {
    return BayutVideoCompressorModule.getMetadata(fileUrl);
}

/**
 * Activate a background task to keep compression running when app is backgrounded.
 * @param onExpired - Optional callback when the background task expires
 */
export async function activateBackgroundTask(onExpired?: () => void): Promise<string> {
    if (onExpired) {
        const sub = EventEmitter.addListener('onBackgroundTaskExpired', () => {
            onExpired();
            sub.remove();
        });
    }
    return BayutVideoCompressorModule.activateBackgroundTask();
}

/**
 * Deactivate the background task.
 */
export function deactivateBackgroundTask(): Promise<void> {
    EventEmitter.removeAllListeners('onBackgroundTaskExpired');
    return BayutVideoCompressorModule.deactivateBackgroundTask();
}
