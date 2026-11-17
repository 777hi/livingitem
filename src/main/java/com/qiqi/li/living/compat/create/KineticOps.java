package com.qiqi.li.living.compat.create;

/** Create 动力网操作端口；具体类型判断与 API 调用由 Create Mixin 适配层实现。 */
public interface KineticOps {

    boolean livingItem$isServerSide();

    boolean livingItem$isSafeForStressInjection();

    boolean livingItem$tryUpdateGeneratedRotation();

    boolean livingItem$hasNetwork();

    void livingItem$removeFromNetwork();

    void livingItem$detachKinetics();

    void livingItem$attachKinetics();

    void livingItem$setSpeed(float rpm);

    void livingItem$setNetworkToSelf();

    void livingItem$clearNetwork();

    void livingItem$updateNetwork(float capacity);

    void livingItem$setChanged();

    void livingItem$sendData();

    String livingItem$getDebugType();

    String livingItem$getDebugPosition();
}
