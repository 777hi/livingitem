package com.qiqi.li.client.gui;

import com.qiqi.li.living.container.ContainerMenuInfo;
import com.qiqi.li.network.ContainerMenuInfoRequestPacket;

/** 每个按钮实例独占的信息会话；请求编号跨菜单复用仍不重复。 */
public final class ContainerInfoSession {
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE =
        new java.util.concurrent.atomic.AtomicLong();
    private final int menuId;
    private long requestId;
    private long lastRequest = Long.MIN_VALUE;
    private ContainerMenuInfo info = ContainerMenuInfo.UNAVAILABLE;

    public ContainerInfoSession(int menuId) { this.menuId = menuId; }

    public ContainerMenuInfoRequestPacket request(long now, boolean hovered) {
        if (lastRequest != Long.MIN_VALUE && (!hovered || now - lastRequest < 1000)) return null;
        lastRequest = now;
        requestId = SEQUENCE.incrementAndGet();
        return new ContainerMenuInfoRequestPacket(menuId, requestId);
    }

    public void accept(int responseMenuId, long responseRequestId, ContainerMenuInfo value) {
        if (requestId != 0 && responseMenuId == menuId && responseRequestId == requestId) info = value;
    }

    public ContainerMenuInfo info() { return info; }
}
