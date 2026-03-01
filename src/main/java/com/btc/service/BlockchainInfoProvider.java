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
 * Blockchain.info API提供者实现
 * 用于主网数据获取
 */
@Component
public class BlockchainInfoProvider implements BlockchainApiProvider {
    
    @Value("${blockchain.api.blockchain-info.base-url:https://blockchain.info}")
    private String baseUrl;
    
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 速率限制
    private static final long RATE_LIMIT_DELAY_MS = 500;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 10000;
    
    private volatile long lastApiCallTime = 0;
    
    @Override
    public String getProviderName() {
        return "blockchain.info";
    }
    
    @Override
    public String getSupportedNetwork() {
        return "mainnet";
    }
    
    @Override
    public boolean isApplicable(String network) {
        return "mainnet".equalsIgnoreCase(network);
    }
    
    @Override
    public long getLatestBlockHeight() {
        try {
            enforceRateLimit();
            String url = baseUrl + "/q/getblockcount";
            
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
            String url = baseUrl + "/block-height/" + blockHeight + "?format=json";
            
            LogUtil.debug(this.getClass(), "请求区块哈希: " + url);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("blocks") && !root.get("blocks").isEmpty()) {
                    return root.get("blocks").get(0).get("hash").asText();
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
    
    private String handleRetryForHash(long blockHeight, int retryCount) {
        try {
            Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
            return getBlockHashWithRetry(blockHeight, retryCount + 1);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试过程中被中断", ie);
        }
    }
    
    @Override
    public List<TransactionInfo> getBlockTransactions(String blockHash) {
        try {
            String json = getBlockDataJson(blockHash);
            JsonNode root = objectMapper.readTree(json);
            
            List<TransactionInfo> transactions = new ArrayList<>();
            if (root.has("tx") && root.get("tx").isArray()) {
                for (JsonNode txNode : root.get("tx")) {
                    transactions.add(parseTransaction(txNode));
                }
            }
            return transactions;
            
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "获取区块交易失败: " + blockHash, e);
            throw new RuntimeException("获取区块交易失败", e);
        }
    }
    
    @Override
    public String getBlockDataJson(String blockHash) {
        return getBlockDataJsonWithRetry(blockHash, 0);
    }
    
    private String getBlockDataJsonWithRetry(String blockHash, int retryCount) {
        try {
            enforceRateLimit();
            String url = baseUrl + "/rawblock/" + blockHash;
            
            LogUtil.debug(this.getClass(), "请求区块数据: " + url);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            throw new RuntimeException("获取区块数据失败，HTTP状态码: " + response.getStatusCode());
            
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
    
    private String handleRetryForBlockData(String blockHash, int retryCount) {
        try {
            Thread.sleep(RETRY_DELAY_MS * (retryCount + 1));
            return getBlockDataJsonWithRetry(blockHash, retryCount + 1);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试过程中被中断", ie);
        }
    }
    
    private TransactionInfo parseTransaction(JsonNode txNode) {
        String txId = txNode.has("hash") ? txNode.get("hash").asText() : "";
        long fee = txNode.has("fee") ? txNode.get("fee").asLong() : 0;
        int size = txNode.has("size") ? txNode.get("size").asInt() : 0;
        int vsize = txNode.has("vsize") ? txNode.get("vsize").asInt() : size;
        
        List<TxInput> inputs = new ArrayList<>();
        if (txNode.has("inputs") && txNode.get("inputs").isArray()) {
            for (JsonNode inputNode : txNode.get("inputs")) {
                inputs.add(parseInput(inputNode));
            }
        }
        
        List<TxOutput> outputs = new ArrayList<>();
        if (txNode.has("out") && txNode.get("out").isArray()) {
            for (JsonNode outputNode : txNode.get("out")) {
                outputs.add(parseOutput(outputNode));
            }
        }
        
        return new TransactionInfo(txId, fee, size, vsize, inputs, outputs);
    }
    
    private TxInput parseInput(JsonNode inputNode) {
        String txId = "";
        int vout = 0;
        String scriptSig = "";
        long sequence = 0;
        String address = "";
        long value = 0;
        
        if (inputNode.has("prev_out")) {
            JsonNode prevOut = inputNode.get("prev_out");
            vout = prevOut.has("n") ? prevOut.get("n").asInt() : 0;
            address = prevOut.has("addr") ? prevOut.get("addr").asText() : "";
            value = prevOut.has("value") ? prevOut.get("value").asLong() : 0;
        }
        
        return new TxInput(txId, vout, scriptSig, sequence, address, value);
    }
    
    private TxOutput parseOutput(JsonNode outputNode) {
        String scriptPubKey = "";
        String address = outputNode.has("addr") ? outputNode.get("addr").asText() : "";
        long value = outputNode.has("value") ? outputNode.get("value").asLong() : 0;
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