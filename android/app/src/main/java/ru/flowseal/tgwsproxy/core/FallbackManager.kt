package ru.flowseal.tgwsproxy.core

import java.net.URLEncoder

object FallbackManager {

    fun getCandidateUrls(
        dcId: Int,
        isMedia: Boolean,
        customWorkerDomains: List<String> = emptyList()
    ): List<String> {
        val urls = mutableListOf<String>()
        val dc = if (dcId == 203) 2 else dcId

        // 1. Custom user-defined Cloudflare Workers (if configured)
        val fallbackDst = Constants.DC_DEFAULT_IPS[dc]
        if (fallbackDst != null) {
            for (worker in customWorkerDomains) {
                val cleanWorker = worker.trim().removePrefix("https://").removePrefix("wss://").trimEnd('/')
                if (cleanWorker.isNotEmpty()) {
                    val encodedDst = URLEncoder.encode(fallbackDst, "UTF-8")
                    urls.add("wss://$cleanWorker/apiws?dst=$encodedDst&dc=$dc")
                }
            }
        }

        // 2. Default Cloudflare Proxy CDN domains (routes through Cloudflare Anycast CDN, bypassing ISP IP blocks)
        val cfDomains = DomainBalancer.getCandidateDomains(dc)
        for (baseDomain in cfDomains.take(5)) {
            urls.add("wss://kws$dc.$baseDomain/apiws")
        }

        // 3. Official Telegram WebSocket endpoints
        val domains = Constants.wsDomains(dcId, isMedia)
        for (domain in domains) {
            urls.add("wss://$domain/apiws")
        }

        return urls
    }
}
