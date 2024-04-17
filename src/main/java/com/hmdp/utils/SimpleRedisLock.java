package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Data
public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock() {
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    public static final String KEY_PREFIX = "lock:";
    public static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁的过期时间
     * @return true 获取锁成功 false 获取锁失败
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ifAbsent);
    }

    @Override
    public void unLock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }
//    @Override
//    public void unLock() {
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(id)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
