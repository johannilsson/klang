package se.klang.dsp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sample encodings we read and write. Matches the Android AudioFormat encodings we capture with. */
enum class WavEncoding(val bitsPerSample: Int, val formatTag: Int) {
    PCM_16(16, 1),
    PCM_24(24, 1),
    PCM_32(32, 1),
    FLOAT_32(32, 3),
}

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val encoding: WavEncoding,
    val frames: Int,
)

class WavFormatException(message: String) : Exception(message)

/**
 * Minimal RIFF/WAVE reader and writer. Supports PCM 16/24/32-bit integer and
 * 32-bit IEEE float, including WAVE_FORMAT_EXTENSIBLE headers.
 *
 * Integer samples are normalised by 2^(bits-1), so PCM16 maps exactly onto
 * [-1, 1 - 2^-15]. Multichannel files are reduced to a single channel by
 * taking [channel] (never by summing, which would change levels and could hide clipping).
 */
object Wav {
    data class Decoded(val info: WavInfo, val signal: PcmSignal)

    fun read(input: InputStream, channel: Int = 0): Decoded = read(input.readBytes(), channel)

    fun read(bytes: ByteArray, channel: Int = 0): Decoded {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || fourCc(buf, 0) != "RIFF" || fourCc(buf, 8) != "WAVE") {
            throw WavFormatException("Not a RIFF/WAVE file")
        }
        var pos = 12
        var fmt: WavInfo? = null
        var blockAlign = 0
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = fourCc(buf, pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body.toLong() + size > bytes.size) {
                // Tolerate a truncated final data chunk (common after an interrupted capture).
                if (id == "data") {
                    dataOffset = body; dataSize = bytes.size - body
                }
                break
            }
            when (id) {
                "fmt " -> {
                    var tag = buf.getShort(body).toInt() and 0xffff
                    val channels = buf.getShort(body + 2).toInt()
                    val rate = buf.getInt(body + 4)
                    blockAlign = buf.getShort(body + 12).toInt()
                    val bits = buf.getShort(body + 14).toInt()
                    if (tag == 0xFFFE && size >= 40) tag = buf.getShort(body + 24).toInt() and 0xffff
                    val enc = when {
                        tag == 1 && bits == 16 -> WavEncoding.PCM_16
                        tag == 1 && bits == 24 -> WavEncoding.PCM_24
                        tag == 1 && bits == 32 -> WavEncoding.PCM_32
                        tag == 3 && bits == 32 -> WavEncoding.FLOAT_32
                        else -> throw WavFormatException("Unsupported WAV encoding: tag=$tag bits=$bits")
                    }
                    if (channels < 1) throw WavFormatException("Invalid channel count $channels")
                    fmt = WavInfo(rate, channels, enc, 0)
                }
                "data" -> {
                    dataOffset = body; dataSize = size
                }
            }
            pos = body + size + (size and 1)
        }
        val info = fmt ?: throw WavFormatException("Missing fmt chunk")
        if (dataOffset < 0) throw WavFormatException("Missing data chunk")
        require(channel in 0 until info.channels) { "Channel $channel not present (file has ${info.channels})" }
        val bytesPerSample = info.encoding.bitsPerSample / 8
        val frameBytes = if (blockAlign > 0) blockAlign else bytesPerSample * info.channels
        val frames = dataSize / frameBytes
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            val p = dataOffset + f * frameBytes + channel * bytesPerSample
            out[f] = when (info.encoding) {
                WavEncoding.PCM_16 -> buf.getShort(p) / 32768f
                WavEncoding.PCM_24 -> {
                    val v = (bytes[p].toInt() and 0xff) or
                        ((bytes[p + 1].toInt() and 0xff) shl 8) or
                        (bytes[p + 2].toInt() shl 16)
                    (v / 8388608.0).toFloat()
                }
                WavEncoding.PCM_32 -> (buf.getInt(p) / 2147483648.0).toFloat()
                WavEncoding.FLOAT_32 -> buf.getFloat(p)
            }
        }
        return Decoded(info.copy(frames = frames), PcmSignal(out, info.sampleRate))
    }

    /** Writes mono samples. Integer encodings are clamped to the representable range. */
    fun write(output: OutputStream, samples: FloatArray, sampleRate: Int, encoding: WavEncoding) {
        val bytesPerSample = encoding.bitsPerSample / 8
        val dataSize = samples.size * bytesPerSample
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16)
            .putShort(encoding.formatTag.toShort()).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * bytesPerSample)
            .putShort(bytesPerSample.toShort()).putShort(encoding.bitsPerSample.toShort())
        header.put("data".toByteArray()).putInt(dataSize)
        output.write(header.array())
        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            when (encoding) {
                WavEncoding.PCM_16 -> body.putShort(Math.round(s * 32768.0).coerceIn(-32768, 32767).toShort())
                WavEncoding.PCM_24 -> {
                    val v = Math.round(s * 8388608.0).coerceIn(-8388608, 8388607).toInt()
                    body.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
                }
                WavEncoding.PCM_32 -> body.putInt(Math.round(s * 2147483648.0).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                WavEncoding.FLOAT_32 -> body.putFloat(s)
            }
        }
        output.write(body.array())
        output.flush()
    }

    fun toBytes(samples: FloatArray, sampleRate: Int, encoding: WavEncoding): ByteArray =
        ByteArrayOutputStream().also { write(it, samples, sampleRate, encoding) }.toByteArray()

    private fun fourCc(buf: ByteBuffer, at: Int): String =
        String(CharArray(4) { (buf.get(at + it).toInt() and 0xff).toChar() })
}
