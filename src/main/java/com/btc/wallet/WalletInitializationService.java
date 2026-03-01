package com.btc.wallet;

import com.btc.service.AddressManagementService;
import com.btc.service.NetworkSwitchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 钱包初始化服务
 * 应用启动时自动初始化或加载钱包
 */
@Service
public class WalletInitializationService {
    
    @Autowired
    private HDWalletManager hdWalletManager;
    
    @Autowired
    private NetworkSwitchService networkSwitchService;
    
    @Autowired
    private AddressManagementService addressManagementService;
    
    /**
     * 应用启动完成后自动初始化钱包
     * 初始化顺序：先初始化HD钱包，再生成Root地址
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeWalletOnStartup() {
        System.out.println("🚀 开始初始化比特币钱包...");
        
        // 初始化网络切换服务
        networkSwitchService.initialize();
        
        try {
            // 1. 从配置文件加载钱包种子（钱包必须在生成地址之前初始化）
            if (hdWalletManager.loadWalletFromStorage()) {
                System.out.println("✅ 钱包从配置文件加载成功");
            } else {
                // 配置文件中没有种子，生成新的钱包供用户配置
                System.out.println("⚠️ 配置文件中未找到钱包种子，正在生成新钱包...");
                hdWalletManager.generateNewWallet();
                System.out.println("⚠️ 请将上述种子配置到 application.yml 的 bitcoin.wallet.seed 中后重启应用");
                throw new RuntimeException("请在配置文件中设置钱包种子后重启应用");
            }
            
            // 2. 钱包初始化完成后，检查并生成Root地址
            if (!addressManagementService.isRootAddressConfigured()) {
                System.out.println("⚠️ Root地址未配置，正在自动生成...");
                addressManagementService.generateRootAddress();
                System.out.println("✅ Root地址已自动生成");
            } else {
                System.out.println("✅ Root地址配置检查通过:"+hdWalletManager.deriveRootAddress(1));
            }
            
            System.out.println("🎉 钱包初始化完成!");
            
        } catch (Exception e) {
            System.err.println("❌ 钱包初始化失败: " + e.getMessage());
            throw new RuntimeException("钱包初始化失败", e);
        }
    }
}