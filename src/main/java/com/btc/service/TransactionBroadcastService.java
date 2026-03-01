package com.btc.service;

import com.btc.util.LogUtil;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static com.btc.service.SimpleEcdsaSigningService.getBytes;

/**
 * 交易广播服务
 * 使用blockchain.info API进行交易广播
 */
@Service
public class TransactionBroadcastService {

    private static final String BLOCKCHAIN_API_URL = "https://blockchain.info/pushtx";

    /**
     * 广播已签名的交易
     * @param signedTxHex 已签名的交易十六进制数据
     * @return 交易哈希
     */
    public String broadcastTransaction(String signedTxHex) {
        try {
            LogUtil.info(this.getClass(), "开始广播交易");
            LogUtil.info(this.getClass(), "交易数据长度: " + signedTxHex.length());

            // 创建RestTemplate实例
            RestTemplate restTemplate = new RestTemplate();

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // 构造请求体
            String requestBody = "tx=" + signedTxHex;

            HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

            // 发送POST请求到blockchain.info API
            ResponseEntity<String> response = restTemplate.postForEntity(
                BLOCKCHAIN_API_URL,
                requestEntity,
                String.class
            );

            // 处理响应
            if (response.getStatusCode().is2xxSuccessful()) {
                String responseBody = response.getBody();
                LogUtil.info(this.getClass(), "交易广播成功: " + responseBody);
                
                // blockchain.info成功时通常返回交易哈希
                if (responseBody != null && responseBody.length() == 64) {
                    return responseBody; // 这就是交易哈希
                } else {
                    // 如果不是64字符的哈希，可能需要从其他地方获取
                    return extractTxHash(signedTxHex);
                }
            } else {
                LogUtil.error(this.getClass(), "交易广播失败，HTTP状态码: " + response.getStatusCode());
                throw new RuntimeException("交易广播失败: " + response.getStatusCode());
            }

        } catch (Exception e) {
            LogUtil.error(this.getClass(), "交易广播异常: " + e.getMessage(), e);
            throw new RuntimeException("交易广播失败", e);
        }
    }

    /**
     * 从交易数据中提取交易哈希
     * @param txHex 交易十六进制数据
     * @return 交易哈希
     */
    private String extractTxHash(String txHex) {
        try {
            // 简化实现：计算交易数据的双重SHA256哈希
            // 实际项目中应该使用BitcoinJ的Transaction类来获取真实的交易哈希
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            
            // 第一次SHA256
            byte[] firstHash = sha256.digest(hexToBytes(txHex));
            
            // 第二次SHA256
            byte[] secondHash = sha256.digest(firstHash);
            
            // 反转字节顺序（Bitcoin使用小端序）
            byte[] reversedHash = reverseBytes(secondHash);
            
            return bytesToHex(reversedHash);
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "提取交易哈希失败: " + e.getMessage(), e);
            return "unknown_tx_hash";
        }
    }

    /**
     * 反转字节数组
     */
    private byte[] reverseBytes(byte[] bytes) {
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            reversed[i] = bytes[bytes.length - 1 - i];
        }
        return reversed;
    }

    /**
     * 十六进制字符串转字节数组
     */
    private byte[] hexToBytes(String hex) {
        return getBytes(hex);
    }

    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * 查询交易状态
     * @param txHash 交易哈希
     * @return 交易确认数，-1表示未找到
     */
    public int queryTransactionStatus(String txHash) {
        try {
            LogUtil.info(this.getClass(), "查询交易状态: " + txHash);

            // 使用blockchain.info API查询交易
            String apiUrl = "https://blockchain.info/rawtx/" + txHash;
            
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Map> response = restTemplate.getForEntity(apiUrl, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map txData = response.getBody();
                if (txData != null && txData.containsKey("confirmations")) {
                    Object confirmationsObj = txData.get("confirmations");
                    if (confirmationsObj instanceof Number) {
                        int confirmations = ((Number) confirmationsObj).intValue();
                        LogUtil.info(this.getClass(), "交易确认数: " + confirmations);
                        return confirmations;
                    }
                }
            }

            LogUtil.warn(this.getClass(), "无法获取交易确认信息");
            return -1;

        } catch (Exception e) {
            LogUtil.error(this.getClass(), "查询交易状态失败: " + e.getMessage(), e);
            return -1;
        }
    }
}