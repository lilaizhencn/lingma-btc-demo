package com.btc.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 日志工具类
 * 提供统一的日志记录方法
 */
public class LogUtil {
    
    /**
     * 获取指定类的Logger实例
     */
    public static Logger getLogger(Class<?> clazz) {
        return LogManager.getLogger(clazz);
    }
    
    /**
     * 记录调试信息
     */
    public static void debug(Class<?> clazz, String message) {
        getLogger(clazz).debug(message);
    }
    
    /**
     * 记录普通信息
     */
    public static void info(Class<?> clazz, String message) {
        getLogger(clazz).info(message);
    }
    
    /**
     * 记录警告信息
     */
    public static void warn(Class<?> clazz, String message) {
        getLogger(clazz).warn(message);
    }
    
    /**
     * 记录错误信息
     */
    public static void error(Class<?> clazz, String message) {
        getLogger(clazz).error(message);
    }
    
    /**
     * 记录错误信息（带异常）
     */
    public static void error(Class<?> clazz, String message, Throwable throwable) {
        getLogger(clazz).error(message, throwable);
    }
}