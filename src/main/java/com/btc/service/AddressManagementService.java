package com.btc.service;

import com.btc.entity.BtcAddress;
import com.btc.util.LogUtil;
import com.btc.repository.BtcAddressRepository;
import com.btc.wallet.HDWalletManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 地址管理服务
 * 
 * BIP44路径结构: m/44'/coin_type'/account'/change/address_index
 * - coin_type: 币种类型 (BTC主网=0, 测试网=1, LTC=2, DASH=5, DOGE=3)
 * - account: 账户/用户ID (Root地址=0, 用户地址=用户ID)
 * - change: 业务ID/链类型 (0=外部链充值地址, 1=内部链找零地址)
 * - address_index: 地址索引 (每个用户可以生成多个地址，递增)
 * 
 * Root地址: m/44'/coin_type'/0'/0/0 (所有路径值都为0)
 * 热钱包地址 = Root地址
 */
@Service
@Transactional
public class AddressManagementService {
    
    private final BtcAddressRepository addressRepository;
    
    @Autowired
    private HDWalletManager hdWalletManager;
    
    // 默认币种类型
    private static final int DEFAULT_COIN_TYPE_TESTNET = 1;
    private static final int DEFAULT_COIN_TYPE_MAINNET = 0;
    
    // 业务ID常量
    public static final int CHANGE_EXTERNAL = 0;  // 外部链 - 充值地址
    public static final int CHANGE_INTERNAL = 1;  // 内部链 - 找零地址
        
    public AddressManagementService(BtcAddressRepository addressRepository) {
        this.addressRepository = addressRepository;
    }
    
    /**
     * 检查是否已配置root地址
     * @return true表示已配置，false表示未配置
     */
    public boolean isRootAddressConfigured() {
        List<BtcAddress> rootAddresses = addressRepository.findByAddressType(BtcAddress.AddressType.ROOT);
        return !rootAddresses.isEmpty() && rootAddresses.getFirst().getActive();
    }
    
    /**
     * 获取Root地址（热钱包地址）
     * Root地址路径: m/44'/coin_type'/0'/0/0
     * 所有路径值都为0
     * @return Root地址
     */
    public BtcAddress generateRootAddress() {
        // 检查是否已存在root地址
        List<BtcAddress> existingRoots = addressRepository.findByAddressType(BtcAddress.AddressType.ROOT);
        if (!existingRoots.isEmpty()) {
            LogUtil.info(this.getClass(), "Root地址已存在: " + existingRoots.get(0).getAddress());
            return existingRoots.get(0);
        }
        
        // 获取当前网络的coin_type
        int coinType = getCurrentCoinType();
        
        // Root地址路径: m/44'/coin_type'/0'/0/0
        // 所有路径值都为0
        int account = 0;
        int change = 0;
        int index = 0;
        
        String derivationPath = buildDerivationPath(coinType, account, change, index);
        
        String address;
        try {
            if (hdWalletManager.isInitialized()) {
                // 使用HD钱包派生地址
                address = hdWalletManager.deriveAddress(coinType, account, change, index);
                LogUtil.info(this.getClass(), "使用HD钱包派生Root地址: " + address + " 路径: " + derivationPath);
            } else {
                throw new IllegalStateException("HD钱包未初始化，无法派生地址");
            }
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "HD钱包派生地址失败: " + e.getMessage());
            throw new RuntimeException("生成Root地址失败: " + e.getMessage(), e);
        }
            
        BtcAddress btcAddress = new BtcAddress();
        btcAddress.setAddress(address);
        btcAddress.setAddressType(BtcAddress.AddressType.ROOT);
        btcAddress.setDerivationPath(derivationPath);
        btcAddress.setCoinType(coinType);
        btcAddress.setAccount((long) account);
        btcAddress.setChangeIndex(change);
        btcAddress.setAddressIndex(index);
        btcAddress.setBalance(0L);
        btcAddress.setUserId(0L);
        btcAddress.setActive(true);
            
        BtcAddress savedAddress = addressRepository.save(btcAddress);
        LogUtil.info(this.getClass(), "已生成新的Root地址: " + savedAddress.getAddress());
        
        return savedAddress;
    }
    
    /**
     * 获取热钱包地址（等同于Root地址）
     * @return 热钱包地址
     */
    public Optional<BtcAddress> getHotWalletAddress() {
        return getRootAddress();
    }

    /**
     * 派生新的用户充值地址
     * 路径: m/44'/coin_type'/userId'/0/index
     * 
     * @param userId 用户ID
     * @return 用户充值地址
     */
    public BtcAddress deriveUserAddress(Long userId) {
        return deriveUserAddress(userId, CHANGE_EXTERNAL);
    }
    
    /**
     * 派生新的用户地址
     * 路径: m/44'/coin_type'/userId'/change/index
     * 
     * @param userId 用户ID
     * @param change 业务ID (0=充值, 1=找零)
     * @return 用户地址
     */
    public BtcAddress deriveUserAddress(Long userId, int change) {
        int coinType = getCurrentCoinType();
        
        // 获取该用户在该业务链下的下一个地址索引
        int nextIndex = getNextAddressIndex(userId, change);
        
        String derivationPath = buildDerivationPath(coinType, userId.intValue(), change, nextIndex);
        
        String address;
        try {
            if (hdWalletManager.isInitialized()) {
                // 使用HD钱包派生地址
                address = hdWalletManager.deriveAddress(coinType, userId.intValue(), change, nextIndex);
                LogUtil.info(this.getClass(), "为用户 " + userId + " 派生地址: " + address + " 路径: " + derivationPath);
            } else {
                throw new IllegalStateException("HD钱包未初始化，无法派生地址");
            }
        } catch (Exception e) {
            LogUtil.error(this.getClass(), "HD钱包派生用户地址失败: " + e.getMessage());
            throw new RuntimeException("派生用户地址失败: " + e.getMessage(), e);
        }
            
        BtcAddress btcAddress = new BtcAddress();
        btcAddress.setAddress(address);
        btcAddress.setAddressType(BtcAddress.AddressType.USER);
        btcAddress.setDerivationPath(derivationPath);
        btcAddress.setCoinType(coinType);
        btcAddress.setAccount(userId);
        btcAddress.setChangeIndex(change);
        btcAddress.setAddressIndex(nextIndex);
        btcAddress.setUserId(userId);
        btcAddress.setBalance(0L);
        btcAddress.setActive(true);
            
        return addressRepository.save(btcAddress);
    }
    
    /**
     * 获取用户最新的充值地址（index最大的地址）
     * 
     * @param userId 用户ID
     * @return 最新的充值地址
     */
    public Optional<BtcAddress> getLatestUserAddress(Long userId) {
        return getLatestUserAddress(userId, CHANGE_EXTERNAL);
    }
    
    /**
     * 获取用户最新地址（index最大的地址）
     * 
     * @param userId 用户ID
     * @param change 业务ID
     * @return 最新的地址
     */
    public Optional<BtcAddress> getLatestUserAddress(Long userId, int change) {
        int coinType = getCurrentCoinType();
        return addressRepository.findLatestByCoinTypeAndAccountAndChangeIndex(coinType, userId, change);
    }
    
    /**
     * 获取用户所有地址列表
     * 
     * @param userId 用户ID
     * @return 地址列表
     */
    public List<BtcAddress> getUserAllAddresses(Long userId) {
        return addressRepository.findByUserIdOrderByAddressIndexDesc(userId);
    }
    
    /**
     * 获取用户指定业务类型的所有地址
     * 
     * @param userId 用户ID
     * @param change 业务ID
     * @return 地址列表
     */
    public List<BtcAddress> getUserAddresses(Long userId, int change) {
        int coinType = getCurrentCoinType();
        return addressRepository.findByCoinTypeAndAccountAndChangeIndexOrderByAddressIndexDesc(coinType, userId, change);
    }
    
    /**
     * 获取下一个地址索引
     * 查询该用户在该业务链下的最大index，+1返回
     * 
     * @param userId 用户ID
     * @param change 业务ID
     * @return 下一个地址索引
     */
    private synchronized int getNextAddressIndex(Long userId, int change) {
        int coinType = getCurrentCoinType();
        Optional<Integer> maxIndex = addressRepository.findMaxAddressIndexByCoinTypeAndAccountAndChangeIndex(coinType, userId, change);
        return maxIndex.orElse(-1) + 1;
    }
    
    /**
     * 构建BIP44派生路径字符串
     * 
     * @param coinType 币种类型
     * @param account 账户
     * @param change 业务ID
     * @param index 地址索引
     * @return 派生路径字符串
     */
    private String buildDerivationPath(int coinType, int account, int change, int index) {
        return String.format("m/44'/%d'/%d'/%d/%d", coinType, account, change, index);
    }
    
    /**
     * 获取当前网络的coin_type
     * 
     * @return coin_type值
     */
    private int getCurrentCoinType() {
        String network = hdWalletManager.getNetwork();
        if (network == null) {
            network = "testnet";
        }
        return network.equals("mainnet") ? DEFAULT_COIN_TYPE_MAINNET : DEFAULT_COIN_TYPE_TESTNET;
    }

    /**
     * 根据用户ID查找充值地址
     */
    public List<BtcAddress> findUserAddresses(Long userId) {
        return addressRepository.findByUserIdAndAddressType(userId, BtcAddress.AddressType.USER);
    }
    
    /**
     * 获取root地址
     */
    public Optional<BtcAddress> getRootAddress() {
        List<BtcAddress> rootAddresses = addressRepository.findByAddressType(BtcAddress.AddressType.ROOT);
        return rootAddresses.isEmpty() ? Optional.empty() : Optional.of(rootAddresses.get(0));
    }
}