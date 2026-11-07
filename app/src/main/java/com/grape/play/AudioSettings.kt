package com.grape.play

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager

object AudioSettings {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { if (!::sp.isInitialized) sp = ctx.getSharedPreferences("audio", Context.MODE_PRIVATE) }

    /** false = PCM 16-bit (tương thích, mặc định); true = giữ 24-bit dạng float 32-bit. */
    var hires: Boolean
        get() = sp.getBoolean("hires", false)
        set(v) { sp.edit().putBoolean("hires", v).apply() }
    /** Tốc độ PCM khi chuyển DSD → PCM (Hz): 44100 / 88200 / 176400. */
    var dsdRate: Int
        get() = if (::sp.isInitialized) sp.getInt("dsdRate", 176400) else 176400
        set(v) { sp.edit().putInt("dsdRate", v).apply() }
    /** Cách gửi DSD tới thiết bị UPnP: DSD_RAW = file DSF/DFF gốc; DSD_DOP = DSD64 gói trong PCM 24-bit/176,4 kHz. */
    const val DSD_RAW = 0
    const val DSD_DOP = 1
    var dsdRemote: Int
        get() = when {
            !::sp.isInitialized -> DSD_RAW
            sp.contains("dsdRemote") -> if (sp.getInt("dsdRemote", DSD_RAW) == DSD_DOP) DSD_DOP else DSD_RAW   // 2 (PCM, bản 0.8.2) đã bỏ → file gốc
            sp.getBoolean("dopRemote", false) -> DSD_DOP      // tuỳ chọn cũ (bản ≤ 0.8.1)
            else -> DSD_RAW
        }
        set(v) { sp.edit().putInt("dsdRemote", if (v == DSD_DOP) DSD_DOP else DSD_RAW).apply() }
    var dopRemote: Boolean
        get() = dsdRemote == DSD_DOP
        set(v) { dsdRemote = if (v) DSD_DOP else DSD_RAW }
    /** Loại thiết bị đầu ra ưu tiên (AudioDeviceInfo.TYPE_*), 0 = mặc định hệ thống. */
    var deviceType: Int
        get() = sp.getInt("devType", 0)
        set(v) { sp.edit().putInt("devType", v).apply() }

    /** Đầu ra mặc định khi mở app: KEEP = giữ lựa chọn lần trước; 0 = mặc định hệ thống; còn lại = loại thiết bị (AudioDeviceInfo.TYPE_*). */
    const val KEEP = -1
    var startupDev: Int
        get() = if (::sp.isInitialized) sp.getInt("startupDev", KEEP) else KEEP
        set(v) { sp.edit().putInt("startupDev", v).apply() }
    /** Gọi một lần khi app khởi động, trước khi dựng bộ phát. */
    fun applyStartup() { if (startupDev != KEEP) deviceType = startupDev }

    fun label(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loa TV"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analogue (line)"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital / Quang (S/PDIF)"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Tai nghe có dây"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        else -> "Thiết bị loại $type"
    }
    fun sinks(ctx: Context): List<AudioDeviceInfo> =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }.distinctBy { it.type }
    fun current(ctx: Context): AudioDeviceInfo? = if (deviceType == 0) null else sinks(ctx).firstOrNull { it.type == deviceType }
    fun currentName(ctx: Context) = when {
        deviceType == 0 -> "Mặc định hệ thống"
        current(ctx) == null -> label(deviceType) + " (chưa kết nối)"   // chưa cắm: tạm dùng mặc định, tự chuyển khi cắm
        else -> label(deviceType)
    }
}
