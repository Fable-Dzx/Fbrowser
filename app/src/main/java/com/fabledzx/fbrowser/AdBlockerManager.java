package com.fabledzx.fbrowser;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class AdBlockerManager {
    private static final String TAG = "AdBlocker";

    // 多源列表：raw 地址 + 说明
    private static final String[][] SOURCES = {
            {"https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-domains.txt", "anti-AD"},
            {"https://raw.githubusercontent.com/Cats-Team/AdRules/main/adblock.txt", "Cats-Team AdRules"},
            {"https://github.com/217heidai/adblockfilters/releases/download/rule/adblockdnslite.txt", "217heidai Lite"},
            {"https://raw.githubusercontent.com/8680/GOODBYEADS/master/data/rules/ad-domain.txt", "GOODBYEADS"},
            {"https://raw.githubusercontent.com/francis-zhao/quarklist/master/dist/quarklist.txt", "QuarkList"},
            {"https://easylist-downloads.adblockplus.org/easylistchina.txt", "EasyList China"}
    };

    private final Set<String> blockedDomains = new HashSet<>();
    private volatile boolean isReady = false;

    public void init() {
        new Thread(this::loadAllSources).start();
    }

    private void loadAllSources() {
        int total = SOURCES.length;
        CountDownLatch latch = new CountDownLatch(total);
        AtomicInteger successCount = new AtomicInteger(0);
        Set<String> merged = new HashSet<>();

        for (String[] source : SOURCES) {
            final String url = source[0];
            final String name = source[1];
            new Thread(() -> {
                try {
                    Set<String> domains = fetchAndParse(url);
                    if (!domains.isEmpty()) {
                        synchronized (merged) {
                            merged.addAll(domains);
                        }
                        successCount.incrementAndGet();
                        Log.i(TAG, name + ": " + domains.size() + " domains");
                    } else {
                        Log.w(TAG, name + ": 0 domains (可能格式不匹配)");
                    }
                } catch (Exception e) {
                    Log.w(TAG, name + " 加载失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (blockedDomains) {
            blockedDomains.clear();
            blockedDomains.addAll(merged);
        }
        isReady = !blockedDomains.isEmpty();
        Log.i(TAG, "总计加载 " + blockedDomains.size() + " 个域名，成功源: "
                + successCount.get() + "/" + total);
    }

    private Set<String> fetchAndParse(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            // 部分 releases 重定向需要浏览器 UA
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) FBrowser");

            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("HTTP " + conn.getResponseCode());
            }

            Set<String> domains = new HashSet<>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String d = extractDomain(line);
                if (d != null) domains.add(d);
            }
            reader.close();
            return domains;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从一行文本中提取可拦截的主域名，自动兼容纯域名和 ABP 语法 */
    private String extractDomain(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) return null;

        // 纯域名行或带协议的 URL
        s = s.replaceAll("^https?://", "");
        s = s.replaceAll("^\\|\\|", "");   // ABP 前缀 ||
        s = s.replaceAll("\\^.*$", "");    // ABP 后缀 ^ 及之后
        s = s.replaceAll("/.*$", "");      // 去掉路径
        s = s.replaceAll("[:#?].*$", "");  // 去掉端口等
        s = s.toLowerCase();

        // 过滤明显不是域名的内容（如 ABP 选项行 $important）
        if (s.contains("$") || s.contains("*")) return null;
        if (!s.contains(".") || s.length() < 4) return null;

        // 只保留字母、数字、点、连字符
        if (!s.matches("[a-z0-9.\\-]+")) return null;
        return s;
    }

    /** 域名精确匹配或后缀匹配（子域名一并拦截） */
    public boolean shouldBlock(String url) {
        if (!isReady || url == null) return false;
        String host;
        try {
            host = new URL(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase();

        synchronized (blockedDomains) {
            if (blockedDomains.contains(host)) return true;
            int dot = host.indexOf('.');
            while (dot != -1 && dot < host.length() - 1) {
                if (blockedDomains.contains(host.substring(dot + 1))) return true;
                dot = host.indexOf('.', dot + 1);
            }
        }
        return false;
    }

    public boolean isReady() {
        return isReady;
    }
}