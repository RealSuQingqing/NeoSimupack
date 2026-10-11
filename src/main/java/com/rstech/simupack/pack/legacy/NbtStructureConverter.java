package com.rstech.simupack.pack.legacy;

import com.rstech.simupack.creation.StructureImportService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用编辑器同一套结构导入管线（{@link StructureImportService}）把旧包的
 * {@code .litematic} / {@code .schem} 转成原版结构 NBT。
 *
 * <p>之所以走"临时文件"而不是纯内存：{@code StructureImportService} 的产物本来就是文件，
 * 转换完立刻读回来删掉即可。临时文件放在系统临时目录，不与建筑目录打交道，
 * 所以<b>不会</b>往 {@code simukraftbuilding/} 里漏任何中间产物（那里的所有 {@code .zip}
 * 都会被 NSUK 当成拓展包读）。
 */
public final class NbtStructureConverter implements LegacyPackConverter.StructureNbtConverter {

    @Override
    public byte[] toStructureNbt(String sourceName, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile("simupack-legacy-", ".nbt");
        try {
            StructureImportService.convert(bytes, temporary);
            return Files.readAllBytes(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 默认实例；无状态，可安全共享。 */
    public static NbtStructureConverter create() {
        return new NbtStructureConverter();
    }
}
