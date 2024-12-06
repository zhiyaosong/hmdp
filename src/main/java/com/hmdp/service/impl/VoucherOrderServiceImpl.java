package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

import static org.redisson.api.condition.Conditions.eq;

@Slf4j
@Service
public  class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();
        //5.1 查询订单
        int count = Math.toIntExact(query()
                .eq("voucher_id", voucherOrder.getVoucherId())
                .eq("user_id", userId)
                .count());
        //5.2 判断是否存在
        if (count > 0) {
            //用户已经购买过了
            log.error("用户已经购买了");
            return;
        }
        //扣减库存
        boolean isSuccess = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId())//where条件
                    .gt("stock", 0)
                .update();//set语句
        if (!isSuccess) {
            //库存不足
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
    }

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //@PostConstruct当前类(VoucherOrderServiceImpl)初始化之后执行
    @PostConstruct
   private  void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
   }
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取队列中的队列信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }

                //2.创建订单

            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        //(反向写不会嵌套)
        if(!isLock){
            //获取锁失败，返回错误信息重试
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象（事务）
            //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2.判断结果是为0
        int r = result.intValue();
        if (r != 0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ?"库存不足":"不能重复下单");
        }
        //2.2 为0，有购买资格
        VoucherOrder voucherOrder = new VoucherOrder();

        //2.3 订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4 用户id
        voucherOrder.setUserId(userId);
        //2.5 代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.6 保存阻塞队列
        orderTasks.add(voucherOrder);

        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4. 返回订单id
        return Result.ok(0);
    }

    /*public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀是否还未开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            Result.fail("秒杀尚未开始！");
        }

        // 3.判断秒杀是否已经结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            Result.fail("秒杀已经结束！");
        }

        // 4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        //创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        //(反向写不会嵌套)
        if(!isLock){
            //获取锁失败，返回错误信息重试
            return Result.fail("不允许重复下单");
        }
        try {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.getResult(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }

    }*/


    @Transactional
    public Result getResult(Long voucherId) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();

        //用synchronized关键字同步代码块(方法内部)，缩小定义范围
        synchronized (userId.toString().intern()) {
            //5.1查询订单（根据voucherid和userid查询）
            Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //5.2判断是否存在
            if (count > 0) {
                return Result.fail("用户已经购买过一次");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update().
                    setSql("stock = stock - 1").
                    eq("voucher_id", voucherId).
//                eq("stock", seckillVoucher.getStock()).    // 增加对库存的判断，判断当前库存是否与查询出的结果一致
        gt("stock", 0).        // 修改判断逻辑，改为只要库存大于0，就允许线程扣减
                            update();

            // 扣减失败
            if (!success) {
                //扣减失败
                return Result.fail("库存不足！");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1生成订单 id
            Long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2代金券id
            voucherOrder.setVoucherId(voucherId);
            // 7.3用户 id
            // Long userId = UserHolder.getUser().getId();
            voucherOrder.setUserId(userId);

            save(voucherOrder);

            //返回订单id
            return Result.ok(orderId);
        }
    }

}

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */


/*

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

*/
/**
     * 自己注入自己为了获取代理对象 @Lazy 延迟注入 避免形成循环依赖
     *//*


    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderService;

*/
/**
     * 秒杀优惠券
     *
     * @param voucherId 券id
     * @return {@link Result}
     *//*

    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //秒杀尚未开始
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //秒杀已经结束
            return Result.fail("秒杀已经结束");
        }
        //判断库存是否充足
        if (voucher.getStock() < 1) {
            //库存不足
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
          IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            return voucherOrderService.getResult(voucherId);
        }
    }

    @Override
    @NotNull
    @Transactional(rollbackFor = Exception.class)//事务
    public Result getResult(Long voucherId) {
        //是否下单
        Long userId = UserHolder.getUser().getId();
        Long count = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId)
                .count();
        if (count > 0) {
            return Result.fail("禁止重复购买");
        }
        //扣减库存
        boolean isSuccess = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherId)//where条件
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock=stock-1"));//set语句
        if (!isSuccess) {
            //库存不足
            return Result.fail("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setId(orderId);
        this.save(voucherOrder);
        //返回订单id
        return Result.ok(orderId);
    }
}
*/
