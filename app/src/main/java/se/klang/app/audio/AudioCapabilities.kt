package se.klang.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import se.klang.sessions.InputDeviceInfo

/** What this phone says it can do. Claims only: real behaviour must be verified by test recordings. */
data class CapabilityReport(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** Raw value of PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED (null = not reported). */
    val unprocessedProperty: String?,
    val unprocessedSupported: Boolean,
    val nativeOutputSampleRate: String?,
    val inputs: List<InputDeviceInfo>,
    /** Sample rate -> encodings for which AudioRecord reports a valid minimum buffer (mono). */
    val formats: Map<Int, List<String>>,
    val agcAvailable: Boolean,
    val noiseSuppressorAvailable: Boolean,
    val echoCancelerAvailable: Boolean,
) {
    val preferredSource: String get() = if (unprocessedSupported) "UNPROCESSED" else "VOICE_RECOGNITION"
}

object AudioCapabilities {
    val CANDIDATE_RATES = listOf(48_000, 44_100)

    fun probe(context: Context): CapabilityReport {
        val am = context.getSystemService(AudioManager::class.java)
        val prop = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        val formats = CANDIDATE_RATES.associateWith { rate ->
            listOf(AudioFormat.ENCODING_PCM_FLOAT to "PCM_FLOAT", AudioFormat.ENCODING_PCM_16BIT to "PCM_16BIT")
                .filter { (enc, _) -> AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, enc) > 0 }
                .map { it.second }
        }
        return CapabilityReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            unprocessedProperty = prop,
            unprocessedSupported = prop.equals("true", ignoreCase = true),
            nativeOutputSampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE),
            inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map(::describe),
            formats = formats,
            agcAvailable = AutomaticGainControl.isAvailable(),
            noiseSuppressorAvailable = NoiseSuppressor.isAvailable(),
            echoCancelerAvailable = AcousticEchoCanceler.isAvailable(),
        )
    }

    fun builtInMic(context: Context): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun describe(d: AudioDeviceInfo) = InputDeviceInfo(
        id = d.id,
        type = typeName(d.type),
        productName = d.productName?.toString().orEmpty(),
        address = d.address.orEmpty(),
    )

    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        else -> "TYPE_$type"
    }
}
