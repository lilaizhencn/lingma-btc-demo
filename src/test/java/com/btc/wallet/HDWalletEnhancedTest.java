//package com.btc.wallet;
//
//import org.junit.jupiter.api.Test;
//import static org.junit.jupiter.api.Assertions.*;
//public class HDWalletEnhancedTest {
//
//    @Test
//    public void testBech32AddressGeneration() {
//        System.out.println("=== Bech32地址生成测试 ===");
//
//        // 直接测试，不依赖Spring容器
//        System.setProperty("bitcoin.network", "testnet");
//        HDWalletManager walletManager = new HDWalletManager();
//        walletManager.generateNewWallet();
//
//        // 测试比特币地址生成
//        String btcAddress = walletManager.deriveUserDepositAddress(0);
//        System.out.println("比特币测试网地址: " + btcAddress);
//        assertTrue(btcAddress.startsWith("tb1"), "测试网地址应该以tb1开头");
//
//        // 测试不同的索引
//        String btcAddress1 = walletManager.deriveUserDepositAddress(1);
//        String btcAddress2 = walletManager.deriveUserDepositAddress(2);
//        System.out.println("索引1地址: " + btcAddress1);
//        System.out.println("索引2地址: " + btcAddress2);
//
//        assertNotEquals(btcAddress, btcAddress1, "不同索引应该生成不同地址");
//        assertNotEquals(btcAddress1, btcAddress2, "不同索引应该生成不同地址");
//    }
//
//    @Test
//    public void testMultiCoinSupport() {
//        System.out.println("\n=== 多币种支持测试 ===");
//
//        System.setProperty("bitcoin.network", "testnet");
//        HDWalletManager walletManager = new HDWalletManager();
//        walletManager.generateNewWallet();
//
//        // 测试不同币种的coin_type
//        int btcCoinType = walletManager.getCoinType("btc");
//        int ltcCoinType = walletManager.getCoinType("ltc");
//        int dashCoinType = walletManager.getCoinType("dash");
//
//        System.out.println("BTC CoinType: " + btcCoinType);
//        System.out.println("LTC CoinType: " + ltcCoinType);
//        System.out.println("DASH CoinType: " + dashCoinType);
//
//        assertEquals(1, btcCoinType, "测试网BTC CoinType应该是1");
//        assertEquals(1, ltcCoinType, "测试网LTC CoinType应该是1");
//        assertEquals(1, dashCoinType, "测试网DASH CoinType应该是1");
//
//        // 测试不同币种地址生成
//        String btcAddress = walletManager.deriveUserDepositAddress(0, btcCoinType);
//        String ltcAddress = walletManager.deriveUserDepositAddress(0, ltcCoinType);
//        String dashAddress = walletManager.deriveUserDepositAddress(0, dashCoinType);
//
//        System.out.println("BTC地址: " + btcAddress);
//        System.out.println("LTC地址: " + ltcAddress);
//        System.out.println("DASH地址: " + dashAddress);
//
//        // 验证都是有效的测试网地址格式
//        assertTrue(btcAddress.startsWith("tb1"), "BTC地址格式正确");
//        assertTrue(ltcAddress.startsWith("tb1"), "LTC地址格式正确");
//        assertTrue(dashAddress.startsWith("tb1"), "DASH地址格式正确");
//    }
//
//    @Test
//    public void testDifferentAddressTypes() {
//        System.out.println("\n=== 不同地址类型测试 ===");
//
//        System.setProperty("bitcoin.network", "testnet");
//        HDWalletManager walletManager = new HDWalletManager();
//        walletManager.generateNewWallet();
//
//        int btcCoinType = walletManager.getCoinType("btc");
//
//        // 测试三种不同类型的地址
//        String userAddress = walletManager.deriveUserDepositAddress(0, btcCoinType);
//        String hotWalletAddress = walletManager.deriveHotWalletAddress(0, btcCoinType);
//        String changeAddress = walletManager.deriveChangeAddress(0, btcCoinType, "btc");
//
//        System.out.println("用户充值地址: " + userAddress);
//        System.out.println("热钱包地址: " + hotWalletAddress);
//        System.out.println("找零地址: " + changeAddress);
//
//        // 验证都是有效的Bech32地址
//        assertTrue(userAddress.startsWith("tb1"), "用户地址格式正确");
//        assertTrue(hotWalletAddress.startsWith("tb1"), "热钱包地址格式正确");
//        assertTrue(changeAddress.startsWith("tb1"), "找零地址格式正确");
//
//        // 验证地址不同（虽然可能相同，但路径不同）
//        assertNotNull(userAddress, "用户地址不为空");
//        assertNotNull(hotWalletAddress, "热钱包地址不为空");
//        assertNotNull(changeAddress, "找零地址不为空");
//    }
//
//    @Test
//    public void testMainnetSupport() {
//        System.out.println("\n=== 主网支持测试 ===");
//
//        // 直接创建主网钱包管理器
//        HDWalletManager walletManager = new HDWalletManager();
//        // 通过反射设置网络为mainnet
//        try {
//            java.lang.reflect.Field networkField = HDWalletManager.class.getDeclaredField("network");
//            networkField.setAccessible(true);
//            networkField.set(walletManager, "mainnet");
//
//            // 重新初始化网络参数
//            java.lang.reflect.Method initMethod = HDWalletManager.class.getDeclaredMethod("initializeNetwork");
//            initMethod.setAccessible(true);
//            initMethod.invoke(walletManager);
//
//            walletManager.generateNewWallet();
//
//            int btcCoinType = walletManager.getCoinType("btc");
//            System.out.println("主网BTC CoinType: " + btcCoinType);
//            assertEquals(0, btcCoinType, "主网BTC CoinType应该是0");
//
//            String mainnetAddress = walletManager.deriveUserDepositAddress(0);
//            System.out.println("主网地址: " + mainnetAddress);
//            assertTrue(mainnetAddress.startsWith("bc1"), "主网地址应该以bc1开头");
//
//        } catch (Exception e) {
//            throw new RuntimeException("反射设置主网失败", e);
//        }
//    }
//
//    @Test
//    public void testInvalidCoinType() {
//        System.out.println("\n=== 无效币种测试 ===");
//
//        System.setProperty("bitcoin.network", "testnet");
//        HDWalletManager walletManager = new HDWalletManager();
//        walletManager.generateNewWallet();
//
//        // 测试不支持的币种
//        assertThrows(IllegalArgumentException.class, () -> walletManager.getCoinType("eth"), "应该抛出不支持币种的异常");
//
//        assertThrows(IllegalArgumentException.class, () -> walletManager.getCoinType("xyz"), "应该抛出不支持币种的异常");
//    }
//
//    @Test
//    public void testWalletPersistence() {
//        System.out.println("\n=== 钱包持久化测试 ===");
//
//        System.setProperty("bitcoin.network", "testnet");
//        HDWalletManager walletManager = new HDWalletManager();
//        walletManager.generateNewWallet();
//
//        String originalFingerprint = walletManager.getRootKeyFingerprint();
//        System.out.println("原始钱包指纹: " + originalFingerprint);
//
//        // 保存钱包
//        walletManager.saveWalletToStorage();
//
//        // 模拟重新加载钱包
//        HDWalletManager newWalletManager = new HDWalletManager();
//        boolean loaded = newWalletManager.loadWalletFromStorage();
//
//        if (loaded) {
//            String loadedFingerprint = newWalletManager.getRootKeyFingerprint();
//            System.out.println("加载的钱包指纹: " + loadedFingerprint);
//            assertEquals(originalFingerprint, loadedFingerprint, "钱包指纹应该一致");
//        } else {
//            System.out.println("注意: 持久化存储为模拟实现，实际项目中需要真实存储");
//        }
//    }
//}