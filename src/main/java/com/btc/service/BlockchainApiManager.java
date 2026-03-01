package com.btc.service;

import com.btc.util.LogUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;

/**
 * 区块链API管理器
 * 根据当前网络配置自动选择合适的API提供者
 */
@Service
public class BlockchainApiManager {
    
    @Value("${bitcoin.network:testnet}")
    private String currentNetwork;
    
    @Autowired
    private List<BlockchainApiProvider> providers;
    
    private BlockchainApiProvider currentProvider;
    
    @PostConstruct
    public void init() {
        selectProvider(currentNetwork);
        LogUtil.info(this.getClass(), "区块链API管理器初始化完成，当前网络: " + currentNetwork + 
                    ", 使用提供者: " + (currentProvider != null ? currentProvider.getProviderName() : "无"));
    }
    
    /**
     * 选择适用于指定网络的API提供者
     */
    public synchronized void selectProvider(String network) {
        this.currentNetwork = network;
        this.currentProvider = findProvider(network)
                .orElseThrow(() -> new RuntimeException("未找到适用于网络 " + network + " 的API提供者"));
        LogUtil.info(this.getClass(), "切换API提供者: " + currentProvider.getProviderName() + " (网络: " + network + ")");
    }
    
    /**
     * 查找适用于指定网络的提供者
     */
    private Optional<BlockchainApiProvider> findProvider(String network) {
        return providers.stream()
                .filter(p -> p.isApplicable(network))
                .findFirst();
    }
    
    /**
     * 获取当前API提供者
     */
    public BlockchainApiProvider getCurrentProvider() {
        if (currentProvider == null) {
            selectProvider(currentNetwork);
        }
        return currentProvider;
    }
    
    /**
     * 获取当前网络
     */
    public String getCurrentNetwork() {
        return currentNetwork;
    }
    
    /**
     * 获取最新区块高度
     */
    public long getLatestBlockHeight() {
        return getCurrentProvider().getLatestBlockHeight();
    }
    
    /**
     * 根据区块高度获取区块哈希
     */
    public String getBlockHash(long blockHeight) {
        return getCurrentProvider().getBlockHash(blockHeight);
    }
    
    /**
     * 获取区块交易列表
     */
    public List<BlockchainApiProvider.TransactionInfo> getBlockTransactions(String blockHash) {
        return getCurrentProvider().getBlockTransactions(blockHash);
    }
    
    /**
     * 获取区块数据JSON
     */
    public String getBlockDataJson(String blockHash) {
        return getCurrentProvider().getBlockDataJson(blockHash);
    }
    
    /**
     * 获取当前提供者名称
     */
    public String getCurrentProviderName() {
        return getCurrentProvider().getProviderName();
    }
}