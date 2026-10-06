package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 四邻取数的守卫（2026-10-07 性能收尾）——
 * 新增 {@link ContainerContext#fillNeighbors}（写入调用方缓冲，避免内层循环每格分配）
 * 后，必须保证它与原 {@code getNeighbors} 逐格一致。
 */
class ContainerNeighborsTest {

    @Test
    @DisplayName("fillNeighbors 与 getNeighbors 结果逐格一致（含边界：首/末列、首/末行）")
    void fillMatchesAllocate() {
        int size = 27, width = 9;
        for (int slot = 0; slot < size; slot++) {
            int[] expected = ContainerContext.getNeighbors(slot, size, width);
            int[] buf = new int[4];
            int count = ContainerContext.fillNeighbors(buf, slot, size, width);

            assertEquals(expected.length, count, "slot " + slot + " 邻居个数");
            assertArrayEquals(expected,
                count == 4 ? buf : java.util.Arrays.copyOf(buf, count),
                "slot " + slot + " 邻居内容（顺序也必须一致：左右上下）");
        }
    }

    @Test
    @DisplayName("单行容器（width == size）：只有左右，无上下")
    void singleRow() {
        int[] buf = new int[4];
        assertEquals(1, ContainerContext.fillNeighbors(buf, 0, 9, 9), "首格只有右邻");
        assertEquals(1, buf[0]);

        assertEquals(2, ContainerContext.fillNeighbors(buf, 4, 9, 9), "中间格左右各一");
        assertEquals(3, buf[0]);
        assertEquals(5, buf[1]);

        assertEquals(1, ContainerContext.fillNeighbors(buf, 8, 9, 9), "末格只有左邻");
        assertEquals(7, buf[0]);
    }

    @Test
    @DisplayName("复用缓冲不被上一次调用污染：只写 count 个，多余位不参与遍历")
    void reuseDoesNotLeak() {
        int[] buf = new int[]{99, 99, 99, 99};
        int count = ContainerContext.fillNeighbors(buf, 0, 27, 9);   // 角落：右 + 下 = 2
        assertEquals(2, count);
        assertEquals(1, buf[0]);
        assertEquals(9, buf[1]);
    }
}
