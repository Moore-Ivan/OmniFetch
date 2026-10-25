package com.downloader.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * FileUtils 单元测试：文件名消毒、重名避让、路径穿越防护、原子移动。
 */
public class FileUtilsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ── sanitizeFileName ──

    @Test
    public void sanitizeRemovesSeparatorsAndDotDot() {
        String safe = FileUtils.sanitizeFileName("..\\..\\evil.exe");
        assertFalse(safe.contains(".."));
        assertFalse(safe.contains("\\"));
        assertFalse(safe.contains("/"));
        assertTrue(safe.endsWith("evil.exe"));
    }

    @Test
    public void sanitizeReplacesSlashAndBackslash() {
        assertEquals("a_b_c.txt", FileUtils.sanitizeFileName("a/b\\c.txt"));
    }

    @Test
    public void sanitizeRemovesControlChars() {
        assertEquals("a_b.txt", FileUtils.sanitizeFileName("a\u0000b.txt"));
        assertEquals("a_b.txt", FileUtils.sanitizeFileName("a\nb.txt"));
    }

    @Test
    public void sanitizeNullAndEmpty() {
        assertEquals("", FileUtils.sanitizeFileName(null));
        assertEquals("", FileUtils.sanitizeFileName(""));
    }

    // ── buildSaveFile ──

    @Test
    public void buildSaveFileDeduplicates() throws IOException {
        File dir = tmp.newFolder();
        File first = FileUtils.buildSaveFile(dir.getPath(), "a.txt");
        assertTrue(first.createNewFile());
        // 重名自动避让
        File second = FileUtils.buildSaveFile(dir.getPath(), "a.txt");
        assertEquals("a (1).txt", second.getName());
        // 模拟真实写入：第二次下载落地后，第三次才会避让到 (2)
        assertTrue(second.createNewFile());
        File third = FileUtils.buildSaveFile(dir.getPath(), "a.txt");
        assertEquals("a (2).txt", third.getName());
    }

    @Test
    public void buildSaveFileKeepsExtensionWhenDeduplicating() throws IOException {
        File dir = tmp.newFolder();
        assertTrue(FileUtils.buildSaveFile(dir.getPath(), "v1.2.exe").createNewFile());
        File next = FileUtils.buildSaveFile(dir.getPath(), "v1.2.exe");
        // 点号在扩展名里：base = "v1.2"，ext = ".exe"
        assertEquals("v1.2 (1).exe", next.getName());
    }

    @Test
    public void buildSaveFileBlocksPathTraversal() throws IOException {
        File dir = tmp.newFolder();
        File f = FileUtils.buildSaveFile(dir.getPath(), "..\\evil.txt");
        // 消毒后必须落在目标目录内，绝不允许穿越到上级
        assertEquals(dir.getCanonicalPath(), f.getParentFile().getCanonicalPath());
    }

    @Test
    public void buildSaveFileCreatesMissingDir() {
        File dir = new File(tmp.getRoot(), "sub/dir");
        File f = FileUtils.buildSaveFile(dir.getPath(), "x.bin");
        assertTrue(dir.exists() && dir.isDirectory());
        assertEquals("x.bin", f.getName());
    }

    @Test
    public void buildSaveFileFallsBackWhenNameEmpty() {
        File dir = tmp.getRoot();
        File f = FileUtils.buildSaveFile(dir.getPath(), "..\\..");
        // 全被消毒成空时使用时间戳兜底名
        assertNotEquals("", f.getName());
        assertEquals(dir.getAbsolutePath(), f.getParentFile().getAbsolutePath());
    }

    // ── atomicMove ──

    @Test
    public void atomicMoveMovesAndRemovesSource() throws IOException {
        Path source = tmp.newFile("src.txt").toPath();
        Files.writeString(source, "payload");
        Path target = tmp.getRoot().toPath().resolve("dst.txt");

        FileUtils.atomicMove(source, target);

        assertFalse(Files.exists(source));
        assertEquals("payload", Files.readString(target));
    }

    @Test
    public void atomicMoveOverwritesExistingTarget() throws IOException {
        Path source = tmp.newFile("s2.txt").toPath();
        Files.writeString(source, "new");
        Path target = tmp.newFile("t2.txt").toPath();
        Files.writeString(target, "old");

        FileUtils.atomicMove(source, target);

        assertFalse(Files.exists(source));
        assertEquals("new", Files.readString(target));
    }
}
