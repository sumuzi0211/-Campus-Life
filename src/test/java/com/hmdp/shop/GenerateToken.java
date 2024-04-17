package com.hmdp.shop;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * @author ghp
 * @date 2023/2/7
 * @title
 * @description
 */
@SpringBootTest
@RunWith(SpringJUnit4ClassRunner.class)
@AutoConfigureMockMvc
@Slf4j
public class GenerateToken {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private IUserService userService;

    @Resource
    private ObjectMapper mapper;

    @Test
    // 忽视异常
    @SneakyThrows
    public void login() {
        // 查询数据库得到1000个号码
        List<String> phoneList = userService.lambdaQuery()
                .select(User::getPhone)
                .last("limit 1000")
                .list().stream().map(User::getPhone).collect(Collectors.toList());
        // 使用线程池，线程池总放入1000个号码，提高效率
        ExecutorService executorService = ThreadUtil.newExecutor(phoneList.size());
        // 创建List集合，存储生成的token。多线程下使用CopyOnWriteArrayList，实现读写分离，保障线程安全（ArrayList不能保障线程安全）
        List<String> tokenList = new CopyOnWriteArrayList<>();
        // 创建CountDownLatch（线程计数器）对象，用于协调线程间的同步
        CountDownLatch countDownLatch = new CountDownLatch(phoneList.size());
        // 遍历phoneList，发送请求，然后将获取的token写入tokenList中
        phoneList.forEach(phone -> {
            executorService.execute(() -> {
                try {
                    // 发送获取验证码的请求，获取验证码
                    String codeJson = mockMvc.perform(MockMvcRequestBuilders
                                    .post("/user/code")
                                    .queryParam("phone", phone))
                            .andExpect(MockMvcResultMatchers.status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    // 将返回的JSON字符串反序列化为Result对象
                    Result result = mapper.readerFor(Result.class).readValue(codeJson);
                    Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的验证码失败", phone));
                    String code = result.getData().toString();

                    // 创建一个登录表单
                    // 使用建造者模式构建 登录信息对象，我这里就没有使用了，我是直接使用new（效率较低不推荐使用）
//                    LoginFormDTO formDTO = LoginFormDTO.builder().code(code).phone(phone).build();
                    LoginFormDTO formDTO = new LoginFormDTO();
                    formDTO.setCode(code);
                    formDTO.setPhone(phone);
                    // 将LoginFormDTO对象序列化为JSON
                    String json = mapper.writeValueAsString(formDTO);

                    // 发送登录请求，获取token
                    // 发送登录请求，获取返回信息（JSON字符串，其中包含token）
                    String tokenJson = mockMvc.perform(MockMvcRequestBuilders
                                    .post("/user/login").content(json).contentType(MediaType.APPLICATION_JSON))
                            .andExpect(MockMvcResultMatchers.status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    // 将JSON字符串反序列化为Result对象
                    result = mapper.readerFor(Result.class).readValue(tokenJson);
                    Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的token失败,json为“%s”", phone, json));
                    String token = result.getData().toString();
                    tokenList.add(token);
                    // 线程计数器减一
                    countDownLatch.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
        // 线程计数器为0时，表示所有线程执行完毕，此时唤醒主线程
        countDownLatch.await();
        // 关闭线程池
        executorService.shutdown();
        Assert.isTrue(tokenList.size() == phoneList.size());
        // 所有线程都获取了token，此时将所有的token写入tokens.txt文件中
        writeToTxt(tokenList, "\\tokens.txt");
        log.info("程序执行完毕！");
    }

    /**
     * 生成tokens.txt文件
     * @param list
     * @param suffixPath
     * @throws Exception
     */
    private static void writeToTxt(List<String> list, String suffixPath) throws Exception {
        // 1. 创建文件
        File file = new File(System.getProperty("user.dir") + "\\src\\main\\resources" + suffixPath);
        if (!file.exists()) {
            file.createNewFile();
        }
        // 2. 输出
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        for (String content : list) {
            bw.write(content);
            bw.newLine();
        }
        bw.close();
        log.info("tokens.txt文件生成完毕！");
    }
}
