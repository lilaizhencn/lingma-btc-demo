package com.btc.service;

import com.btc.util.LogUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 区块数据Kafka生产者服务
 * 负责将区块数据发送到Kafka队列
 */
@Service
public class BlockDataKafkaProducer {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Value("${spring.kafka.topic.block-data:bitcoin-block-data}")
    private String topicName;
    
    /**
     * 发送区块数据到Kafka
     * 
     * @param blockHeight 区块高度
     * @param blockDataJson 区块数据JSON
     * @return 消息ID
     */
    public String sendBlockData(long blockHeight, String blockDataJson) {
        // 使用区块高度作为消息key，保证同一区块的消息有序
        String key = String.valueOf(blockHeight);
        String messageId = UUID.randomUUID().toString();
        
        LogUtil.info(this.getClass(), "发送区块数据到Kafka: topic=" + topicName + ", key=" + key + ", messageId=" + messageId);
        
        try {
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topicName, key, blockDataJson);
            
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    LogUtil.error(this.getClass(), "发送区块数据到Kafka失败: " + blockHeight, ex);
                } else {
                    LogUtil.debug(this.getClass(), "区块数据发送成功: " + blockHeight + 
                        ", partition=" + result.getRecordMetadata().partition() +
                        ", offset=" + result.getRecordMetadata().offset());
                }
            });
            
            // 等待发送完成（同步确认）
            future.join();
            
            return messageId;
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "发送区块数据到Kafka异常: " + blockHeight, e);
            throw new RuntimeException("发送区块数据到Kafka失败", e);
        }
    }
    
    /**
     * 异步发送区块数据到Kafka
     * 
     * @param blockHeight 区块高度
     * @param blockDataJson 区块数据JSON
     * @return CompletableFuture
     */
    public CompletableFuture<SendResult<String, String>> sendBlockDataAsync(long blockHeight, String blockDataJson) {
        String key = String.valueOf(blockHeight);
        LogUtil.debug(this.getClass(), "异步发送区块数据到Kafka: topic=" + topicName + ", key=" + key);
        
        return kafkaTemplate.send(topicName, key, blockDataJson);
    }
}