package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.constant.JwtClaimsConstant;
import com.hmdp.dto.UserDTO;
import com.hmdp.properties.JwtProperties;
import com.hmdp.utils.JwtUtil;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 刷新令牌拦截器
 *
 * @author CHEN
 * @date 2022/10/07
 */
//public class RefreshTokenInterceptor implements HandlerInterceptor {
//    private final StringRedisTemplate stringRedisTemplate;
//
//    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }
//
//    @Override
//    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //从请求头中获取token
//        String token = request.getHeader("authorization");
//        if (StringUtils.isEmpty(token)) {
//            //不存在token
//            return true;
//        }
//        //从redis中获取用户
//        Map<Object, Object> userMap =
//                stringRedisTemplate.opsForHash()
//                        .entries(RedisConstants.LOGIN_USER_KEY + token);
//        //用户不存在
//        if (userMap.isEmpty()) {
//            return true;
//        }
//        //hash转UserDTO存入ThreadLocal
//        UserHolder.saveUser(BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false));
//        //token续命
//        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//        return true;
//    }
//
//    @Override
//    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        UserHolder.removeUser();
//    }
//}

/**
 *Jwt登录拦截
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties; // 直接通过构造器注入


    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate,JwtProperties jwtProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtProperties = jwtProperties; // 手动接收依赖
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的token
        String token = request.getHeader(jwtProperties.getUserTokenName());
        if (StrUtil.isBlank(token)) {
            return true;
        }
        Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(),token);
        Long userId =  claims.get(JwtClaimsConstant.USER_ID,Long.class);
        // 2.基于userId获取redis中的用户
        String key  = LOGIN_USER_KEY + userId;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3.判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }
        // 4.判断token是否一致,防止有以前生成的jwt，仍然能够登录
        String jwttoken = userMap.get("jwttoken").toString();
        if(!jwttoken.equals(token)){
            return true;
        }
        // 5.将查询到的hash数据转为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.放行
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}

