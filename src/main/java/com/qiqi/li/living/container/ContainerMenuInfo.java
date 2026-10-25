package com.qiqi.li.living.container;

/** 当前菜单的宿主信息；不可用与已知非运行界面不是同一种状态。 */
public record ContainerMenuInfo(Status status, ContainerKind kind, int size,
                                ContainerLayout layout, String typeId) {
    public enum Status { AVAILABLE, NON_HOST, UNAVAILABLE }

    public static final ContainerMenuInfo UNAVAILABLE =
        new ContainerMenuInfo(Status.UNAVAILABLE, null, 0, null, "");
    public static final ContainerMenuInfo NON_HOST =
        new ContainerMenuInfo(Status.NON_HOST, null, 0, null, "");

    public ContainerMenuInfo {
        java.util.Objects.requireNonNull(status);
        java.util.Objects.requireNonNull(typeId);
        if (status == Status.AVAILABLE) {
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(layout);
            if (size < 0) throw new IllegalArgumentException("Container size must not be negative");
        }
    }

    public long rows() {
        return layout == null ? 0 : ((long) size + layout.columns() - 1) / layout.columns();
    }
}
