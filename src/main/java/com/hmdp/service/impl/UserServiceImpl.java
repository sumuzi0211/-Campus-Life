package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constants.RedisConstants;
import com.hmdp.constants.SessionConstant;
import com.hmdp.constants.UserConstant;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.log4j.Log4j;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     *
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号码是否符合规则
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合规则，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.符合规则，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码至redis
        String key = RedisConstants.LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(key, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.info("发送手机验证码, 验证码：{}", code);
        //TODO 暂时使用日志记录验证码，后续上线需使用第三方短信发送验证码功能！
        //6.返回成功结果
        return Result.ok(code);
    }

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //手机号格式错误，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //2.校验验证码
        String key = RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone();
        String cacheCOde = stringRedisTemplate.opsForValue().get(key);
        String code = loginForm.getCode();
        if (cacheCOde == null || !cacheCOde.toString().equals(code)) {
            //验证码错误，返回错误信息
            return Result.fail("验证码错误!");
        }
        //3.根据手机号码从数据库从查询用户是否存在
        User user = query().eq(UserConstant.USER_PHONE, phone).one();
        //4.用户不存在自动注册新用户
        if (user == null) {
            user = createNewUser(phone);
        }
        //5.保存用户至redis
        //5.1 使用随机UUID作为用户的token
        String token = UUID.randomUUID().toString(true);
        log.info("token：{}", token);
        //5.2 将UserDTO转化为Map存入redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userDtoMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userDtoMap);
        //5.3 设置过期时间
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //6.返回token
        return Result.ok(token);
    }

    /**
     * 注册新用户
     * @param phone
     * @return
     */
    private User createNewUser(String phone) {
        //创建新用户
        User user = new User();
        //给新用户设置对应参数
        user.setPhone(phone);
//        user.setCreateTime(LocalDateTime.now());
//        user.setUpdateTime(LocalDateTime.now());
        user.setNickName(UserConstant.USER_PREFIX_NAME + RandomUtil.randomString(10));
        //保存新用户至数据库中
        save(user);

        return user;
    }
}
