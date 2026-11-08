package com.aura.launcher

import com.aura.launcher.ui.theme.wallpaperInSampleSize
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperDecodePolicyTest {

    @Test
    fun `large wallpaper decode stays within the requested long edge`() {
        assertEquals(1, wallpaperInSampleSize(2048, 1536, 2048))
        assertEquals(2, wallpaperInSampleSize(4096, 3072, 2048))
        assertEquals(4, wallpaperInSampleSize(8000, 6000, 2048))
        assertEquals(8, wallpaperInSampleSize(12000, 9000, 2048))
    }

    @Test
    fun `invalid dimensions use a safe neutral sample`() {
        assertEquals(1, wallpaperInSampleSize(0, 4000, 2048))
        assertEquals(1, wallpaperInSampleSize(4000, 3000, 0))
    }
}
