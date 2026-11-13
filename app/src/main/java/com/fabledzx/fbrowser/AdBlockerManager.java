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

    // 多个加速代理列表（去掉无法作为网页访问的 .com 域名）
    private static final String[] PROXIES = {
            "https://gh-proxy.org/",
            "https://ghproxy.net/",
            "https://gh.llkk.cc/"
    };

    // 原始多源拦截列表
    private static final String[][] SOURCES = {
            {"https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/anti-ad-domains.txt", "anti-AD"},
            {"https://raw.githubusercontent.com/Cats-Team/AdRules/main/adblock.txt", "Cats-Team"},
            {"https://github.com/217heidai/adblockfilters/releases/download/rule/adblockdnslite.txt", "217heidai"},
            {"https://raw.githubusercontent.com/8680/GOODBYEADS/master/data/rules/ad-domain.txt", "GOODBYEADS"},
            {"https://raw.githubusercontent.com/francis-zhao/quarklist/master/dist/quarklist.txt", "QuarkList"},
            {"https://easylist-downloads.adblockplus.org/easylistchina.txt", "EasyListChina"}
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
            final String originalUrl = source[0];
            final String name = source[1];
            
            new Thread(() -> {
                try {
                    Set<String> domains = fetchWithFallback(originalUrl);
                    if (!domains.isEmpty()) {
                        synchronized (merged) {
                            merged.addAll(domains);
                        }
                        successCount.incrementAndGet();
                        Log.i(TAG, name + ": " + domains.size() + " domains");
                    } else {
                        Log.w(TAG, name + ": 0 domains (可能格式不匹配或全部代理失效)");
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

    /** 
     * 尝试使用各个代理前缀去拉取数据，全部失败则尝试直连 
     */
    private Set<String> fetchWithFallback(String originalUrl) {
        for (String proxy : PROXIES) {
            try {
                Set<String> result = fetchAndParse(proxy + originalUrl);
                if (!result.isEmpty()) {
                    return result;
                }
            } catch (Exception ignored) {}
        }
        // 直连兜底
        try {
            return fetchAndParse(originalUrl);
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private Set<String> fetchAndParse(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) FBrowser");

            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("HTTP " + conn.getResponseCode());
            }

            Set<String> domains = new HashSet<>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            
            // 探测首行，防止代理返回 HTML 错误页面
            boolean isFirstLine = true;
            while ((line = reader.readLine()) != null) {
                if (isFirstLine) {
                    String trimLine = line.trim();
                    if (trimLine.startsWith("<!DOCTYPE") || trimLine.startsWith("<html")) {
                        throw new RuntimeException("代理返回了 HTML 错误页");
                    }
                    isFirstLine = false;
                }
                String d = extractDomain(line);
                if (d != null) domains.add(d);
            }
            reader.close();
            return domains;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String extractDomain(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) return null;

        s = s.replaceAll("^https?://", "");
        s = s.replaceAll("^\\|\\|", "");
        s = s.replaceAll("\\^.*$", "");
        s = s.replaceAll("/.*$", "");
        s = s.replaceAll("[:#?].*$", "");
        s = s.toLowerCase();

        if (s.contains("$") || s.contains("*")) return null;
        if (!s.contains(".") || s.length() < 4) return null;
        if (!s.matches("[a-z0-9.\\-]+")) return null;

        // 【核心白名单】防止一些核心搜索/功能域名被误杀
        if (s.equals("bing.com") || s.endsWith(".bing.com") ||
            s.equals("microsoft.com") || s.endsWith(".microsoft.com") ||
            s.equals("github.com") || s.endsWith(".github.com") || 
            s.equals("gh-proxy.org") || s.equals("ghproxy.net")) {
            return null;
        }

        return s;
    }

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

        // 双重保险：主搜索引擎和代理服务绝对不放行
        if (host.endsWith("bing.com") || host.endsWith("github.com")) {
            return false;
        }

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