package com.qiqi.li.living.container;

/** 列数与产生它的依据；二者必须来自同一次解析。 */
public record ContainerLayout(int columns, Source source) {
    public ContainerLayout {
        if (columns < 1) throw new IllegalArgumentException("Container columns must be positive");
        java.util.Objects.requireNonNull(source);
    }

    public enum Source {
        FIXED,
        LIVE,
        RULE,
        LEARNED,
        FAMILY,
        HEURISTIC,
        UNKNOWN
    }
}
