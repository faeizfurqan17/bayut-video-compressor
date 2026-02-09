package expo.modules.bayutvideocompressor

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated video compressor using MediaCodec.
 * Uses GL surface pipeline to properly set presentation timestamps
 * via eglPresentationTimeANDROID for correct encoder bitrate control.
 */
class VideoCompressor(private val context: Context) {

    companion object {
        private const val TAG = "BayutCompressor"
        private const val I_FRAME_INTERVAL = 1
    }

    data class CompressConfig(
        val maxSize: Int = 1080,
        val bitrate: Int = 0,
        val codec: String = "h264",
        val speed: String = "ultrafast",
    )

    interface ProgressListener {
        fun onProgress(progress: Float)
    }

    fun compress(
        inputUri: Uri,
        outputFile: File,
        config: CompressConfig,
        cancelled: AtomicBoolean,
        listener: ProgressListener?,
    ): String {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, inputUri)
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot read video: ${e.message}")
        }

        val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
        val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val sourceBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 5_000_000
        retriever.release()

        val (outputWidth, outputHeight) = calculateOutputSize(sourceWidth, sourceHeight, config.maxSize, rotation)

        val outputBitrate = if (config.bitrate > 0) {
            config.bitrate
        } else {
            calculateAutoBitrate(outputWidth, outputHeight, sourceBitrate, config.speed)
        }

        val mimeType = when (config.codec) {
            "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }

        Log.i(TAG, "Compressing: ${sourceWidth}x${sourceHeight} -> ${outputWidth}x${outputHeight}, " +
                "bitrate: $outputBitrate, codec: $mimeType, speed: ${config.speed}")

        val startTime = System.currentTimeMillis()

        // Set up extractor
        val extractor = MediaExtractor()
        val fd = context.contentResolver.openFileDescriptor(inputUri, "r")
            ?: throw IllegalArgumentException("Cannot open video file")
        extractor.setDataSource(fd.fileDescriptor)

        val videoTrackIndex = findTrack(extractor, true)
        if (videoTrackIndex < 0) throw IllegalStateException("No video track found")
        val audioTrackIndex = findTrack(extractor, false)

        // Set up encoder
        val outputFormat = MediaFormat.createVideoFormat(mimeType, outputWidth, outputHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, outputBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)  // Realtime priority
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())  // Max speed
            }
        }

        val encoder = findHardwareEncoder(mimeType, outputFormat)
        Log.i(TAG, "Using encoder: ${encoder.name} (${if (isHardwareEncoder(encoder.name)) "HARDWARE" else "SOFTWARE"})")
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        // GL pipeline: decoder → SurfaceTexture → GL → eglPresentationTimeANDROID → encoder
        // Required for correct timestamps and bitrate control
        extractor.selectTrack(videoTrackIndex)
        val inputFormat = extractor.getTrackFormat(videoTrackIndex)
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)

        val outputSurface = OutputSurface(encoderInputSurface, outputWidth, outputHeight)
        decoder.configure(inputFormat, outputSurface.surface, null, 0)
        decoder.start()

        // Set up muxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) muxer.setOrientationHint(rotation)

        var videoMuxTrack = -1
        var audioMuxTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var outputDone = false
        var lastProgressPercent = -1
        var frameCount = 0

        Log.i(TAG, "Starting transcode loop...")

        while (!outputDone) {
            if (cancelled.get()) {
                cleanupAll(decoder, encoder, muxer, extractor, outputSurface, encoderInputSurface, fd)
                outputFile.delete()
                throw InterruptedException("Compression cancelled")
            }

            // --- Feed input to decoder (non-blocking) ---
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(0)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                        frameCount++
                    }
                }
            }

            // --- Drain decoder → GL render → encoder (with proper timestamps) ---
            if (!decoderDone) {
                val status = decoder.dequeueOutputBuffer(bufferInfo, 2_500)
                when {
                    status >= 0 -> {
                        val doRender = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(status, doRender)

                        if (doRender) {
                            outputSurface.awaitNewImage()
                            outputSurface.drawImage()
                            outputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                            outputSurface.swapBuffers()
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoder.signalEndOfInputStream()
                            decoderDone = true
                        }

                        if (duration > 0 && doRender) {
                            val pct = ((bufferInfo.presentationTimeUs.toFloat() / (duration * 1000f)) * 100)
                                .toInt().coerceIn(0, 100)
                            if (pct > lastProgressPercent) {
                                lastProgressPercent = pct
                                listener?.onProgress(pct / 100f)
                            }
                        }
                    }
                    // INFO_OUTPUT_FORMAT_CHANGED, INFO_TRY_AGAIN_LATER — continue
                }
            }

            // --- Drain encoder → write to muxer ---
            while (true) {
                val encStatus = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    encStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoMuxTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioTrackIndex >= 0) {
                            extractor.unselectTrack(videoTrackIndex)
                            extractor.selectTrack(audioTrackIndex)
                            audioMuxTrack = muxer.addTrack(extractor.getTrackFormat(audioTrackIndex))
                            extractor.unselectTrack(audioTrackIndex)
                            extractor.selectTrack(videoTrackIndex)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    encStatus >= 0 -> {
                        val data = encoder.getOutputBuffer(encStatus)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            data.position(bufferInfo.offset)
                            data.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoMuxTrack, data, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(encStatus, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                }
            }
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

        // Audio pass-through
        if (audioTrackIndex >= 0 && audioMuxTrack >= 0 && muxerStarted) {
            processAudio(extractor, muxer, audioTrackIndex, audioMuxTrack)
        }

        cleanupAll(decoder, encoder, muxer, extractor, outputSurface, encoderInputSurface, fd)

        listener?.onProgress(1.0f)
        val outKB = outputFile.length() / 1024
        val fps = if (elapsed > 0) (frameCount / elapsed).toInt() else 0
        Log.i(TAG, "Compression complete: ${outKB}KB in ${String.format("%.1f", elapsed)}s ($frameCount frames, $fps fps)")

        return outputFile.absolutePath
    }

    // *** Hardware encoder selection ***
    private fun findHardwareEncoder(mimeType: String, format: MediaFormat): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        val compatibleEncoders = codecList.codecInfos
            .filter { it.isEncoder }
            .filter { it.supportedTypes.any { type -> type.equals(mimeType, ignoreCase = true) } }

        Log.i(TAG, "Available encoders for $mimeType:")
        compatibleEncoders.forEach { codec ->
            val hw = if (isHardwareEncoder(codec.name)) "HARDWARE" else "SOFTWARE"
            Log.i(TAG, "  - ${codec.name} ($hw)")
        }

        val hwEncoders = compatibleEncoders
            .filter { isHardwareEncoder(it.name) }
            .filter { !it.name.contains("secure", ignoreCase = true) }

        if (hwEncoders.isNotEmpty()) {
            val selected = hwEncoders.first()
            Log.i(TAG, "Selected HARDWARE encoder: ${selected.name}")
            return MediaCodec.createByCodecName(selected.name)
        }

        val swEncoder = compatibleEncoders.firstOrNull()
        if (swEncoder != null) {
            Log.w(TAG, "No HW encoder, using SOFTWARE: ${swEncoder.name}")
            return MediaCodec.createByCodecName(swEncoder.name)
        }

        return MediaCodec.createEncoderByType(mimeType)
    }

    private fun isHardwareEncoder(name: String): Boolean {
        val softwarePrefixes = listOf("OMX.google.", "c2.android.", "c2.google.")
        return softwarePrefixes.none { name.startsWith(it, ignoreCase = true) }
    }

    private fun processAudio(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        muxTrackIndex: Int
    ) {
        extractor.unselectTrack(findTrack(extractor, true))
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val buffer = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()

        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxTrackIndex, buffer, info)
            extractor.advance()
        }
    }

    private fun findTrack(extractor: MediaExtractor, isVideo: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (isVideo && mime.startsWith("video/")) return i
            if (!isVideo && mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun calculateOutputSize(srcW: Int, srcH: Int, maxSize: Int, rotation: Int): Pair<Int, Int> {
        val w = if (rotation == 90 || rotation == 270) srcH else srcW
        val h = if (rotation == 90 || rotation == 270) srcW else srcH
        val scale = if (h > w) (maxSize.toFloat() / h).coerceAtMost(1f)
                    else (maxSize.toFloat() / w).coerceAtMost(1f)
        return Pair((Math.round(w * scale / 2f) * 2), (Math.round(h * scale / 2f) * 2))
    }

    private fun calculateAutoBitrate(width: Int, height: Int, sourceBitrate: Int, speed: String): Int {
        val pixels = width * height
        val factor = when (speed) { "ultrafast" -> 0.9f; "fast" -> 0.8f; else -> 0.7f }
        val target = (pixels * 1.5f * factor).toInt()
        return target.coerceIn(500_000, minOf(sourceBitrate, 5_000_000))
    }

    private fun cleanupAll(
        decoder: MediaCodec, encoder: MediaCodec, muxer: MediaMuxer,
        extractor: MediaExtractor, outputSurface: OutputSurface,
        inputSurface: Surface, fd: android.os.ParcelFileDescriptor
    ) {
        try { decoder.stop() } catch (_: Exception) {}
        try { decoder.release() } catch (_: Exception) {}
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        try { muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        try { extractor.release() } catch (_: Exception) {}
        try { outputSurface.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
        try { fd.close() } catch (_: Exception) {}
    }
}
