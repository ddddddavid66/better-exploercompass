package com.chaosthedude.explorerscompass.unified.data;

import java.io.IOException;
import java.nio.file.*;

/** 临时文件与目标位于同一目录；失败保留原文件，不留下半写状态。 */
public final class AtomicFiles {
    private AtomicFiles() {}
    public static void write(Path destination, byte[] bytes) throws IOException {
        Path absolute = destination.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), "compass-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static void backup(Path source) throws IOException {
        if (Files.exists(source)) Files.copy(source, source.resolveSibling(source.getFileName() + ".pre-unified-" + java.util.UUID.randomUUID() + ".bak"));
    }
}
