package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.constants.RedisConstants;
import com.hmdp.constants.UserConstant;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //从请求头中获取token
        String token = request.getHeader(UserConstant.USER_LOGIN_TOKEN);
        //判断token是否为空
        if (StrUtil.isBlank(token)) {
            return true;
        }
        //tokenKey
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        //基于tokenKey从redis中获取用户数据
        Map<Object, Object> userDtoMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //判断用户是否为空
        if (CollectionUtils.isEmpty(userDtoMap)) {
            return true;
        }
        //将存储用户属性的map转换为userDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userDtoMap, new UserDTO(), false);
        //将userDTO存入ThreadLocal
        UserHolder.saveUser(userDTO);
        //刷新token有效期
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }


}
