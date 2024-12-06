package com.hmdp.utils;

import cn.hutool.core.lang.DefaultSegment;
import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static cn.hutool.poi.excel.sax.AttributeName.t;

public class SimpleRedisLock implements ILock{
    //锁的名字
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 需要用户传值
     * @param name
     * @param stringRedisTemplate
     */
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //锁的统一前缀
    private static final String KEK_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+ "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEK_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEK_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }

    //释放锁
    /*@Override
    public void unlock() {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中标识
        String id = stringRedisTemplate.opsForValue().get(KEK_PREFIX + name);

        //判断标识是否一致
        if (threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(KEK_PREFIX+name);
        }

    }*/
}
