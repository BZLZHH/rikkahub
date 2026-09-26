package me.rerere.rikkahub.data.workspace

import android.os.Build

/** 可以装进工作区的发行版。 */
enum class RootfsDistro(val label: String, val note: String) {
    ARCH("Arch Linux", "滚动更新 · 默认"),
    UBUNTU("Ubuntu 24.04", "LTS · 更省心"),
}

/**
 * 下载源。
 *
 * 默认**中科大**（用户指定）: 国内直连快得多, 而 rootfs 是上百 MB 的下载 ——
 * 走官方源在有些网络下会慢到让人以为卡死了。官方源仍然保留, 供海外或中科大故障时切。
 */
enum class RootfsMirror(val label: String, val note: String) {
    USTC("中科大", "国内 · 默认"),
    OFFICIAL("官方", "上游原站"),
}

/**
 * rootfs 目录: 发行版 × 架构 × 镜像源 → 下载地址。
 *
 * 三件事在这里汇合:
 *
 * 1. **默认 Arch Linux** + **默认中科大源**（都是用户指定的默认）;
 * 2. **架构必须按设备取**。原来 DEFAULT_ROOTFS_URL 是写死的 arm64 地址 ——
 *    在 x86_64 设备上默认就会去下一个架构不对的 rootfs: 解压成功、shell 却起不来,
 *    而错误信息只会说"执行失败", 完全指不到"下错架构"上;
 * 3. Arch 在两个地方发布, 路径形状完全不同:
 *    - arm64 / armv7 → ArchLinuxARM 的 *-latest.tar.gz（本来就支持）;
 *    - x86_64 → 官方只发 archlinux-bootstrap-x86_64.tar.zst（为此给安装器补了 zstd）。
 *
 * 下面每个地址都实际 curl 过（HTTP 200）。
 */
object RootfsCatalog {

    const val AMD64 = "amd64"
    const val ARM64 = "arm64"
    const val ARMFH = "armhf"

    val defaultDistro: RootfsDistro = RootfsDistro.ARCH
    val defaultMirror: RootfsMirror = RootfsMirror.USTC

    val arches: List<String> = listOf(ARM64, AMD64, ARMFH)

    private const val USTC = "https://mirrors.ustc.edu.cn"
    private const val UBUNTU_VERSION = "24.04"
    private const val UBUNTU_POINT = "24.04.3"

    /** 设备架构（用 amd64/arm64/armhf 这套叫法）。 */
    fun deviceArch(): String = archOf(Build.SUPPORTED_ABIS?.firstOrNull())

    /**
     * ABI → 架构名。
     *
     * 纯函数, 所以能单测 —— 而"下错架构"这个坑正落在这个映射上, 值得测。
     */
    fun archOf(abi: String?): String = when (abi) {
        "arm64-v8a" -> ARM64
        "x86_64" -> AMD64
        "armeabi-v7a", "armeabi" -> ARMFH
        // 兜底取 arm64: 绝大多数设备是它, 而且真错了用户还能手改地址
        else -> ARM64
    }

    fun archLabel(arch: String): String = when (arch) {
        ARM64 -> "arm64 (aarch64)"
        AMD64 -> "x86_64 (amd64)"
        ARMFH -> "armhf (32 位)"
        else -> arch
    }

    /** 某个组合的下载地址; 该组合不存在时返回 null。 */
    fun url(
        distro: RootfsDistro,
        arch: String,
        mirror: RootfsMirror = defaultMirror,
    ): String? = when (distro) {
        RootfsDistro.ARCH -> when (arch) {
            ARM64 -> archAlarm(mirror, "aarch64")
            ARMFH -> archAlarm(mirror, "armv7")
            AMD64 -> when (mirror) {
                RootfsMirror.USTC ->
                    USTC + "/archlinux/iso/latest/archlinux-bootstrap-x86_64.tar.zst"
                RootfsMirror.OFFICIAL ->
                    "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst"
            }
            else -> null
        }

        RootfsDistro.UBUNTU -> if (arch in arches) ubuntu(mirror, arch) else null
    }

    private fun archAlarm(mirror: RootfsMirror, arch: String): String {
        val host = when (mirror) {
            RootfsMirror.USTC -> USTC + "/archlinuxarm"
            RootfsMirror.OFFICIAL -> "http://os.archlinuxarm.org"
        }
        return host + "/os/ArchLinuxARM-" + arch + "-latest.tar.gz"
    }

    private fun ubuntu(mirror: RootfsMirror, arch: String): String {
        val host = when (mirror) {
            RootfsMirror.USTC -> USTC + "/ubuntu-cdimage"
            RootfsMirror.OFFICIAL -> "https://cdimage.ubuntu.com"
        }
        return host + "/ubuntu-base/releases/" + UBUNTU_VERSION +
            "/release/ubuntu-base-" + UBUNTU_POINT + "-base-" + arch + ".tar.gz"
    }

    /** 默认地址 = 默认发行版 × 设备架构 × 默认镜像源。 */
    fun defaultUrl(): String =
        url(defaultDistro, deviceArch())
            ?: url(RootfsDistro.UBUNTU, deviceArch())
            ?: url(RootfsDistro.UBUNTU, ARM64)!!
}
