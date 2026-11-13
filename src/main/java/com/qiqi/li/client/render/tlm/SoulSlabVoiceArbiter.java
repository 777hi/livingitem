package com.qiqi.li.client.render.tlm;

/** 单枚预览的语音请求仲裁；不同预览互不共享状态。 */
final class SoulSlabVoiceArbiter {
    enum Request {
        AMBIENT(0), RESPONSE(1), FEED(2);

        final int priority;
        Request(int priority) { this.priority = priority; }
    }

    private Request pending;

    void request(Request request) {
        if (pending == null || request.priority > pending.priority) pending = request;
    }

    Request take() {
        Request result = pending;
        pending = null;
        return result;
    }

    void clear() {
        pending = null;
    }
}
