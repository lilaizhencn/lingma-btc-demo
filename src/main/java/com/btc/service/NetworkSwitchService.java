package com.btc.service;

import com.btc.util.LogUtil;
import com.btc.wallet.HDWalletManager;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;



/**
 * 网络切换服务
 * 支持主网/测试网一键切换功能
 */
@Service
public class NetworkSwitchService {

    @Value("${bitcoin.network-switch.enabled:true}")
    private boolean networkSwitchEnabled;

    @Value("${bitcoin.network-switch.default-network:testnet}")
    private String defaultNetwork;

    /**
     * -- GETTER --
     *  获取当前网络
     */
    @Getter
    @Value("${bitcoin.network:testnet}")
    private String currentNetwork;

    private final HDWalletManager hdWalletManager;

    public NetworkSwitchService(HDWalletManager hdWalletManager) {
        this.hdWalletManager = hdWalletManager;
    }


    public void initialize() {
        if (networkSwitchEnabled) {
            LogUtil.info(this.getClass(), "网络切换功能已启用，默认网络: " + defaultNetwork);
            // 确保当前网络与默认网络一致
            if (!currentNetwork.equals(defaultNetwork)) {
                switchNetwork(defaultNetwork);
            }
        } else {
            LogUtil.info(this.getClass(), "网络切换功能已禁用，使用固定网络: " + currentNetwork);
        }
    }

    /**
     * 切换网络
     * @param targetNetwork 目标网络 (mainnet/testnet)
     */
    public void switchNetwork(String targetNetwork) {
        if (!networkSwitchEnabled) {
            throw new IllegalStateException("网络切换功能已被禁用");
        }

        if (!isValidNetwork(targetNetwork)) {
            throw new IllegalArgumentException("不支持的网络类型: " + targetNetwork);
        }

        if (currentNetwork.equals(targetNetwork)) {
            LogUtil.info(this.getClass(), "网络已经是 " + targetNetwork + "，无需切换");
            return;
        }

        try {
            LogUtil.info(this.getClass(), "开始切换网络: " + currentNetwork + " -> " + targetNetwork);

            // 1. 更新HD钱包管理器的网络配置
            updateWalletNetwork(targetNetwork);

            // 2. 更新当前网络状态
            currentNetwork = targetNetwork;

            // 3. 重新初始化相关服务
            reinitializeServices();

            LogUtil.info(this.getClass(), "网络切换成功: " + targetNetwork);

        } catch (Exception e) {
            LogUtil.error(this.getClass(), "网络切换失败: " + e.getMessage(), e);
            throw new RuntimeException("网络切换失败", e);
        }
    }

    /**
     * 检查是否为主网
     */
    public boolean isMainnet() {
        return "mainnet".equals(currentNetwork);
    }

    /**
     * 检查是否为测试网
     */
    public boolean isTestnet() {
        return "testnet".equals(currentNetwork);
    }

    /**
     * 验证网络类型是否有效
     */
    private boolean isValidNetwork(String network) {
        return "mainnet".equals(network) || "testnet".equals(network);
    }

    /**
     * 更新钱包网络配置
     */
    private void updateWalletNetwork(String targetNetwork) {
        try {
            // 通过反射更新HDWalletManager的网络配置
            java.lang.reflect.Field networkField = HDWalletManager.class.getDeclaredField("network");
            networkField.setAccessible(true);
            networkField.set(hdWalletManager, targetNetwork);

            // 重新初始化网络参数
            java.lang.reflect.Method initMethod = HDWalletManager.class.getDeclaredMethod("initializeNetwork");
            initMethod.setAccessible(true);
            initMethod.invoke(hdWalletManager);

            LogUtil.info(this.getClass(), "钱包网络配置更新完成: " + targetNetwork);

        } catch (Exception e) {
            LogUtil.error(this.getClass(), "更新钱包网络配置失败: " + e.getMessage(), e);
            throw new RuntimeException("更新钱包网络配置失败", e);
        }
    }

    /**
     * 重新初始化相关服务
     */
    private void reinitializeServices() {
        // 这里可以添加其他需要重新初始化的服务
        LogUtil.info(this.getClass(), "相关服务重新初始化完成");
    }

    /**
     * 获取网络切换状态
     */
    public NetworkSwitchStatus getStatus() {
        return new NetworkSwitchStatus(
            networkSwitchEnabled,
            currentNetwork,
            defaultNetwork,
            isValidNetwork(currentNetwork)
        );
    }

    /**
     * 网络切换状态信息
     *
     * @param enabled Getters
     */
        public record NetworkSwitchStatus(boolean enabled, String currentNetwork, String defaultNetwork, boolean valid) {

        @Override
            public String toString() {
                return "NetworkSwitchStatus{" +
                        "enabled=" + enabled +
                        ", currentNetwork='" + currentNetwork + '\'' +
                        ", defaultNetwork='" + defaultNetwork + '\'' +
                        ", valid=" + valid +
                        '}';
            }
        }
}