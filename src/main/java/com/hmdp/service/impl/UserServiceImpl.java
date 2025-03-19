package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.JwtClaimsConstant;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.properties.JwtProperties;
import com.hmdp.service.IUserService;
import com.hmdp.utils.JwtUtil;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.*;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //手机号不符合
            return Result.fail("手机号格式错误");
        }
        //手机号符合,生成验证码
        String code = RandomUtil.randomNumbers(6);
        /*//保存验证码到session
        session.setAttribute("code", code);*/
        //保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        //返回ok
        return Result.ok();
    }

//    /**
//     * redis+token
//     * @param loginForm 登录表单
//     * @param session   会话
//     * @return
//     */
//    @Override
//    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        //校验手机号
//        String phone = loginForm.getPhone();
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            //手机号不符合
//            return Result.fail("手机号格式错误");
//        }
//        //从redis中获取验证码 校验验证码
//        /*  Object cacheCode = session.getAttribute("code");*/
//        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
//        String code = loginForm.getCode();
//        if (cacheCode == null || !cacheCode.equals(code)) {
//            //不一致 报错
//            return Result.fail("验证码错误");
//        }
//        //一致 根据手机号查询用户
//        User user = baseMapper
//                .selectOne(new LambdaQueryWrapper<User>()
//                        .eq(User::getPhone, phone));
//        //判断用户是否存在
//        if (user == null) {
//            //不存在 创建新用户
//            user = createUserWithPhone(phone);
//        }
//        /*//保存用户信息到session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));*/
//        //生成token
//        String token = UUID.randomUUID().toString(true);
//        //userDTO转map
//        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>()
//                , CopyOptions.create().setIgnoreNullValue(true)
//                        .setFieldValueEditor(
//                                (name, value) -> value.toString()
//                        ));
//        //保存用户信息到redis
//        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
//        //设置过期时间
//        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
//        return Result.ok(token);
//    }


    /**
     * 使用JWT实现登录功能
     * @param loginForm 登录表单
     * @param session   会话
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.校验验证码
        //Object cacheCode = session.getAttribute("code");
        // 3.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();

        // @TODO 假如要生成1k个用户测试的话，注释掉以下部分，先不校验验证码
//        if(cacheCode == null || !cacheCode.equals(code)){
//            //3.不一致，报错
//            return Result.fail("验证码错误");
//        }
        //注释掉以上部分


        //一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if(user == null){
            //不存在，则创建
            user =  createUserWithPhone(phone);
        }

        // 6.生成JWT
        Map<String,Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID,user.getId());
        //String token = JwtUtil.createJWT(jwtProperties.getSecretKey(),jwtProperties.getTtl(),claims);
        //String jwttoken = JwtUtil.createJWT("ThisIsA32BytesLongSecretKeyForHS256",30*60*1000,claims);
        String jwttoken = JwtUtil.createJWT(jwtProperties.getUserSecretKey(),jwtProperties.getUserTtl(),claims);
        //jwtProperties.getUserSecretKey();


        // 7.保存用户信息到 redis中
        // 7.1.随机生成token，作为登录令牌
//        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), //beanToMap方法执行了对象到Map的转换
                CopyOptions.create()
                        .setIgnoreNullValue(true) //BeanUtil在转换过程中忽略所有null值的属性
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); //对于每个字段值，它简单地调用toString()方法，将字段值转换为字符串。
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + userDTO.getId();
        // 7.4.将jwttoken存入userMap中
        userMap.put("jwttoken",jwttoken);
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.5.设置redis中 userId的有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8.返回token
        return Result.ok(jwttoken);

    }



    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        //生成随机昵称
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        baseMapper.insert(user);
        return user;
    }
}
