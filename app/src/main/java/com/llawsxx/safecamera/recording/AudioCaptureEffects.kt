package com.llawsxx.safecamera.recording

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

internal class AudioCaptureEffects private constructor(
    private var automaticGainControl: AutomaticGainControl?,
    private var noiseSuppressor: NoiseSuppressor?,
    private var echoCanceler: AcousticEchoCanceler?,
) {
    fun release() {
        runCatching { automaticGainControl?.enabled = false }
        runCatching { noiseSuppressor?.enabled = false }
        runCatching { echoCanceler?.enabled = false }
        runCatching { automaticGainControl?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        automaticGainControl = null
        noiseSuppressor = null
        echoCanceler = null
    }

    companion object {
        fun create(audioSessionId: Int, config: RecordingConfig): AudioCaptureEffects {
            var agc: AutomaticGainControl? = null
            var ns: NoiseSuppressor? = null
            var aec: AcousticEchoCanceler? = null
            try {
                if (config.audioAutomaticGainControl) {
                    check(AutomaticGainControl.isAvailable()) { "当前设备不支持自动增益控制（AGC）" }
                    agc = checkNotNull(AutomaticGainControl.create(audioSessionId)) {
                        "无法为当前音频输入创建自动增益控制（AGC）"
                    }.apply {
                        enabled = true
                        check(this.enabled) { "当前音频输入无法启用自动增益控制（AGC）" }
                    }
                }
                if (config.audioDisableNoiseSuppressor) {
                    check(NoiseSuppressor.isAvailable()) { "当前设备不支持系统降噪（NS）控制" }
                    ns = checkNotNull(NoiseSuppressor.create(audioSessionId)) {
                        "无法为当前音频输入创建系统降噪（NS）控制"
                    }.apply {
                        enabled = false
                        check(!this.enabled) { "当前音频输入无法关闭系统降噪（NS）" }
                    }
                }
                if (config.audioDisableEchoCanceler) {
                    check(AcousticEchoCanceler.isAvailable()) { "当前设备不支持回声消除（AEC）控制" }
                    aec = checkNotNull(AcousticEchoCanceler.create(audioSessionId)) {
                        "无法为当前音频输入创建回声消除（AEC）控制"
                    }.apply {
                        enabled = false
                        check(!this.enabled) { "当前音频输入无法关闭回声消除（AEC）" }
                    }
                }
                return AudioCaptureEffects(agc, ns, aec)
            } catch (t: Throwable) {
                runCatching { agc?.release() }
                runCatching { ns?.release() }
                runCatching { aec?.release() }
                throw t
            }
        }
    }
}
