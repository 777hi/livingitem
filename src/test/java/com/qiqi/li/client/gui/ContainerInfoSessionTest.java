package com.qiqi.li.client.gui;

import static org.junit.jupiter.api.Assertions.*;
import com.qiqi.li.living.container.*;
import org.junit.jupiter.api.Test;

class ContainerInfoSessionTest {
    private static final ContainerMenuInfo INFO = new ContainerMenuInfo(ContainerMenuInfo.Status.AVAILABLE,
        ContainerKind.WORLD_BLOCK, 30, new ContainerLayout(5, ContainerLayout.Source.LIVE), "test:container");

    @Test void initialRequest_doesNotRequireHover() {
        var session = new ContainerInfoSession(1);
        assertNotNull(session.request(0, false));
        assertNull(session.request(1000, false));
    }
    @Test void hoverRefresh_isRateLimitedAndRetries() {
        var session = new ContainerInfoSession(1);
        var first = session.request(0, false);
        assertNull(session.request(999, true));
        var second = session.request(1000, true);
        assertNotNull(second);
        assertNotEquals(first.requestId(), second.requestId());
    }
    @Test void responseRequiresMatchingMenuAndRequest() {
        var session = new ContainerInfoSession(1);
        var request = session.request(0, false);
        session.accept(2, request.requestId(), INFO);
        assertEquals(ContainerMenuInfo.UNAVAILABLE, session.info());
        session.accept(1, request.requestId() + 1, INFO);
        assertEquals(ContainerMenuInfo.UNAVAILABLE, session.info());
        session.accept(1, request.requestId(), INFO);
        assertEquals(INFO, session.info());
    }
    @Test void lateResponse_cannotOverwriteNewerRequest() {
        var session = new ContainerInfoSession(1);
        var first = session.request(0, false);
        var next = session.request(1000, true);
        session.accept(1, next.requestId(), INFO);
        session.accept(1, first.requestId(), ContainerMenuInfo.NON_HOST);
        assertEquals(INFO, session.info());
    }
    @Test void reusedMenuId_doesNotReuseSession() {
        var first = new ContainerInfoSession(1);
        var oldRequest = first.request(0, false);
        var reopened = new ContainerInfoSession(1);
        var newRequest = reopened.request(0, false);
        reopened.accept(1, oldRequest.requestId(), INFO);
        assertEquals(ContainerMenuInfo.UNAVAILABLE, reopened.info());
        reopened.accept(1, newRequest.requestId(), INFO);
        assertEquals(INFO, reopened.info());
    }
    @Test void responseBeforeRequest_isIgnored() {
        var session = new ContainerInfoSession(1);
        session.accept(1, 0, INFO);
        assertEquals(ContainerMenuInfo.UNAVAILABLE, session.info());
    }
}
