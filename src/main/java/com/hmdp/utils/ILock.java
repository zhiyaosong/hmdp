package com.hmdp.utils;

public interface ILock {
    /**
     *
     * @param timeoutSec 锁持有的超时时间，过期之后自动释放
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();


}
