package com.downloader.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * UpdateDownloader.parseTotalFromContentRange 单元测试
 * （该值同时供 HttpDownloader 的 GET 探测使用，格式错误必须安全回退 -1）。
 */
public class UpdateDownloaderTest {

    @Test
    public void parsesNormalRange() {
        assertEquals(1000L, UpdateDownloader.parseTotalFromContentRange("bytes 100-199/1000"));
    }

    @Test
    public void parsesFullRange() {
        assertEquals(512L, UpdateDownloader.parseTotalFromContentRange("bytes 0-511/512"));
    }

    @Test
    public void unknownTotalReturnsMinusOne() {
        assertEquals(-1L, UpdateDownloader.parseTotalFromContentRange("bytes 0-0/*"));
    }

    @Test
    public void nullReturnsMinusOne() {
        assertEquals(-1L, UpdateDownloader.parseTotalFromContentRange(null));
    }

    @Test
    public void emptyStringReturnsMinusOne() {
        assertEquals(-1L, UpdateDownloader.parseTotalFromContentRange(""));
    }

    @Test
    public void missingTotalPartReturnsMinusOne() {
        assertEquals(-1L, UpdateDownloader.parseTotalFromContentRange("bytes 1-2/"));
    }

    @Test
    public void nonNumericTotalReturnsMinusOne() {
        assertEquals(-1L, UpdateDownloader.parseTotalFromContentRange("bytes 1-2/abc"));
    }

    @Test
    public void garbageReturnsMinusOne() {
        assertEquals(-1L, UpdateDownloader.parseTotalFromContentRange("garbage"));
    }
}
