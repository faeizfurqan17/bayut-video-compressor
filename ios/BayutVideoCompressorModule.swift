import ExpoModulesCore
import AVFoundation
import VideoToolbox

public class BayutVideoCompressorModule: Module {
  private var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid
  private var activeSessions: [String: VTCompressionSession] = [:]
  private var cancelledUUIDs: Set<String> = []

  public func definition() -> ModuleDefinition {
    Name("BayutVideoCompressor")

    Events("onCompressProgress", "onBackgroundTaskExpired")

    // MARK: - Compress

    AsyncFunction("compress") { (fileUrl: String, options: [String: Any]) -> String in
      let uuid = UUID().uuidString

      // Send UUID back via options callback (handled in JS layer)
      // Emit it as part of progress events

      let maxSize = options["maxSize"] as? Int ?? 1080
      let bitrate = options["bitrate"] as? Int ?? 0
      let codec = options["codec"] as? String ?? "h264"
      let speed = options["speed"] as? String ?? "ultrafast"
      let minimumFileSize = options["minimumFileSizeForCompress"] as? Double ?? 0
      let progressDivider = options["progressDivider"] as? Int ?? 0

      let inputURL = URL(string: fileUrl)!
      let resolvedURL = self.resolveFileURL(inputURL)

      // Check minimum file size
      if minimumFileSize > 0 {
        let fileSize = self.getFileSizeMB(url: resolvedURL)
        if fileSize <= minimumFileSize {
          return resolvedURL.absoluteString
        }
      }

      // Get source video info
      let asset = AVURLAsset(url: resolvedURL, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
      guard let videoTrack = asset.tracks(withMediaType: .video).first else {
        throw CompressorError.invalidVideo("No video track found")
      }

      let naturalSize = videoTrack.naturalSize.applying(videoTrack.preferredTransform)
      let sourceWidth = abs(naturalSize.width)
      let sourceHeight = abs(naturalSize.height)
      let sourceBitrate = videoTrack.estimatedDataRate

      // Calculate output dimensions
      let (outputWidth, outputHeight) = self.calculateOutputSize(
        sourceWidth: sourceWidth,
        sourceHeight: sourceHeight,
        maxSize: CGFloat(maxSize)
      )

      // Calculate output bitrate
      let outputBitrate: Int
      if bitrate > 0 {
        outputBitrate = bitrate
      } else {
        outputBitrate = self.calculateAutoBitrate(
          width: Int(outputWidth),
          height: Int(outputHeight),
          sourceBitrate: Int(sourceBitrate),
          speed: speed
        )
      }

      // Create output URL
      let outputURL = self.createTempURL()

      // Compress using VTCompressionSession for maximum speed
      try await self.compressWithVTSession(
        asset: asset,
        videoTrack: videoTrack,
        outputURL: outputURL,
        outputWidth: Int(outputWidth),
        outputHeight: Int(outputHeight),
        outputBitrate: outputBitrate,
        codec: codec,
        speed: speed,
        uuid: uuid,
        progressDivider: progressDivider
      )

      return outputURL.absoluteString
    }

    // MARK: - Cancel

    Function("cancel") { (uuid: String) in
      self.cancelledUUIDs.insert(uuid)
      if let session = self.activeSessions[uuid] {
        VTCompressionSessionInvalidate(session)
        self.activeSessions.removeValue(forKey: uuid)
      }
    }

    // MARK: - Get Metadata

    AsyncFunction("getMetadata") { (fileUrl: String) -> [String: Any] in
      let url = URL(string: fileUrl)!
      let resolvedURL = self.resolveFileURL(url)

      let asset = AVURLAsset(url: resolvedURL, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])

      var result: [String: Any] = [:]

      if let track = asset.tracks(withMediaType: .video).first {
        let size = track.naturalSize
        let duration = CMTimeGetSeconds(asset.duration)
        let fileSize = self.getFileSizeBytes(url: resolvedURL)
        let ext = resolvedURL.pathExtension

        result["width"] = size.width
        result["height"] = size.height
        result["duration"] = duration
        result["size"] = fileSize
        result["bitrate"] = track.estimatedDataRate
        result["extension"] = ext
      }

      return result
    }

    // MARK: - Background Task

    AsyncFunction("activateBackgroundTask") { () -> String in
      guard self.backgroundTaskId == .invalid else {
        throw CompressorError.backgroundTask("Background task already active")
      }

      self.backgroundTaskId = UIApplication.shared.beginBackgroundTask(withName: "video-compress") {
        self.sendEvent("onBackgroundTaskExpired", [:])
        UIApplication.shared.endBackgroundTask(self.backgroundTaskId)
        self.backgroundTaskId = .invalid
      }

      return "\(self.backgroundTaskId.rawValue)"
    }

    AsyncFunction("deactivateBackgroundTask") { () in
      guard self.backgroundTaskId != .invalid else {
        throw CompressorError.backgroundTask("No active background task")
      }
      UIApplication.shared.endBackgroundTask(self.backgroundTaskId)
      self.backgroundTaskId = .invalid
    }
  }

  // MARK: - VTCompressionSession (Hardware Accelerated)

  private func compressWithVTSession(
    asset: AVURLAsset,
    videoTrack: AVAssetTrack,
    outputURL: URL,
    outputWidth: Int,
    outputHeight: Int,
    outputBitrate: Int,
    codec: String,
    speed: String,
    uuid: String,
    progressDivider: Int
  ) async throws {
    // Set up reader
    let reader = try AVAssetReader(asset: asset)
    let readerSettings: [String: Any] = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
    ]
    let readerOutput = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: readerSettings)
    readerOutput.alwaysCopiesSampleData = false // Zero-copy optimization
    reader.add(readerOutput)

    // Set up writer
    let codecType: AVVideoCodecType = codec == "hevc" ? .hevc : .h264
    let profileLevel: String = codec == "hevc"
      ? kVTProfileLevel_HEVC_Main_AutoLevel as String
      : kVTProfileLevel_H264_High_AutoLevel as String

    var compressionProps: [String: Any] = [
      AVVideoAverageBitRateKey: outputBitrate,
      AVVideoProfileLevelKey: profileLevel,
    ]

    // Speed optimizations
    if speed == "ultrafast" {
      compressionProps[AVVideoAllowFrameReorderingKey] = false
      compressionProps[kVTCompressionPropertyKey_RealTime as String] = true
    } else if speed == "fast" {
      compressionProps[AVVideoAllowFrameReorderingKey] = false
    }

    let videoSettings: [String: Any] = [
      AVVideoCodecKey: codecType,
      AVVideoWidthKey: outputWidth,
      AVVideoHeightKey: outputHeight,
      AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
      AVVideoCompressionPropertiesKey: compressionProps,
    ]

    let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
    writerInput.expectsMediaDataInRealTime = speed == "ultrafast"
    writerInput.transform = videoTrack.preferredTransform

    let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
    writer.shouldOptimizeForNetworkUse = true
    writer.add(writerInput)

    // Audio: decompress to PCM then re-encode to AAC
    var audioReaderOutput: AVAssetReaderTrackOutput?
    var audioWriterInput: AVAssetWriterInput?

    if let audioTrack = asset.tracks(withMediaType: .audio).first {
      // Read as decompressed Linear PCM so writer can re-encode to AAC
      let audioDecompressSettings: [String: Any] = [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVLinearPCMIsBigEndianKey: false,
        AVLinearPCMIsFloatKey: false,
        AVLinearPCMBitDepthKey: 16,
        AVLinearPCMIsNonInterleaved: false,
      ]
      let audioOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: audioDecompressSettings)
      audioOutput.alwaysCopiesSampleData = false
      reader.add(audioOutput)
      audioReaderOutput = audioOutput

      let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
        AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVEncoderBitRateKey: 128000,
        AVNumberOfChannelsKey: 2,
        AVSampleRateKey: 44100.0,
      ])
      writer.add(audioInput)
      audioWriterInput = audioInput
    }

    // Start processing
    reader.startReading()
    writer.startWriting()
    writer.startSession(atSourceTime: .zero)

    let totalDuration = CMTimeGetSeconds(asset.duration)
    var lastReportedProgress: Int = 0

    // Process video frames
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      let videoQueue = DispatchQueue(label: "com.bayut.videocompressor.video")
      let audioQueue = DispatchQueue(label: "com.bayut.videocompressor.audio")

      let group = DispatchGroup()

      // Video processing
      group.enter()
      writerInput.requestMediaDataWhenReady(on: videoQueue) {
        while writerInput.isReadyForMoreMediaData {
          // Check writer is still valid
          guard writer.status == .writing else {
            writerInput.markAsFinished()
            group.leave()
            return
          }

          if self.cancelledUUIDs.contains(uuid) {
            reader.cancelReading()
            writerInput.markAsFinished()
            group.leave()
            return
          }

          if let sampleBuffer = readerOutput.copyNextSampleBuffer() {
            if !writerInput.append(sampleBuffer) {
              writerInput.markAsFinished()
              group.leave()
              return
            }

            // Progress
            let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
            let progress = Float(pts / totalDuration)
            let roundedProgress = Int(progress * 100)

            if progressDivider == 0 || (roundedProgress % max(progressDivider, 1) == 0 && roundedProgress > lastReportedProgress) {
              lastReportedProgress = roundedProgress
              self.sendEvent("onCompressProgress", [
                "progress": min(progress, 1.0),
                "uuid": uuid
              ])
            }
          } else {
            writerInput.markAsFinished()
            group.leave()
            return
          }
        }
      }

      // Audio processing
      if let audioOutput = audioReaderOutput, let audioInput = audioWriterInput {
        group.enter()
        audioInput.requestMediaDataWhenReady(on: audioQueue) {
          while audioInput.isReadyForMoreMediaData {
            guard writer.status == .writing else {
              audioInput.markAsFinished()
              group.leave()
              return
            }

            if let sampleBuffer = audioOutput.copyNextSampleBuffer() {
              if !audioInput.append(sampleBuffer) {
                audioInput.markAsFinished()
                group.leave()
                return
              }
            } else {
              audioInput.markAsFinished()
              group.leave()
              return
            }
          }
        }
      }

      group.notify(queue: .main) {
        continuation.resume()
      }
    }

    // Finalize
    if self.cancelledUUIDs.contains(uuid) {
      writer.cancelWriting()
      self.cancelledUUIDs.remove(uuid)
      throw CompressorError.cancelled
    }

    await writer.finishWriting()

    if writer.status == .failed {
      throw writer.error ?? CompressorError.unknown
    }

    // Send 100% progress
    self.sendEvent("onCompressProgress", [
      "progress": 1.0,
      "uuid": uuid
    ])
  }

  // MARK: - Helpers

  private func resolveFileURL(_ url: URL) -> URL {
    if url.scheme == "file" || url.scheme == nil {
      return url
    }
    // Handle ph:// and other schemes if needed
    return url
  }

  private func calculateOutputSize(sourceWidth: CGFloat, sourceHeight: CGFloat, maxSize: CGFloat) -> (CGFloat, CGFloat) {
    let isPortrait = sourceHeight > sourceWidth
    let scale: CGFloat

    if isPortrait {
      scale = min(maxSize / sourceHeight, 1.0)
    } else {
      scale = min(maxSize / sourceWidth, 1.0)
    }

    // Round to even numbers (required by video encoders)
    let w = round(sourceWidth * scale / 2.0) * 2.0
    let h = round(sourceHeight * scale / 2.0) * 2.0

    return (w, h)
  }

  private func calculateAutoBitrate(width: Int, height: Int, sourceBitrate: Int, speed: String) -> Int {
    let pixels = width * height
    let compressFactor: Float

    switch speed {
    case "ultrafast":
      compressFactor = 0.9
    case "fast":
      compressFactor = 0.8
    default:
      compressFactor = 0.7
    }

    let baseBitrate = Float(pixels) * 1.5
    let targetBitrate = Int(baseBitrate * compressFactor)
    let maxBitrate = 5_000_000

    return min(max(targetBitrate, 500_000), min(sourceBitrate, maxBitrate))
  }

  private func createTempURL() -> URL {
    let tempDir = FileManager.default.temporaryDirectory
    let filename = ProcessInfo.processInfo.globallyUniqueString + ".mp4"
    return tempDir.appendingPathComponent(filename)
  }

  private func getFileSizeMB(url: URL) -> Double {
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
          let size = attrs[.size] as? UInt64 else { return 0 }
    return Double(size) / (1024 * 1024)
  }

  private func getFileSizeBytes(url: URL) -> UInt64 {
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
          let size = attrs[.size] as? UInt64 else { return 0 }
    return size
  }
}

// MARK: - Errors

enum CompressorError: Error, LocalizedError {
  case invalidVideo(String)
  case cancelled
  case backgroundTask(String)
  case unknown

  var errorDescription: String? {
    switch self {
    case .invalidVideo(let msg): return "Invalid video: \(msg)"
    case .cancelled: return "Compression was cancelled"
    case .backgroundTask(let msg): return msg
    case .unknown: return "Unknown compression error"
    }
  }
}
