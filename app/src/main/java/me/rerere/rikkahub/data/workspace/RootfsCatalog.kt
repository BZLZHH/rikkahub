package me.rerere.rikkahub.data.workspace

import android.os.Build

/** 可以装进工作区的发行版。 */
enum class RootfsDistro(val label: String, val note: String) {
    ARCH("Arch Linux", "滚动更新 · 默认"),
    UBUNTU("Ubuntu 24.04", "LTS · 更省心"),
}

/**
 * rootfs 目录: 发行版 × 架构 → 下载地址。
 *
 * 两件事在这里汇合:
 *
 * 1. **默认 Arch Linux**（用户指定的默认）。
 * 2. **架构必须按设备取**。原来 DEFAULT_ROOTFS_URL 是写死的 arm64 Ubuntu 地址 ——
 *    在 x86_64 设备上默认就会去下一个架构不对的 rootfs, 解压成功、shell 却起不来,
 *    而错误信息只会说"执行失败", 完全指不到"下错架构"上。
 *
 * Arch 的情况比 Ubuntu 绕一点, 因为它在两个地方发布:
 * - **arm64 / armv7**: ArchLinuxARM 提供 *-latest.tar.gz（地址稳定, 压缩格式本来就支持）;
 * - **x86_64**: 官方只发 archlinux-bootstrap-x86_64.tar.zst —— 所以安装器为此补了 zstd 解压。
 */
object RootfsCatalog {

    const val AMD64 = "amd64"
    const val ARM64 = "arm64"
    const val ARMFH = "armhf"

    /** 默认发行版。 */
    val defaultDistro: RootfsDistro = RootfsDistro.ARCH

    val arches: List<String> = listOf(ARM64, AMD64, ARMFH)

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

    /** 某个发行版在某个架构上的下载地址; 该组合不存在时返回 null。 */
    fun url(distro: RootfsDistro, arch: String): String? = when (distro) {
        RootfsDistro.ARCH -> when (arch) {
            ARM64 -> "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
            ARMFH -> "http://os.archlinuxarm.org/os/ArchLinuxARM-armv7-latest.tar.gz"
            AMD64 -> "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst"
            else -> null
        }

        RootfsDistro.UBUNTU -> if (arch in arches) {
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-" +
                arch + ".tar.gz"
        } else {
            null
        }
    }

    /** 默认地址 = 默认发行版 × 设备架构; 万一那个组合不存在, 退到 Ubuntu 同架构。 */
    fun defaultUrl(): String =
        url(defaultDistro, deviceArch())
            ?: url(RootfsDistro.UBUNTU, deviceArch())
            ?: url(RootfsDistro.UBUNTU, ARM64)!!
}
