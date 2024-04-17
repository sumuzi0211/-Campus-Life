package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁的过期时间
     * @return true 获取锁成功 false 获取锁失败
     */
    public boolean tryLock(long timeoutSec);

    public void unLock();
}
