package com.rstech.simupack.attention.provider;

import com.rstech.simupack.attention.AttentionContext;
import com.rstech.simupack.attention.AttentionItem;
import com.rstech.simupack.attention.AttentionKind;
import com.rstech.simupack.attention.AttentionProvider;
import com.rstech.simupack.pack.BuildingPack;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * A 类：故障拓展包（数据现成，最先落地）。
 *
 * <p>判据与列表里的红灯<b>完全同源</b>，否则会出现"列表里红着、主菜单却不亮"的不一致：
 * <ul>
 *   <li>{@link BuildingPack#faulty()} 的包——比 {@code unusable()} 宽一格
 *       （个别建筑缺结构文件的包也算红灯）；</li>
 *   <li>建筑目录里待转换的旧包（{@code LegacyPackInstaller.pendingInBuildingDirectory()}），
 *       它们在列表里同样是红灯。</li>
 * </ul>
 *
 * <p><b>不弹窗</b>：{@link AttentionKind#FAULTY_PACK}{@code .promptsUser() == false}。
 * 故障包的问题点（哪个包、什么错）只有在列表里对着看才说得清。
 */
public final class FaultyPackAttentionProvider implements AttentionProvider {

    /** 翻译键前缀；具体文案在 lang 文件里。 */
    static final String TITLE_KEY = "gui.simupack.attention.faulty.title";
    static final String BODY_KEY = "gui.simupack.attention.faulty.body";
    static final String LEGACY_BODY_KEY = "gui.simupack.attention.faulty.legacy_body";

    @Override
    public void collect(AttentionContext context, Consumer<AttentionItem> sink) {
        for (BuildingPack pack : context.packs()) {
            if (!pack.faulty()) {
                continue;
            }
            sink.accept(new AttentionItem(AttentionKind.FAULTY_PACK,
                    keyOf(pack.path()),
                    TITLE_KEY, BODY_KEY,
                    List.of(pack.path().getFileName().toString())));
        }

        for (Path legacy : context.pendingLegacyPacks()) {
            sink.accept(new AttentionItem(AttentionKind.FAULTY_PACK,
                    // 旧包与"已转换的包"可能同名（转换是固定同名 + .zip），
                    // 加前缀区分，避免两者共用一条 key 互相顶掉。
                    "legacy:" + keyOf(legacy),
                    TITLE_KEY, LEGACY_BODY_KEY,
                    List.of(legacy.getFileName().toString())));
        }
    }

    /**
     * 故障项的稳定身份：磁盘文件的规范化绝对路径。
     *
     * <p><b>刻意不用 {@link BuildingPack#packId()}</b>——它在启用/禁用切换后会变
     * （{@code id} ↔ {@code id#disabled}），会让同一条问题在红点里翻倍。
     * 磁盘上同一时刻只有一个文件，路径才是稳定身份。
     *
     * <p>转小写：Windows 上文件名大小写不敏感，{@code City.zip} 与 {@code city.zip}
     * 指向同一个文件，不该算两条。
     */
    static String keyOf(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(java.util.Locale.ROOT);
    }
}
