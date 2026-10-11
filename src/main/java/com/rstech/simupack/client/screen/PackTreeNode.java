package com.rstech.simupack.client.screen;

import com.lowdragmc.lowdraglib2.gui.util.ITreeNode;
import com.rstech.simupack.pack.BuildingPack;

import java.util.List;

/**
 * 拓展包管理器左栏的树节点：已安装拓展包 / 待转换的旧包。
 *
 * <p>除了显示用的文本，节点还要带上它对应的数据（{@link #pack()} 或原始路径），
 * 因为列表左侧的<b>状态指示灯</b>要按"启用中 / 未启用 / 故障"取色，而这三个状态
 * 只有 {@link BuildingPack} 知道；只留一个字符串就会退化成"再按名字去列表里找"，
 * 重名包会找错。
 */
final class PackTreeNode implements ITreeNode<String, String> {
    enum Kind {
        ROOT,
        PACK,
        LEGACY
    }

    private final Kind kind;
    private final String key;
    private final String content;
    private final BuildingPack pack;
    private final String legacyPath;
    private final PackTreeNode parent;
    private final List<PackTreeNode> children;

    private PackTreeNode(Kind kind, String key, String content, BuildingPack pack, String legacyPath,
                         PackTreeNode parent, List<PackTreeNode> children) {
        this.kind = kind;
        this.key = key;
        this.content = content;
        this.pack = pack;
        this.legacyPath = legacyPath;
        this.parent = parent;
        this.children = children;
    }

    static PackTreeNode root(List<PackTreeNode> children) {
        return new PackTreeNode(Kind.ROOT, "root", "拓展包", null, null, null, children);
    }

    /**
     * 已安装拓展包节点。
     *
     * <p>key 必须带上启用状态：同一个包在启用/禁用两种状态下 {@code BuildingPack.id()} 是同一个
     * （这是有意的，见 {@code PackActivation.stripExtensions}），而 {@code TreeList} 用 key 去重，
     * 只用 id 会让"启用中的包"和"被禁用的包"折叠成一个节点。状态由 {@code packId} 前缀承载，
     * 所以这里直接用 {@code pack.packId()}。
     */
    static PackTreeNode pack(BuildingPack pack, String content, PackTreeNode parent) {
        return new PackTreeNode(Kind.PACK, pack.packId(), content, pack, null, parent, List.of());
    }

    /** 待转换的旧包节点。 */
    static PackTreeNode legacy(String absolutePath, String content, PackTreeNode parent) {
        return new PackTreeNode(Kind.LEGACY, "legacy:" + absolutePath, content, null, absolutePath, parent, List.of());
    }

    Kind kind() {
        return kind;
    }

    /** 这个节点对应的包；旧包/根节点返回 null。 */
    BuildingPack pack() {
        return pack;
    }

    /** 旧包节点的绝对路径；其它节点返回 null。 */
    String legacyPath() {
        return legacyPath;
    }

    @Override
    public int getDimension() {
        return parent == null ? 0 : parent.getDimension() + 1;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public PackTreeNode getParent() {
        return parent;
    }

    @Override
    public List<PackTreeNode> getChildren() {
        return children;
    }
}
