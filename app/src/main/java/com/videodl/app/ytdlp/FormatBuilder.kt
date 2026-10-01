package com.videodl.app.ytdlp

import com.yausername.youtubedl_android.mapper.VideoFormat
import java.util.Locale

/**
 * 把 yt-dlp 返回的原始格式列表整理成用户能理解、且能直接执行的画质选项。
 *
 * 规则（对应计划里「默认预选各自最高可用画质」「优先生成带声音的 MP4」）：
 *  - 只保留真正带画面的格式，按分辨率分档，每档取码率最高的一条；
 *  - 纯视频流与最佳音频流组合，交给 FFmpeg 合并，预估体积为两者之和；
 *  - 已在服务端合并好音视频的单一格式直接按 formatId 下载；
 *  - 视频编码/音频编码无法装进 MP4 时，在选项上标注真实会保存的容器。
 */
object FormatBuilder {

    private const val UNKNOWN = 0L

    fun build(formats: List<VideoFormat>): List<FormatOption> {
        val usable = formats.filter { !it.formatId.isNullOrBlank() && !isStoryboard(it) }

        if (usable.isEmpty()) return emptyList()

        val videos = usable.filter { isVideo(it) && it.height > 0 }
        val audios = usable.filter { isAudioOnly(it) && it.abr > 0 || isAudioOnly(it) }
        val bestAudio = audios.maxByOrNull { it.abr * 1000 + it.tbr }
        val audioBytes = bestAudio?.let { sizeOf(it) } ?: UNKNOWN

        val perHeight = videos
            .groupBy { it.height }
            .mapValues { (_, group) -> group.maxBy { it.tbr * 100 + it.fps } }

        val options = perHeight.entries
            .sortedByDescending { it.key }
            .map { (height, format) ->
                val selfContained = !isVideoOnly(format)
                val needsMerge = !selfContained

                val selector = if (selfContained) {
                    format.formatId.orEmpty()
                } else {
                    "bestvideo[height=$height]+bestaudio/best[height=$height]"
                }

                val videoBytes = sizeOf(format)
                val approx = when {
                    videoBytes == UNKNOWN -> UNKNOWN
                    needsMerge && audioBytes != UNKNOWN -> videoBytes + audioBytes
                    needsMerge -> UNKNOWN
                    else -> videoBytes
                }

                FormatOption(
                    id = "h$height-${format.formatId}",
                    qualityLabel = qualityLabel(height, format.fps),
                    detail = detailOf(format, needsMerge),
                    selector = selector,
                    height = height,
                    approxBytes = approx,
                    needsMerge = needsMerge,
                    containerNote = containerNote(format, needsMerge),
                )
            }
            .toMutableList()

        if (options.isEmpty()) {
            // 拿不到分档信息时退回到让 yt-dlp 自己挑最优组合
            options += FormatOption(
                id = "best",
                qualityLabel = "最佳可用",
                detail = "自动选择最佳画面与音轨，优先合并为 MP4",
                selector = "bestvideo+bestaudio/best",
                height = 0,
                approxBytes = UNKNOWN,
                needsMerge = true,
                containerNote = null,
            )
        }

        return options
    }

    private fun qualityLabel(height: Int, fps: Int): String =
        if (fps >= 50) "${height}p$fps" else "${height}p"

    private fun detailOf(format: VideoFormat, needsMerge: Boolean): String {
        val parts = mutableListOf<String>()
        parts += containerName(format.ext)
        videoCodecName(format.vcodec)?.let { parts += it }
        parts += if (needsMerge) "需合并音轨" else "含音轨"
        sizeLabel(sizeOf(format))?.let { parts += "约 $it" }
        return parts.joinToString(" · ")
    }

    /**
     * 判断最终能不能是 MP4。MP4 容器对 VP8/VP9/AV1 + Opus/Vorbis 的组合支持不佳，
     * 这种情况下 yt-dlp 会改存 WebM/MKV，必须提前告诉用户。
     */
    private fun containerNote(format: VideoFormat, needsMerge: Boolean): String? {
        val v = format.vcodec.orEmpty().lowercase(Locale.ROOT)
        if (v.startsWith("vp8") || v.startsWith("vp9") || v.startsWith("vp0")) {
            return if (needsMerge) "源为 WebM 系编码，可能保存为 WebM（无法封装为 MP4）"
            else "源为 WebM 系编码，将保存为 WebM"
        }
        return null
    }

    private fun containerName(ext: String?): String = when (ext?.lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> "MP4"
        "webm" -> "WebM"
        "mkv" -> "MKV"
        "flv" -> "FLV"
        "mov" -> "MOV"
        "m4a" -> "M4A"
        "mp3" -> "MP3"
        "opus" -> "OPUS"
        else -> ext?.uppercase(Locale.ROOT) ?: "未知容器"
    }

    private fun videoCodecName(vcodec: String?): String? {
        val codec = vcodec.orEmpty().lowercase(Locale.ROOT)
        return when {
            codec.isEmpty() || codec == "none" || codec == "unknown" -> null
            codec.startsWith("avc1") || codec.startsWith("h264") -> "H.264"
            codec.startsWith("hev1") || codec.startsWith("hvc1") -> "H.265"
            codec.startsWith("av01") -> "AV1"
            codec.startsWith("vp9") || codec.startsWith("vp09") -> "VP9"
            codec.startsWith("vp8") -> "VP8"
            else -> codec.substringBefore('.').uppercase(Locale.ROOT)
        }
    }

    private fun isVideo(format: VideoFormat): Boolean {
        val vcodec = format.vcodec.orEmpty()
        return vcodec.isNotEmpty() && !vcodec.equals("none", ignoreCase = true)
    }

    private fun isVideoOnly(format: VideoFormat): Boolean {
        val acodec = format.acodec.orEmpty()
        return acodec.isEmpty() || acodec.equals("none", ignoreCase = true)
    }

    private fun isAudioOnly(format: VideoFormat): Boolean {
        val acodec = format.acodec.orEmpty()
        val hasAudio = acodec.isNotEmpty() && !acodec.equals("none", ignoreCase = true)
        return hasAudio && !isVideo(format)
    }

    private fun isStoryboard(format: VideoFormat): Boolean {
        val note = format.formatNote.orEmpty().lowercase(Locale.ROOT)
        val ext = format.ext.orEmpty().lowercase(Locale.ROOT)
        return note.contains("storyboard") || ext == "mhtml"
    }

    private fun sizeOf(format: VideoFormat): Long {
        val exact = format.fileSize
        if (exact > 0) return exact
        val approx = format.fileSizeApproximate
        return if (approx > 0) approx else UNKNOWN
    }

    fun sizeLabel(bytes: Long): String? {
        if (bytes <= 0) return null
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}