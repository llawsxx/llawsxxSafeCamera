package com.llawsxx.safecamera.recording

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class AudioInputInfo(
    val id: Int,
    val name: String,
    val typeLabel: String,
) {
    val displayName: String get() = "$name · $typeLabel · ID $id"
}

object AudioInputDevices {
    fun query(context: Context): List<AudioInputInfo> = devices(context).map { device ->
        AudioInputInfo(
            id = device.id,
            name = device.productName?.toString()?.takeIf(String::isNotBlank) ?: "未命名设备",
            typeLabel = typeLabel(device.type),
        )
    }.sortedWith(compareBy<AudioInputInfo> { it.typeLabel }.thenBy { it.name }.thenBy { it.id })

    fun find(context: Context, id: Int): AudioDeviceInfo? = devices(context).firstOrNull { it.id == id }

    private fun devices(context: Context): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话麦克风"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频设备"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音频配件"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳麦"
        AudioDeviceInfo.TYPE_TELEPHONY -> "电话音频"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路输入"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路输入"
        AudioDeviceInfo.TYPE_AUX_LINE -> "辅助线路输入"
        AudioDeviceInfo.TYPE_IP -> "网络音频设备"
        AudioDeviceInfo.TYPE_BUS -> "音频总线"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 耳麦"
        else -> "输入设备类型 $type"
    }
}
