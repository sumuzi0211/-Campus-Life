package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.constants.RedisConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //查询缓存
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String shopListJSON = stringRedisTemplate.opsForValue().get(key);
        //判断缓存是否命中
        if (StrUtil.isNotBlank(shopListJSON)) {
            //缓存命中
            List<ShopType> shopTypes = JSONUtil.toList(shopListJSON, ShopType.class);
            //根据sort字段排序
            Collections.sort(shopTypes, (o1, o2) -> o1.getSort()- o2.getSort());
            return Result.ok(shopTypes);
        }
        //缓存未命中，查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //判断数据库中是否有数据
        if (CollectionUtils.isEmpty(shopTypes)) {
            return Result.fail("商铺分类不存在");
        }
        //存入缓存，返回数据
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypes));
        return Result.ok(shopTypes);
    }
}
