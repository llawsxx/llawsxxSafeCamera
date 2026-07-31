package com.llawsxx.safecamera.recording

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

internal class H26xVuiRewriter(
    private val codec: VideoCodec,
    private val range: VideoColorRange,
    private val standard: VideoColorStandard,
    private val matrix: VideoColorMatrix,
    private val transfer: VideoColorTransfer,
) {
    private var nalLengthSize = 4

    fun rewriteFormat(format: MediaFormat): MediaFormat {
        format.getByteBuffer("csd-0")?.let { csd ->
            val bytes = csd.bytes()
            nalLengthSize = when {
                codec == VideoCodec.H264 && bytes.size >= 5 && bytes[0] == 1.toByte() ->
                    (bytes[4].toInt() and 3) + 1
                codec == VideoCodec.H265 && bytes.size >= 22 && bytes[0] == 1.toByte() ->
                    (bytes[21].toInt() and 3) + 1
                else -> 4
            }
        }
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            format.getByteBuffer(key)?.let { source ->
                format.setByteBuffer(key, ByteBuffer.wrap(rewrite(source.bytes())))
            }
        }
        range.mediaFormatValue?.let { format.setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
        standard.mediaFormatValue?.let { format.setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
        transfer.mediaFormatValue?.let { format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
        return format
    }

    fun rewriteKeyFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo): RewrittenSample {
        val bytes = ByteArray(info.size).also { output ->
            buffer.duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            }.get(output)
        }
        val rewritten = rewrite(bytes)
        val rewrittenInfo = MediaCodec.BufferInfo().apply {
            set(0, rewritten.size, info.presentationTimeUs, info.flags)
        }
        return RewrittenSample(ByteBuffer.wrap(rewritten), rewrittenInfo)
    }

    private fun rewrite(input: ByteArray): ByteArray = checkNotNull(
        nativeRewrite(
            input,
            if (codec == VideoCodec.H265) CODEC_H265 else CODEC_H264,
            nalLengthSize,
            range.vuiFullRange,
            standard.vuiPrimaries,
            transfer.vuiValue,
            matrix.vuiValue,
        ),
    ) { "Unable to rewrite H.26x SPS/VUI" }

    private fun ByteBuffer.bytes(): ByteArray =
        ByteArray(remaining()).also { duplicate().get(it) }

    private external fun nativeRewrite(
        input: ByteArray,
        codec: Int,
        nalLengthSize: Int,
        fullRange: Int,
        colourPrimaries: Int,
        transferCharacteristics: Int,
        matrixCoefficients: Int,
    ): ByteArray?

    companion object {
        private const val CODEC_H264 = 1
        private const val CODEC_H265 = 2

        init {
            System.loadLibrary("safecamera_mpegts")
        }
    }
}

internal data class RewrittenSample(
    val buffer: ByteBuffer,
    val info: MediaCodec.BufferInfo,
)

internal class VuiRewritingMuxCoordinator(
    private val delegate: EncodedMuxCoordinator,
    private val rewriter: H26xVuiRewriter,
) : EncodedMuxCoordinator {
    override fun setVideoFormat(format: MediaFormat) {
        delegate.setVideoFormat(rewriter.rewriteFormat(format))
    }

    override fun setAudioFormat(format: MediaFormat) = delegate.setAudioFormat(format)

    override fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
            val sample = rewriter.rewriteKeyFrame(buffer, info)
            delegate.writeVideo(sample.buffer, sample.info)
        } else {
            delegate.writeVideo(buffer, info)
        }
    }

    override fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        delegate.writeAudio(buffer, info)

    override fun finish() = delegate.finish()
}
