//package com.btc.service;
//
//import com.btc.wallet.HDWalletManager;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.context.ActiveProfiles;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * 简化版ECKey签名功能测试
// */
//@SpringBootTest
//@ActiveProfiles("test")
//public class SimpleEcdsaSigningServiceTest {
//
//    @Autowired
//    private SimpleEcdsaSigningService signingService;
//
//    @Autowired
//    private HDWalletManager hdWalletManager;
//
//    @Test
//    public void testEcdsaSigningAndVerification() {
//        System.out.println("=== 简化版ECKey签名功能测试 ===");
//
//        try {
//            // 1. 初始化钱包
//            hdWalletManager.generateNewWallet();
//            System.out.println("✅ 钱包初始化完成");
//
//            // 2. 测试数据
//            String testData = "Hello Bitcoin World!";
//            String derivationPath = "m/44'/0'/0'/0/0";
//
//            System.out.println("📝 测试数据: " + testData);
//            System.out.println("📍 派生路径: " + derivationPath);
//
//            // 3. 执行签名
//            String signature = signingService.signData(testData, derivationPath);
//            System.out.println("🔐 签名完成，签名长度: " + signature.length());
//            System.out.println("📄 签名内容: " + signature);
//
//            // 4. 获取公钥
//            String publicKey = signingService.getPublicKey(derivationPath);
//            System.out.println("🔑 公钥长度: " + publicKey.length());
//            System.out.println("📄 公钥内容: " + publicKey.substring(0, 32) + "...");
//
//            // 5. 验证签名
//            boolean isValid = signingService.verifySignature(testData, signature, publicKey);
//            System.out.println("✅ 签名验证结果: " + (isValid ? "有效" : "无效"));
//
//            // 6. 验证错误签名
//            String wrongData = "Wrong data";
//            boolean isInvalid = signingService.verifySignature(wrongData, signature, publicKey);
//            System.out.println("✅ 错误数据验证结果: " + (isInvalid ? "有效(错误)" : "无效(正确)"));
//
//            // 7. 断言验证
//            assertNotNull(signature, "签名不应为null");
//            assertNotNull(publicKey, "公钥不应为null");
//            assertTrue(isValid, "签名应该验证通过");
//            assertFalse(isInvalid, "错误数据的签名应该验证失败");
//
//            System.out.println("✅ 简化版ECKey签名功能测试通过");
//
//        } catch (Exception e) {
//            System.err.println("❌ 测试失败: " + e.getMessage());
//            e.printStackTrace();
//            fail("ECKey签名功能测试失败: " + e.getMessage());
//        }
//    }
//
//    @Test
//    public void testMultipleSignatures() {
//        System.out.println("\n=== 多次签名测试 ===");
//
//        try {
//            String testData = "Multiple signature test data";
//
//            // 测试不同路径的签名
//            String[] paths = {
//                "m/44'/0'/0'/0/0",
//                "m/44'/0'/0'/0/1",
//                "m/44'/0'/0'/0/2"
//            };
//
//            for (String path : paths) {
//                String signature = signingService.signData(testData, path);
//                String publicKey = signingService.getPublicKey(path);
//                boolean isValid = signingService.verifySignature(testData, signature, publicKey);
//
//                System.out.println("路径 " + path + ": 签名" + (isValid ? "✓" : "✗"));
//                assertTrue(isValid, "路径 " + path + " 的签名应该有效");
//            }
//
//            System.out.println("✅ 多次签名测试通过");
//
//        } catch (Exception e) {
//            fail("多次签名测试失败: " + e.getMessage());
//        }
//    }
//}