package com.btc.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实的Bitcoin节点RPC客户端
 * 实现生产环境可用的区块链API调用
 */
@Component
public class BitcoinRpcClient {
    
    @Value("${bitcoin.node.rpc.url:http://localhost:18332}")
    private String rpcUrl;
    
    @Value("${bitcoin.node.rpc.username:rpcuser}")
    private String rpcUsername;
    
    @Value("${bitcoin.node.rpc.password:rpcpassword}")
    private String rpcPassword;
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicInteger requestId;
    
    public BitcoinRpcClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.requestId = new AtomicInteger(1);
    }
    
    /**
     * 调用RPC方法
     */
    public JsonNode call(String method, Object... params) {
        try {
            String requestBody = constructRequest(method, params);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rpcUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + 
                    Base64.getEncoder().encodeToString((rpcUsername + ":" + rpcPassword).getBytes()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode responseJson = objectMapper.readTree(response.body());
            
            if (responseJson.has("error") && !responseJson.get("error").isNull()) {
                throw new RuntimeException("RPC调用错误: " + responseJson.get("error").toString());
            }
            
            return responseJson.get("result");
            
        } catch (Exception e) {
            throw new RuntimeException("RPC调用失败: " + method, e);
        }
    }
    
    /**
     * 构造RPC请求JSON
     */
    private String constructRequest(String method, Object... params) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"jsonrpc\":\"2.0\",");
            json.append("\"id\":").append(requestId.getAndIncrement()).append(",");
            json.append("\"method\":\"").append(method).append("\",");
            json.append("\"params\":[");
            
            for (int i = 0; i < params.length; i++) {
                if (i > 0) json.append(",");
                json.append(objectMapper.writeValueAsString(params[i]));
            }
            
            json.append("]}");
            return json.toString();
        } catch (Exception e) {
            throw new RuntimeException("构造RPC请求失败", e);
        }
    }
    
    /**
     * 获取钱包余额
     */
    public double getBalance() {
        JsonNode result = call("getbalance");
        return result.asDouble();
    }
    
    /**
     * 获取区块高度
     */
    public long getBlockCount() {
        JsonNode result = call("getblockcount");
        return result.asLong();
    }
    
    /**
     * 获取最新区块哈希
     */
    public String getBestBlockHash() {
        JsonNode result = call("getbestblockhash");
        return result.asText();
    }
    
    /**
     * 获取区块详情
     */
    public JsonNode getBlock(String blockHash) {
        return call("getblock", blockHash);
    }
    
    /**
     * 发送原始交易
     */
    public String sendRawTransaction(String hexString) {
        JsonNode result = call("sendrawtransaction", hexString);
        return result.asText();
    }
    
    /**
     * 估算手续费
     */
    public double estimateSmartFee(int confTarget) {
        JsonNode result = call("estimatesmartfee", confTarget);
        if (result.has("feerate")) {
            return result.get("feerate").asDouble();
        }
        return 0.0001; // 默认手续费率
    }
    
    /**
     * 验证地址
     */
    public boolean validateAddress(String address) {
        JsonNode result = call("validateaddress", address);
        return result.has("isvalid") && result.get("isvalid").asBoolean();
    }
    
    /**
     * 列出未花费的交易输出
     */
    public JsonNode listUnspent() {
        return call("listunspent");
    }
    
    /**
     * 创建原始交易
     */
    public String createRawTransaction(Object inputs, Object outputs) {
        JsonNode result = call("createrawtransaction", inputs, outputs);
        return result.asText();
    }
    
    /**
     * 签名原始交易
     */
    public JsonNode signRawTransactionWithWallet(String hexString) {
        return call("signrawtransactionwithwallet", hexString);
    }
    
    /**
     * 测试连接
     */
    public boolean testConnection() {
        try {
            JsonNode result = call("getnetworkinfo");
            return result.has("version");
        } catch (Exception e) {
            return false;
        }
    }
}