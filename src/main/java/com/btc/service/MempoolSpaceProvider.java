package com.btc.service;

import com.btc.util.LogUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Mempool.space API服务
 * 用于获取比特币区块链数据
 * API文档: https://mempool.space/docs/api
 */
@Service
public class MempoolSpaceProvider {
    
    @Value("${blockchain.api.mempool.base-url:https://mempool.space}")
    private String baseUrl;
    
    @Value("${blockchain.api.mempool.network:testnet4}")
    private String network;
    
    @Value("${blockchain.api.mempool.api-key:}")
    private String apiKey;
    
    private RestTemplate restTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 速率限制
    private static final long RATE_LIMIT_DELAY_MS = 300;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 5000;
    
    private volatile long lastApiCallTime = 0;
    
    /**
     * 初始化RestTemplate，添加API Key认证拦截器
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        this.restTemplate = new RestTemplate();
        
        // 如果配置了API Key，添加认证拦截器
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            restTemplate.getInterceptors().add((request, body, execution) -> {
                request.getHeaders().add("Authorization", "Bearer " + apiKey);
                return execution.execute(request, body);
            });
            LogUtil.info(this.getClass(), "Mempool.space API Key已配置，启用认证");
        } else {
            LogUtil.info(this.getClass(), "Mempool.space 未配置API Key，使用公开访问");
        }
        
        LogUtil.info(this.getClass(), "Mempool.space 初始化完成，网络: " + network);
    }
    
    /**
     * 获取API路径前缀
     * 测试网使用 /testnet4/api/，主网使用 /api/
     */
    private String getApiPrefix() {
        if ("mainnet".equalsIgnoreCase(network)) {
            return baseUrl + "/api";
        }
        return baseUrl + "/testnet4/api";
    }
    
    /**
     * 获取最新区块高度
     */
    public long getLatestBlockHeight() {
        try {
            enforceRateLimit();
            String url = getApiPrefix() + "/blocks/tip/height";
            
            LogUtil.debug(this.getClass(), "请求最新区块高度: " + url);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Long.parseLong(response.getBody().trim());
            }
            throw new RuntimeException("获取区块高度失败，HTTP状态码: " + response.getStatusCode());
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "获取最新区块高度失败: " + e.getMessage(), e);
            throw new RuntimeException("获取最新区块高度失败", e);
        }
    }
    
    /**
     * 根据区块高度获取区块哈希
     */
    public String getBlockHash(long blockHeight) {
        return getBlockHashWithRetry(blockHeight, 0);
    }
    
    private String getBlockHashWithRetry(long blockHeight, int retryCount) {
        try {
            enforceRateLimit();
            String url = getApiPrefix() + "/block-height/" + blockHeight;
            
            LogUtil.debug(this.getClass(), "请求区块哈希: " + url);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String hash = response.getBody().trim();
                if (hash.length() == 64) {  // 比特币区块哈希长度为64
                    return hash;
                }
            }
            throw new RuntimeException("未找到指定高度的区块: " + blockHeight);
            
        } catch (HttpClientErrorException.TooManyRequests e) {
            LogUtil.warn(this.getClass(), "遇到429速率限制，重试次数: " + retryCount);
            return handleRetryForHash(blockHeight, retryCount);
            
        } catch (Exception e) {
            if (retryCount < MAX_RETRY_ATTEMPTS && shouldRetry(e)) {
                LogUtil.warn(this.getClass(), "获取区块哈希失败，重试次数: " + retryCount);
                return handleRetryForHash(blockHeight, retryCount);
            }
            throw new RuntimeException("获取区块哈希失败: " + blockHeight, e);
        }
    }
    
    /**
     * 获取区块的交易列表
     */
    public List<TransactionInfo> getBlockTransactions(String blockHash) {
        List<TransactionInfo> allTransactions = new ArrayList<>();
        int currentIndex = 0;
        int pageCount = 0;
        
        LogUtil.info(this.getClass(), "开始获取区块所有交易: " + blockHash);
        
        while (true) {
            List<TransactionInfo> pageTransactions = getBlockTransactionsPage(blockHash, currentIndex, 0);
            
            if (pageTransactions.isEmpty()) {
                break;
            }
            
            allTransactions.addAll(pageTransactions);
            pageCount++;
            
            LogUtil.debug(this.getClass(), String.format(
                "已获取第%d页，本页%d笔交易，累计%d笔交易", 
                pageCount, pageTransactions.size(), allTransactions.size()));
            
            if (pageTransactions.size() < 25) {
                break;
            }
            
            currentIndex += pageTransactions.size();
        }
        
        LogUtil.info(this.getClass(), String.format(
            "区块 %s 共获取 %d 笔交易，分 %d 页", blockHash, allTransactions.size(), pageCount));
        
        return allTransactions;
    }
    
    /**
     * 获取单页区块交易
     */
    private List<TransactionInfo> getBlockTransactionsPage(String blockHash, int startIndex, int retryCount) {
        try {
            enforceRateLimit();
            String url = getApiPrefix() + "/block/" + blockHash + "/txs";
            if (startIndex > 0) {
                url += "/" + startIndex;
            }
            
            LogUtil.debug(this.getClass(), "请求区块交易页: " + url + " (startIndex=" + startIndex + ")");
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseTransactions(response.getBody());
            }
            throw new RuntimeException("获取区块交易失败，HTTP状态码: " + response.getStatusCode());
            
        } catch (HttpClientErrorException.TooManyRequests e) {
            LogUtil.warn(this.getClass(), "遇到429速率限制，重试次数: " + retryCount);
            return handleRetryForTransactionsPage(blockHash, startIndex, retryCount);
            
        } catch (Exception e) {
            if (retryCount < MAX_RETRY_ATTEMPTS && shouldRetry(e)) {
                LogUtil.warn(this.getClass(), "获取区块交易失败，重试次数: " + retryCount);
                return handleRetryForTransactionsPage(blockHash, startIndex, retryCount);
            }
            throw new RuntimeException("获取区块交易失败: " + blockHash, e);
        }
    }
    
    /**
     * 获取区块完整数据JSON
     * 调用 /block/:hash/txs 接口获取所有交易数据（包含完整的vin/vout）
     */
    public String getBlockDataJson(String blockHash) {
        return getBlockDataJsonWithRetry(blockHash, 0);
    }
    
    private String getBlockDataJsonWithRetry(String blockHash, int retryCount) {
        try {
            List<String> allTxJsons = new ArrayList<>();
            int currentIndex = 0;
            int pageCount = 0;
            
            LogUtil.info(this.getClass(), "开始获取区块完整交易数据: " + blockHash);
            
            while (true) {
                String pageJson = getBlockTransactionsRawJson(blockHash, currentIndex, 0);
                
                if (pageJson == null || pageJson.isEmpty()) {
                    break;
                }
                
                JsonNode txArray = objectMapper.readTree(pageJson);
                if (!txArray.isArray() || txArray.isEmpty()) {
                    break;
                }
                
                for (JsonNode txNode : txArray) {
                    allTxJsons.add(txNode.toString());
                }
                
                pageCount++;
                int pageSize = txArray.size();
                LogUtil.debug(this.getClass(), String.format(
                    "已获取第%d页，本页%d笔交易，累计%d笔交易", 
                    pageCount, pageSize, allTxJsons.size()));
                
                if (pageSize < 25) {
                    break;
                }
                
                currentIndex += pageSize;
            }
            
            LogUtil.info(this.getClass(), String.format(
                "区块 %s 共获取 %d 笔交易，分 %d 页", blockHash, allTxJsons.size(), pageCount));
            
            // 构建最终的区块数据JSON
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{\"hash\":\"").append(blockHash).append("\",");
            jsonBuilder.append("\"txs\":[");
            
            for (int i = 0; i < allTxJsons.size(); i++) {
                if (i > 0) jsonBuilder.append(",");
                jsonBuilder.append(allTxJsons.get(i));
            }
            
            jsonBuilder.append("]}");
            return jsonBuilder.toString();
            
        } catch (HttpClientErrorException.TooManyRequests e) {
            LogUtil.warn(this.getClass(), "遇到429速率限制，重试次数: " + retryCount);
            return handleRetryForBlockData(blockHash, retryCount);
            
        } catch (Exception e) {
            if (retryCount < MAX_RETRY_ATTEMPTS && shouldRetry(e)) {
                LogUtil.warn(this.getClass(), "获取区块数据失败，重试次数: " + retryCount);
                return handleRetryForBlockData(blockHash, retryCount);
            }
            throw new RuntimeException("获取区块数据失败: " + blockHash, e);
        }
    }
    
    /**
     * 获取单页区块交易的原始JSON字符串
     */
    private String getBlockTransactionsRawJson(String blockHash, int startIndex, int retryCount) {
        try {
            enforceRateLimit();
            String url = getApiPrefix() + "/block/" + blockHash + "/txs";
            if (startIndex > 0) {
                url += "/" + startIndex;
            }
            
            LogUtil.debug(this.getClass(), "请求区块交易页原始数据: " + url + " (startIndex=" + startIndex + ")");
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            throw new RuntimeException("获取区块交易失败，HTTP状态码: " + response.getStatusCode());
            
        } catch (HttpClientErrorException.TooManyRequests e) {
            LogUtil.warn(this.getClass(), "遇到429速率限制，重试次数: " + retryCount);
            return handleRetryForRawJson(blockHash, startIndex, retryCount);
            
        } catch (Exception e) {
            if (retryCount < MAX_RETRY_ATTEMPTS && shouldRetry(e)) {
                LogUtil.warn(this.getClass(), "获取区块交易原始数据失败，重试次数: " + retryCount);
                return handleRetryForRawJson(blockHash, startIndex, retryCount);
            }
            throw new RuntimeException("获取区块交易原始数据失败: " + blockHash, e);
        }
    }
    
    // ===== 重试处理方法 =====
    
    private String handleRetryForRawJson(String blockHash, int startIndex, int retryCount) {
        try {
            Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
            return getBlockTransactionsRawJson(blockHash, startIndex, retryCount + 1);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试过程中被中断", ie);
        }
    }
    
    private List<TransactionInfo> handleRetryForTransactionsPage(String blockHash, int startIndex, int retryCount) {
        try {
            Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
            return getBlockTransactionsPage(blockHash, startIndex, retryCount + 1);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试过程中被中断", ie);
        }
    }
    
    private String handleRetryForBlockData(String blockHash, int retryCount) {
        try {
            Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
            return getBlockDataJsonWithRetry(blockHash, retryCount + 1);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试过程中被中断", ie);
        }
    }
    
    private String handleRetryForHash(long blockHeight, int retryCount) {
        try {
            Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
            return getBlockHashWithRetry(blockHeight, retryCount + 1);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试过程中被中断", ie);
        }
    }
    
    // ===== 解析方法 =====
    
    private List<TransactionInfo> parseTransactions(String json) {
        try {
            List<TransactionInfo> transactions = new ArrayList<>();
            JsonNode root = objectMapper.readTree(json);
            
            if (root.isArray()) {
                for (JsonNode txNode : root) {
                    transactions.add(parseTransaction(txNode));
                }
            }
            return transactions;
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "解析交易数据失败: " + e.getMessage(), e);
            throw new RuntimeException("解析交易数据失败", e);
        }
    }
    
    private TransactionInfo parseTransaction(JsonNode txNode) {
        String txId = txNode.has("txid") ? txNode.get("txid").asText() : "";
        long fee = txNode.has("fee") ? txNode.get("fee").asLong() : 0;
        int size = txNode.has("size") ? txNode.get("size").asInt() : 0;
        int vsize = txNode.has("vsize") ? txNode.get("vsize").asInt() : size;
        
        List<TxInput> inputs = new ArrayList<>();
        if (txNode.has("vin") && txNode.get("vin").isArray()) {
            for (JsonNode vinNode : txNode.get("vin")) {
                inputs.add(parseInput(vinNode));
            }
        }
        
        List<TxOutput> outputs = new ArrayList<>();
        if (txNode.has("vout") && txNode.get("vout").isArray()) {
            for (JsonNode voutNode : txNode.get("vout")) {
                outputs.add(parseOutput(voutNode));
            }
        }
        
        return new TransactionInfo(txId, fee, size, vsize, inputs, outputs);
    }
    
    private TxInput parseInput(JsonNode vinNode) {
        String txId = vinNode.has("txid") ? vinNode.get("txid").asText() : "";
        int vout = vinNode.has("vout") ? vinNode.get("vout").asInt() : 0;
        String scriptSig = "";
        if (vinNode.has("scriptsig") && !vinNode.get("scriptsig").isNull()) {
            scriptSig = vinNode.get("scriptsig").asText();
        } else if (vinNode.has("scriptsig_asm") && !vinNode.get("scriptsig_asm").isNull()) {
            scriptSig = vinNode.get("scriptsig_asm").asText();
        }
        long sequence = vinNode.has("sequence") ? vinNode.get("sequence").asLong() : 0;
        
        String address = "";
        long value = 0;
        if (vinNode.has("prevout") && !vinNode.get("prevout").isNull()) {
            JsonNode prevout = vinNode.get("prevout");
            address = prevout.has("scriptpubkey_address") ? 
                      prevout.get("scriptpubkey_address").asText() : "";
            value = prevout.has("value") ? prevout.get("value").asLong() : 0;
        }
        
        return new TxInput(txId, vout, scriptSig, sequence, address, value);
    }
    
    private TxOutput parseOutput(JsonNode voutNode) {
        String scriptPubKey = voutNode.has("scriptpubkey") ? voutNode.get("scriptpubkey").asText() : "";
        String address = voutNode.has("scriptpubkey_address") ? 
                         voutNode.get("scriptpubkey_address").asText() : "";
        long value = voutNode.has("value") ? voutNode.get("value").asLong() : 0;
        return new TxOutput(scriptPubKey, address, value);
    }
    
    // ===== 工具方法 =====
    
    private synchronized void enforceRateLimit() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastCall = currentTime - lastApiCallTime;
        
        if (timeSinceLastCall < RATE_LIMIT_DELAY_MS) {
            try {
                Thread.sleep(RATE_LIMIT_DELAY_MS - timeSinceLastCall);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastApiCallTime = System.currentTimeMillis();
    }
    
    private boolean shouldRetry(Exception e) {
        return e instanceof java.net.SocketTimeoutException ||
               e instanceof java.net.ConnectException ||
               e instanceof java.net.UnknownHostException ||
               e instanceof HttpServerErrorException;
    }
    
    // ===== 数据记录类 =====
    
    /**
     * 交易信息
     */
    public record TransactionInfo(
        String txId,
        long fee,
        int size,
        int vsize,
        List<TxInput> inputs,
        List<TxOutput> outputs
    ) {}
    
    /**
     * 交易输入
     */
    public record TxInput(
        String txId,
        int vout,
        String scriptSig,
        long sequence,
        String address,
        long value
    ) {}
    
    /**
     * 交易输出
     */
    public record TxOutput(
        String scriptPubKey,
        String address,
        long value
    ) {}
}