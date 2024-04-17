package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constants.RedisConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.constants.RedisConstants.CACHE_SHOP_TTL;


@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息
     *
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Result queryShopById(Long id) {
//        // 解决缓存穿透
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

//         互斥锁解决缓存击穿
         Shop shop = cacheClient
                 .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
//         Shop shop = cacheClient
//                 .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("该商铺不存在！");
        }

        return Result.ok(shop);
    }

    /**
     * 使用逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    private ExecutorService cacheReloadExecutor = Executors.newFixedThreadPool(5);
    private Shop queryWithLogicalExpire(Long id) {
        // 1.查询redis中的商铺缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String redisDataJSON = stringRedisTemplate.opsForValue().get(shopKey);
        // 2.判断缓存中是否有数据
        if (StrUtil.isBlank(redisDataJSON)) {
            // 缓存未命中
            return null;
        }
        // 3.判断是否逻辑过期
        // 3.1 将redisDataJSON转换为RedisData对象
        RedisData redisData = JSONUtil.toBean(redisDataJSON, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3.2 判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 缓存未过期,返回商铺信息
            return shop;
        }
        // 缓存逻辑过期，尝试获取互斥锁，重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);
        if (lock) {
            // 二次确认缓存是否存在，防止再次重建缓存
            Shop shopAfter = isNotLogicalExpire(shopKey);
            if (shopAfter != null) return shopAfter;
            // 获取到互斥锁，开启子线程重建缓存
            cacheReloadExecutor.submit(() -> {
                try {
                    this.saveShopToRedis(id, 120L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        // 返回过期数据
        return shop;
    }

    /**
     * 判断缓存是否逻辑过期
     */
    private Shop isNotLogicalExpire(String key) {
        String redisDataJSON = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存中是否有数据
        if (StrUtil.isBlank(redisDataJSON)) {
            // 缓存未命中
            return null;
        }
        // 3.判断是否逻辑过期
        // 3.1 将redisDataJSON转换为RedisData对象
        RedisData redisData = JSONUtil.toBean(redisDataJSON, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3.2 判断是否逻辑过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 缓存未过期,返回商铺信息
            return shop;
        } else {
            return null;
        }
    }
    /**
     * 使用互斥锁解决缓存击穿，缓存空数据解决缓存穿透，过期时间加上随机值解决缓存雪崩
     * @param id
     * @return
     */
    private Shop queryWithPassMutex(Long id) {
        // 1.查询redis中的商铺缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJSON = stringRedisTemplate.opsForValue().get(shopKey);
        // 2.判断缓存中是否有数据
        if (StrUtil.isNotBlank(shopJSON)) {
            // 缓存命中
            return JSONUtil.toBean(shopJSON, Shop.class);
        }
        // 判断是否为空数据
        if ("".equals(shopJSON)) {
            return null;
        }

        // 重建缓存数据
        // 尝试获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        try {
            boolean lock = tryLock(lockKey);
            if (!lock) {
                // 未获取到互斥锁，休眠一段时间后重试
                Thread.sleep(50);
                return queryWithPassMutex(id);
            }
            // 递归出口，判断缓存中是否命中，防止最后一次递归再次查询数据库重建缓存数据
            String shopJSONAfter = stringRedisTemplate.opsForValue().get(shopKey);
            // 2.判断缓存中是否有数据
            if (StrUtil.isNotBlank(shopJSONAfter)) {
                // 缓存命中
                return JSONUtil.toBean(shopJSONAfter, Shop.class);
            }
            // 判断是否为空数据
            if ("".equals(shopJSONAfter)) {
                return null;
            }

            // 3.缓存未命中,查询数据库
            Shop shop = getById(id);
            // 4.判断数据库中是否有该商铺数据
            if (shop == null) {
                // 数据库中不存在该商铺数据,返回错误信息
                // 防止缓存穿透，如数据库中不存在，则缓存空数据
                stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 5.数据库中有该商铺数据
            // 5.1添加至缓存中,并设置过期时间
            // 防止缓存雪崩，给缓存过期时间添加1至6分钟的随机值
            long randomLong = RandomUtil.randomLong(1, 6);
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + randomLong, TimeUnit.MINUTES);
            // 5.2返回商铺数据
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
    }

    /**
     * 缓存空数据解决缓存穿透，过期时间加上随机值解决缓存雪崩
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id) {
        // 1.查询redis中的商铺缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJSON = stringRedisTemplate.opsForValue().get(shopKey);
        // 2.判断缓存中是否有数据
        if (StrUtil.isNotBlank(shopJSON)) {
            // 缓存命中
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        // 判断是否为空数据
        if ("".equals(shopJSON)) {
            return null;
        }
        // 3.缓存未命中,查询数据库
        Shop shop = getById(id);
        // 4.判断数据库中是否有该商铺数据
        if (shop == null) {
            // 数据库中不存在该商铺数据,返回错误信息
            // 防止缓存穿透，如数据库中不存在，则缓存空数据
            stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 5.数据库中有该商铺数据
        // 5.1添加至缓存中,并设置过期时间
        // 防止缓存雪崩，给缓存过期时间添加1至6分钟的随机值
        long randomLong = RandomUtil.randomLong(1, 6);
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + randomLong, TimeUnit.MINUTES);
        // 5.2返回商铺数据
        return shop;
    }

    /**
     * 尝试获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(ifAbsent);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 保存商铺热点数据至缓存
     * @param id
     * @param expireSeconds
     */
    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询数据库
        Shop shop = getById(id);
        // 添加逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 添加至缓存
        String key = CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));

    }


    /**
     * 更新商铺信息
     *
     * @param shop 商铺数据
     * @return 无
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop == null) {
            return Result.fail("商铺信息不能为空！");
        }
        if (shop.getId() == null) {
            return Result.fail("商铺id不能为空！");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
