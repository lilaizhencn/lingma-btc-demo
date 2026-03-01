package com.btc.util;

import com.btc.Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
public class LogUtilTest {

    @Test
    public void testLogLevels() {
        System.out.println("=== Log4j2日志级别测试 ===");
        
        // 测试不同级别的日志输出
        LogUtil.debug(LogUtilTest.class, "这是DEBUG级别日志消息");
        LogUtil.info(LogUtilTest.class, "这是INFO级别日志消息");
        LogUtil.warn(LogUtilTest.class, "这是WARN级别日志消息");
        LogUtil.error(LogUtilTest.class, "这是ERROR级别日志消息");
        
        // 测试带异常的日志
        try {
            throw new RuntimeException("测试异常");
        } catch (Exception e) {
            LogUtil.error(LogUtilTest.class, "捕获到异常:", e);
        }
        
        System.out.println("✅ Log4j2日志测试完成");
    }

    @Test
    public void testLogPerformance() {
        System.out.println("\n=== 日志性能测试 ===");
        
        long startTime = System.currentTimeMillis();
        
        // 测试大量日志输出性能
        for (int i = 0; i < 1000; i++) {
            LogUtil.debug(LogUtilTest.class, "性能测试日志消息 #" + i);
        }
        
        long endTime = System.currentTimeMillis();
        System.out.println("输出1000条DEBUG日志耗时: " + (endTime - startTime) + "ms");
        System.out.println("✅ 日志性能测试完成");
    }
}