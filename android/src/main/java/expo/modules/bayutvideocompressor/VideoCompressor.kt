package expo.modules.bayutvideocompressor

import android.content.Context
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated video compressor using MediaCodec.
 * Key difference from react-native-compressor:
 * Uses MediaCodecList.findEncoderForFormat() for HARDWARE encoder selection
 * instead of hardcoded "c2.android.avc.encoder" (SOFTWARE).
 */
class VideoCompressor(private val context: Context) {

    companion object {
        private const val TAG = "BayutCompressor"
        private const val TIMEOUT_US = 10_000L
        private const val I_FRAME_INTERVAL = 1
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_SAMPLE_RATE = 44_100
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

    /**
     * Compress a video with hardware-accelerated encoding.
     * @return path to compressed video file
     */
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

        // Calculate output dimensions
        val (outputWidth, outputHeight) = calculateOutputSize(sourceWidth, sourceHeight, config.maxSize, rotation)

        // Calculate bitrate
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

        // Set up extractor
        val extractor = MediaExtractor()
        val contentResolver = context.contentResolver
        val fd = contentResolver.openFileDescriptor(inputUri, "r")
            ?: throw IllegalArgumentException("Cannot open video file")
        extractor.setDataSource(fd.fileDescriptor)

        val videoTrackIndex = findTrack(extractor, true)
        if (videoTrackIndex < 0) throw IllegalStateException("No video track found")

        val audioTrackIndex = findTrack(extractor, false)

        // Set up encoder with HARDWARE acceleration
        val outputFormat = MediaFormat.createVideoFormat(mimeType, outputWidth, outputHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, outputBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, if (config.speed == "ultrafast") 0 else 1)
            }
        }

        // *** THE KEY FIX: Use findEncoderForFormat for HARDWARE encoder ***
        val encoder = findHardwareEncoder(mimeType, outputFormat)
        Log.i(TAG, "Using encoder: ${encoder.name} (${if (isHardwareEncoder(encoder.name)) "HARDWARE" else "SOFTWARE"})")

        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Surface-to-surface pipeline (zero-copy)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        // Set up decoder
        extractor.selectTrack(videoTrackIndex)
        val inputFormat = extractor.getTrackFormat(videoTrackIndex)
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)

        // OutputSurface receives decoded frames and renders to encoder's input surface via OpenGL
        val outputSurface = OutputSurface(inputSurface, outputWidth, outputHeight)

        decoder.configure(inputFormat, outputSurface.surface, null, 0)
        decoder.start()

        // Set up muxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) {
            muxer.setOrientationHint(rotation)
        }

        var videoMuxTrack = -1
        var audioMuxTrack = -1
        var muxerStarted = false

        // Process video
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgressReport = 0

        while (!outputDone) {
            if (cancelled.get()) {
                cleanup(decoder, encoder, muxer, extractor, outputSurface, inputSurface, fd)
                outputFile.delete()
                throw InterruptedException("Compression cancelled")
            }

            // Feed input to decoder
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize,
                            extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Get decoded output and send to encoder via surface
            var decoderOutputAvailable = true
            while (decoderOutputAvailable) {
                val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                when {
                    decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        decoderOutputAvailable = false
                    }
                    decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Continue
                    }
                    decoderStatus >= 0 -> {
                        val doRender = bufferInfo.size > 0
                        decoder.releaseOutputBuffer(decoderStatus, doRender)

                        if (doRender) {
                            outputSurface.awaitNewImage()
                            outputSurface.drawImage()
                            outputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                            outputSurface.swapBuffers()
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoder.signalEndOfInputStream()
                            decoderOutputAvailable = false
                        }

                        // Report progress
                        if (duration > 0) {
                            val progress = (bufferInfo.presentationTimeUs.toFloat() / (duration * 1000f))
                                .coerceIn(0f, 1f)
                            val progressInt = (progress * 100).toInt()
                            if (progressInt > lastProgressReport) {
                                lastProgressReport = progressInt
                                listener?.onProgress(progress)
                            }
                        }
                    }
                }
            }

            // Get encoder output and write to muxer
            var encoderOutputAvailable = true
            while (encoderOutputAvailable) {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                when {
                    encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        encoderOutputAvailable = false
                    }
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoMuxTrack = muxer.addTrack(encoder.outputFormat)

                            // Process audio track too
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
                    }
                    encoderStatus >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoMuxTrack, encodedData, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            encoderOutputAvailable = false
                        }
                    }
                }
            }
        }

        // Process audio track (pass-through)
        if (audioTrackIndex >= 0 && audioMuxTrack >= 0 && muxerStarted) {
            processAudio(extractor, muxer, audioTrackIndex, audioMuxTrack)
        }

        cleanup(decoder, encoder, muxer, extractor, outputSurface, inputSurface, fd)

        listener?.onProgress(1.0f)
        Log.i(TAG, "Compression complete: ${outputFile.length() / 1024}KB")

        return outputFile.absolutePath
    }

    // *** THE KEY FUNCTION: Hardware encoder selection ***
    private fun findHardwareEncoder(mimeType: String, format: MediaFormat): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        // Try to find the best encoder using the official API
        val encoderName = codecList.findEncoderForFormat(format)

        if (encoderName != null) {
            Log.i(TAG, "MediaCodecList selected encoder: $encoderName")
            return MediaCodec.createByCodecName(encoderName)
        }

        // Fallback: manually search for hardware encoder
        val hwEncoder = codecList.codecInfos
            .filter { it.isEncoder }
            .filter { it.supportedTypes.any { type -> type.equals(mimeType, ignoreCase = true) } }
            .sortedByDescending { isHardwareEncoder(it.name) }
            .firstOrNull()

        if (hwEncoder != null) {
            Log.i(TAG, "Fallback encoder: ${hwEncoder.name}")
            return MediaCodec.createByCodecName(hwEncoder.name)
        }

        // Last resort
        Log.w(TAG, "Using default encoder for $mimeType")
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

        val bufferSize = 256 * 1024
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(muxTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    private fun findTrack(extractor: MediaExtractor, isVideo: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (isVideo && mime.startsWith("video/")) return i
            if (!isVideo && mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun calculateOutputSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxSize: Int,
        rotation: Int
    ): Pair<Int, Int> {
        val effectiveWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val effectiveHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight

        val isPortrait = effectiveHeight > effectiveWidth
        val scale = if (isPortrait) {
            (maxSize.toFloat() / effectiveHeight).coerceAtMost(1f)
        } else {
            (maxSize.toFloat() / effectiveWidth).coerceAtMost(1f)
        }

        // Round to even numbers (required by video encoders)
        val w = (Math.round(effectiveWidth * scale / 2f) * 2)
        val h = (Math.round(effectiveHeight * scale / 2f) * 2)

        return Pair(w, h)
    }

    private fun calculateAutoBitrate(width: Int, height: Int, sourceBitrate: Int, speed: String): Int {
        val pixels = width * height
        val compressFactor = when (speed) {
            "ultrafast" -> 0.9f
            "fast" -> 0.8f
            else -> 0.7f
        }

        val baseBitrate = (pixels * 1.5f).toInt()
        val targetBitrate = (baseBitrate * compressFactor).toInt()
        val maxBitrate = 5_000_000

        return targetBitrate.coerceIn(500_000, minOf(sourceBitrate, maxBitrate))
    }

    private fun cleanup(
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        extractor: MediaExtractor,
        outputSurface: OutputSurface,
        inputSurface: Surface,
        fd: android.os.ParcelFileDescriptor
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
