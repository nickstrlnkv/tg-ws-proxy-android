package ru.flowseal.tgwsproxy.core

import java.util.concurrent.ConcurrentHashMap

object DomainBalancer {

    val DEFAULT_CF_DOMAINS = listOf(
        "pclead.co.uk",
        "offshor.co.uk",
        "cakeisalie.co.uk",
        "noskomnadzor.co.uk",
        "lovetrue.co.uk",
        "sorokdva.co.uk",
        "pyatdesyatdva.co.uk",
        "kartoshka.co.uk",
        "sorokodin.co.uk",
        "pyatdesyatodin.co.uk",
        "notelega.co.uk",
        "ebally.co.uk",
        "nebally.co.uk",
        "havegreatday.co.uk",
        "pomogite.co.uk",
        "fixtelega.co.uk",
        "sadnews.co.uk",
        "onedaychamp.co.uk",
        "stopblocking.co.uk",
        "nothingthere.co.uk"
    )

    private val dcToDomain = ConcurrentHashMap<Int, String>()

    fun getCandidateDomains(dcId: Int): List<String> {
        val dc = if (dcId == 203) 2 else dcId
        val candidates = mutableListOf<String>()

        val active = dcToDomain[dc]
        if (active != null) {
            candidates.add(active)
        }

        val shuffled = DEFAULT_CF_DOMAINS.shuffled()
        for (d in shuffled) {
            if (d != active) {
                candidates.add(d)
            }
        }
        return candidates
    }

    fun markDomainSuccess(dcId: Int, url: String) {
        val dc = if (dcId == 203) 2 else dcId
        for (domain in DEFAULT_CF_DOMAINS) {
            if (url.contains(domain)) {
                dcToDomain[dc] = domain
                break
            }
        }
    }
}
