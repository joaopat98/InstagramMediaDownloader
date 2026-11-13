package com.joaopat98.instagrammediadownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

internal object StorySegmentMuxer {
    fun save(context: Context, inputs: List<File>, folder: File, expectedMs: Long) {
        val output = File(folder, "story.mp4")
        val extractors = mutableListOf<MediaExtractor>()
        var expectsAudio = false
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val tracks = mutableListOf<Track>()
            for (input in inputs) {
                val inspector = MediaExtractor().also { extractors.add(it) }
                inspector.setDataSource(input.path)
                for (index in 0 until inspector.trackCount) {
                    val format = inspector.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val type = mime.substringBefore('/')
                    if (type !in setOf("audio", "video")) continue
                    check(tracks.none { it.type == type }) { "Multiple tracks of the same type" }
                    val reader = MediaExtractor().also { extractors.add(it) }
                    reader.setDataSource(input.path)
                    reader.selectTrack(index)
                    if (reader.sampleTrackIndex < 0) reader.seekTo(0, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    check(reader.sampleTrackIndex >= 0) { "No readable $type samples" }
                    tracks.add(Track(reader, muxer.addTrack(format), type))
                }
            }
            check(tracks.any { it.type == "video" }) { "No video track" }
            expectsAudio = tracks.any { it.type == "audio" }
            val endTimeUs = expectedMs * 1000
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = tracks.filter { it.reader.sampleTrackIndex >= 0 }
                    .minByOrNull { it.reader.sampleTime } ?: break
                val reader = track.reader
                val timestamp = reader.sampleTime
                // WebView can append samples outside the Story's displayed time window.
                if (timestamp < 0 || timestamp >= endTimeUs) {
                    reader.advance()
                    continue
                }
                check(reader.sampleSize in 1..buffer.capacity().toLong())
                check(reader.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
                buffer.clear()
                val size = reader.readSampleData(buffer, 0)
                check(size > 0)
                val flags = if (reader.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                if (track.type == "video" && track.samples == 0 && flags == 0) {
                    reader.advance()
                    continue
                }
                if (track.firstTime == -1L) track.firstTime = timestamp
                info.set(0, size, timestamp, flags)
                muxer.writeSampleData(track.outputIndex, buffer, info)
                track.lastTime = maxOf(track.lastTime, info.presentationTimeUs)
                track.samples++
                reader.advance()
            }
            check(tracks.all { it.samples > 0 && it.firstTime <= 500_000 &&
                abs(it.lastTime / 1000 - expectedMs) <= 500 }) {
                "Track durations did not match playback"
            }
            muxer.stop()
        } finally {
            muxer.release()
            extractors.forEach(MediaExtractor::release)
        }

        MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(output.path)
            check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
            val duration = checkNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            check(abs(duration - expectedMs) <= 1_000) { "Output duration did not match playback" }
            val audio = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            check(metadata.getFrameAtTime((expectedMs - 500).coerceAtLeast(0) * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST) != null) { "Could not decode a frame near the end" }
            check(!expectsAudio || audio) { "Output audio track is missing" }
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Souvenir_Story_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Souvenir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { destination ->
                output.inputStream().use { it.copyTo(destination) }
            }
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private data class Track(
        val reader: MediaExtractor,
        val outputIndex: Int,
        val type: String,
        var firstTime: Long = -1,
        var lastTime: Long = -1,
        var samples: Int = 0
    )
}
