package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rootfs 目录。
 *
 * 两条都是**真机上会出事的**语义, 不是形式:
 *
 * 1. 默认必须是 **Arch Linux**（用户指定的默认）;
 * 2. 架构必须跟着设备走 —— 原来默认地址是写死的 arm64, 在 x86_64 设备上会去下一个
 *    架构不对的 rootfs: 解压成功、shell 起不来, 而错误信息只会说"执行失败"。
 */
class RootfsCatalogTest {

    @Test
    fun defaultDistroIsArch() {
        assertEquals("默认发行版必须是 Arch Linux", RootfsDistro.ARCH, RootfsCatalog.defaultDistro)
    }

    @Test
    fun abiMapsToTheMatchingArch() {
        assertEquals(RootfsCatalog.ARM64, RootfsCatalog.archOf("arm64-v8a"))
        assertEquals(RootfsCatalog.AMD64, RootfsCatalog.archOf("x86_64"))
        assertEquals(RootfsCatalog.ARMFH, RootfsCatalog.archOf("armeabi-v7a"))
        assertEquals(RootfsCatalog.ARMFH, RootfsCatalog.archOf("armeabi"))
    }

    @Test
    fun unknownAbiFallsBackToArm64() {
        // 拿不到 ABI 时也要给出一个能用的地址, 而不是 null
        assertEquals(RootfsCatalog.ARM64, RootfsCatalog.archOf(null))
        assertEquals(RootfsCatalog.ARM64, RootfsCatalog.archOf("mips"))
    }

    @Test
    fun archUrlNeverPointsAtAnotherArch() {
        // 这条是核心: 地址里的架构名必须与请求的架构一致。
        // "下错架构"这个坑就落在这里。
        RootfsCatalog.arches.forEach { arch ->
            val url = RootfsCatalog.url(RootfsDistro.ARCH, arch)
            assertNotNull("Arch 在 " + arch + " 上应当有地址", url)
            val expectedMarker = when (arch) {
                RootfsCatalog.ARM64 -> "aarch64"
                RootfsCatalog.ARMFH -> "armv7"
                RootfsCatalog.AMD64 -> "x86_64"
                else -> arch
            }
            assertTrue(
                "地址里应当带 " + expectedMarker + ", 实际是 " + url,
                url!!.contains(expectedMarker),
            )
        }
    }

    @Test
    fun ubuntuUrlCarriesTheArchSuffix() {
        RootfsCatalog.arches.forEach { arch ->
            val url = RootfsCatalog.url(RootfsDistro.UBUNTU, arch)
            assertNotNull(url)
            assertTrue("Ubuntu 地址应当以架构结尾: " + url, url!!.endsWith("-base-" + arch + ".tar.gz"))
        }
    }

    @Test
    fun archIsNeverReusedAcrossDistros() {
        // armhf 在 Arch 那边是 armv7 的包, 在 Ubuntu 那边是 armhf 的包 —— 两者不能混
        val archArmhf = RootfsCatalog.url(RootfsDistro.ARCH, RootfsCatalog.ARMFH)!!
        val ubuntuArmhf = RootfsCatalog.url(RootfsDistro.UBUNTU, RootfsCatalog.ARMFH)!!
        assertTrue(archArmhf.contains("armv7"))
        assertTrue(ubuntuArmhf.contains("armhf"))
    }

    @Test
    fun unsupportedCombinationIsNullNotAWrongGuess() {
        // 宁可说"没有", 也不要悄悄给一个别的架构的地址
        assertNull(RootfsCatalog.url(RootfsDistro.UBUNTU, "riscv64"))
    }

    @Test
    fun archLabelMentionsTheRealArchName() {
        assertTrue(RootfsCatalog.archLabel(RootfsCatalog.ARM64).contains("aarch64"))
        assertTrue(RootfsCatalog.archLabel(RootfsCatalog.AMD64).contains("x86_64"))
    }
}
