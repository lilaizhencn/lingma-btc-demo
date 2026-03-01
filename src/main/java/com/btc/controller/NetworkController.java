package com.btc.controller;

import com.btc.service.NetworkSwitchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 网络管理控制器
 * 提供主网/测试网切换的REST API接口
 */
@RestController
@RequestMapping("/api/network")
public class NetworkController {

    @Autowired
    private NetworkSwitchService networkSwitchService;

    /**
     * 获取当前网络状态
     */
    @GetMapping("/status")
    public ResponseEntity<NetworkSwitchService.NetworkSwitchStatus> getNetworkStatus() {
        return ResponseEntity.ok(networkSwitchService.getStatus());
    }

    /**
     * 切换到主网
     */
    @PostMapping("/switch/mainnet")
    public ResponseEntity<String> switchToMainnet() {
        try {
            networkSwitchService.switchNetwork("mainnet");
            return ResponseEntity.ok("成功切换到主网");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("切换失败: " + e.getMessage());
        }
    }

    /**
     * 切换到测试网
     */
    @PostMapping("/switch/testnet")
    public ResponseEntity<String> switchToTestnet() {
        try {
            networkSwitchService.switchNetwork("testnet");
            return ResponseEntity.ok("成功切换到测试网");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("切换失败: " + e.getMessage());
        }
    }

    /**
     * 切换到指定网络
     */
    @PostMapping("/switch/{network}")
    public ResponseEntity<String> switchNetwork(@PathVariable String network) {
        try {
            networkSwitchService.switchNetwork(network);
            return ResponseEntity.ok("成功切换到 " + network);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("切换失败: " + e.getMessage());
        }
    }

    /**
     * 检查是否为主网
     */
    @GetMapping("/is-mainnet")
    public ResponseEntity<Boolean> isMainnet() {
        return ResponseEntity.ok(networkSwitchService.isMainnet());
    }

    /**
     * 检查是否为测试网
     */
    @GetMapping("/is-testnet")
    public ResponseEntity<Boolean> isTestnet() {
        return ResponseEntity.ok(networkSwitchService.isTestnet());
    }
}