package com.btc.service;

import com.btc.util.LogUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Mempool.space API提供者实现
 * 用于测试网（testnet4）数据获取
 * API文档: https://mempool.space/testnet4/docs/
 */
@Component
public class MempoolSpaceProvider implements BlockchainApiProvider {
    
    @Value("${blockchain.api.mempool.base-url:https://mempool.space}")
    private String baseUrl;
    
    @Value("${blockchain.api.mempool.api-key:}")
    private String apiKey;
    
    private RestTemplate restTemplate;
    
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
    }
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 速率限制
    private static final long RATE_LIMIT_DELAY_MS = 300;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 5000;
    
    private volatile long lastApiCallTime = 0;
    
    @Override
    public String getProviderName() {
        return "mempool.space";
    }
    
    @Override
    public String getSupportedNetwork() {
        return "testnet";
    }
    
    @Override
    public boolean isApplicable(String network) {
        // 支持testnet和testnet4
        return "testnet".equalsIgnoreCase(network) || "testnet4".equalsIgnoreCase(network);
    }
    
    /**
     * 获取API路径前缀
     * 测试网使用 /testnet4/api/
     */
    private String getApiPrefix() {
        return baseUrl + "/testnet4/api";
    }
    
    @Override
    public long getLatestBlockHeight() {
        try {
            enforceRateLimit();
            // GET /testnet4/api/blocks/tip/height
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
    
    @Override
    public String getBlockHash(long blockHeight) {
        return getBlockHashWithRetry(blockHeight, 0);
    }
    
    private String getBlockHashWithRetry(long blockHeight, int retryCount) {
        try {
            enforceRateLimit();
            // GET /testnet4/api/block-height/:height
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
    
    @Override
    public List<TransactionInfo> getBlockTransactions(String blockHash) {
        // mempool.space API是分页接口，需要循环获取所有交易
        // 每页返回25个交易，通过start_index参数获取下一页
        List<TransactionInfo> allTransactions = new ArrayList<>();
        int currentIndex = 0;
        int pageCount = 0;
        
        LogUtil.info(this.getClass(), "开始获取区块所有交易: " + blockHash);
        
        while (true) {
            List<TransactionInfo> pageTransactions = getBlockTransactionsPage(blockHash, currentIndex, 0);
            
            if (pageTransactions.isEmpty()) {
                // 没有更多交易了
                break;
            }
            
            allTransactions.addAll(pageTransactions);
            pageCount++;
            
            LogUtil.debug(this.getClass(), String.format(
                "已获取第%d页，本页%d笔交易，累计%d笔交易", 
                pageCount, pageTransactions.size(), allTransactions.size()));
            
            // mempool.space每页最多返回25个交易
            // 如果返回少于25个，说明已经是最后一页
            if (pageTransactions.size() < 25) {
                break;
            }
            
            // 更新索引获取下一页
            currentIndex += pageTransactions.size();
        }
        
        LogUtil.info(this.getClass(), String.format(
            "区块 %s 共获取 %d 笔交易，分 %d 页", blockHash, allTransactions.size(), pageCount));
        
        return allTransactions;
    }
    
    /**
     * 获取单页区块交易
     * @param blockHash 区块哈希
     * @param startIndex 起始索引（0表示从第一笔开始）
     * @param retryCount 重试次数
     */
    private List<TransactionInfo> getBlockTransactionsPage(String blockHash, int startIndex, int retryCount) {
        try {
            enforceRateLimit();
            // GET /testnet4/api/block/:hash/txs[/:start_index]
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
    
    @Override
    public String getBlockDataJson(String blockHash) {
        return getBlockDataJsonWithRetry(blockHash, 0);
    }
    
    private String getBlockDataJsonWithRetry(String blockHash, int retryCount) {
        try {
            enforceRateLimit();
            // 获取区块头信息
            String headerUrl = getApiPrefix() + "/block/" + blockHash + "/header";
            LogUtil.debug(this.getClass(), "请求区块头: " + headerUrl);
            ResponseEntity<String> headerResponse = restTemplate.getForEntity(headerUrl, String.class);
            
            // 获取区块交易列表
            List<TransactionInfo> transactions = getBlockTransactions(blockHash);
            
            // 构建区块数据JSON
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{\"hash\":\"").append(blockHash).append("\",");
            jsonBuilder.append("\"header\":").append(headerResponse.getBody()).append(",");
            jsonBuilder.append("\"txs\":[");
            
            for (int i = 0; i < transactions.size(); i++) {
                if (i > 0) jsonBuilder.append(",");
                TransactionInfo tx = transactions.get(i);
                jsonBuilder.append("{\"txid\":\"").append(tx.txId()).append("\",");
                jsonBuilder.append("\"fee\":").append(tx.fee()).append(",");
                jsonBuilder.append("\"size\":").append(tx.size()).append(",");
                jsonBuilder.append("\"vsize\":").append(tx.vsize()).append("}");
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
}