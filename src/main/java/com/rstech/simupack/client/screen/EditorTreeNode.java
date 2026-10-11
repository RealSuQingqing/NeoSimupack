package com.rstech.simupack.client.screen;

import com.lowdragmc.lowdraglib2.gui.util.ITreeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 创作空间左栏的树节点：工程 → 分类 → 建筑。
 *
 * <p>选择语义：选中工程节点表示切换到该工程；选中建筑节点表示切换到该工程
 * 下的这个建筑；分类节点只用于分组。
 */
final class EditorTreeNode implements ITreeNode<String, String> {
    enum Kind {
        ROOT,
        PROJECT,
        CATEGORY,
        BUILDING
    }

    private final Kind kind;
    private final String key;
    private final String content;
    private final String projectId;
    private final String category;
    private final String buildingId;
    /**
     * 父节点；由 {@link #attach(EditorTreeNode)} 回填。
     *
     * <p>不能是 {@code final}：三个工厂方法建节点时还不知道父节点，而 TreeList 需要它 ——
     * {@code getDimension()} 决定每行的缩进（{@code 5 * (dimension - flattenRoot?1:0)}），
     * {@code expandNodeAlongPath()} 靠它向上收集祖先来展开。所以「父节点永远为 null」
     * 的代价是：所有节点都算第 0 层（整棵树没有缩进），并且「打开编辑器时定位到上次的建筑」
     * 一个祖先也展不开，目标行根本不会出现。
     */
    private EditorTreeNode parent;
    private final List<EditorTreeNode> children;

    private EditorTreeNode(Kind kind, String key, String content, String projectId,
                           String category, String buildingId,
                           EditorTreeNode parent, List<EditorTreeNode> children) {
        this.kind = kind;
        this.key = key;
        this.content = content;
        this.projectId = projectId;
        this.category = category;
        this.buildingId = buildingId;
        this.parent = parent;
        this.children = children;
    }

    static EditorTreeNode root(String content) {
        return new EditorTreeNode(Kind.ROOT, "__root__", content, "", "", "", null, new ArrayList<>());
    }

    static EditorTreeNode category(String projectId, String category, String content) {
        return new EditorTreeNode(Kind.CATEGORY, "category:" + projectId + "/" + category, content,
                projectId, category, "", null, new ArrayList<>());
    }

    static EditorTreeNode building(String projectId, String category, String buildingId, String content) {
        return new EditorTreeNode(Kind.BUILDING,
                "building:" + projectId + "/" + category + "/" + buildingId, content,
                projectId, category, buildingId, null, new ArrayList<>());
    }

    /** 按顺序建立父子关系并返回父节点，便于链式构建。 */
    EditorTreeNode attach(EditorTreeNode child) {
        // 双向绑定：只往 children 里塞、不回填 child.parent 的话，
        // getDimension() 与 expandNodeAlongPath() 都会当成「这是根」。
        child.parent = this;
        children.add(child);
        return this;
    }

    Kind kind() {
        return kind;
    }

    String projectId() {
        return projectId;
    }

    String category() {
        return category;
    }

    String buildingId() {
        return buildingId;
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
    public EditorTreeNode getParent() {
        return parent;
    }

    @Override
    public List<EditorTreeNode> getChildren() {
        return children;
    }

    @Override
    public String toString() {
        return content;
    }
}
