package com.rstech.simupack.client.ui.editor;

import java.util.Objects;

/**
 * 选点会话的作用域快照：<b>这次选出来的坐标属于谁</b>。
 *
 * <p>由「建筑 + 被编辑的对象 + 配置快照」三样组成。用户在选点过程中可能切建筑、
 * 换配方/容器、或改了配置，那时选出来的坐标已经不属于原来那条记录了 ——
 * 这个快照就是用来在落盘前发现这件事的（{@code matches} 返回 false → 安全退出）。
 *
 * <p>名字刻意不带领域前缀：它原先叫 {@code IndustrialPickScope}，
 * 但它服务的是「选点」这个通用能力（工业点位、商业容器、将来的住宅都用），
 * 带上工业前缀会把通用概念错误地绑到一个分类上。
 *
 * <p>{@code building} 用引用比较（{@code ==}）而不是 {@code equals}：
 * 判据是「还是不是同一个建筑对象」，而不是「内容是否相同」。
 */
public record PickScope(Object building, String subject, String configuration) {

    /** {@code subject} 是领域自己的标识：工业是配方 id，商业是容器 id。 */
    public boolean matches(Object currentBuilding, String currentSubject, String currentConfiguration) {
        return building == currentBuilding && Objects.equals(subject, currentSubject)
                && Objects.equals(configuration, currentConfiguration);
    }
}
