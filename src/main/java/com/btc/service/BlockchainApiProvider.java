package com.btc.service;

import java.util.List;

/**
 * 区块链API提供者接口
 * 支持不同的区块链数据源（blockchain.info, mempool.space等）
 */
public interface BlockchainApiProvider {
    
    /**
     * 获取API提供者名称
     */
    String getProviderName();
    
    /**
     * 获取支持的比特币网络
     */
    String getSupportedNetwork();
    
    /**
     * 检查此提供者是否适用于当前网络
     */
    boolean isApplicable(String network);
    
    /**
     * 获取最新区块高度
     */
    long getLatestBlockHeight();
    
    /**
     * 根据区块高度获取区块哈希
     */
    String getBlockHash(long blockHeight);
    
    /**
     * 获取区块的交易列表
     */
    List<TransactionInfo> getBlockTransactions(String blockHash);
    
    /**
     * 获取完整的区块数据JSON
     */
    String getBlockDataJson(String blockHash);
    
    /**
     * 交易信息
     */
    record TransactionInfo(
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
    record TxInput(
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
    record TxOutput(
        String scriptPubKey,
        String address,
        long value
    ) {}
}