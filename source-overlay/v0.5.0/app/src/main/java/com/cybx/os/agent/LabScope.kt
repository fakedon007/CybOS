package com.cybx.os.agent

data class SafeScanPlan(
    val request: String,
    val targetCidr: String,
    val hosts: List<String>,
    val ports: List<Int> = DEFAULT_PORTS,
    val language: String = "en"
) {
    companion object {
        val DEFAULT_PORTS = listOf(22, 80, 443, 445, 8080, 8443)
    }
}

private data class Ipv4Cidr(val start: Long, val end: Long, val prefix: Int, val text: String)

object LabScope {
    private val ipPattern = Regex("""(?<![0-9.])((?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?:/[0-9]{1,2})?)(?![0-9.])""")
    private val allowedWords = listOf(
        "scan", "port", "network", "check", "discover", "host",
        "স্ক্যান", "নেটওয়ার্ক", "নেটওয়ার্ক", "চেক", "পোর্ট", "খুঁজে"
    )

    fun draft(request: String, approvedScopeText: String): SafeScanPlan {
        require(request.isNotBlank()) { "বাংলা বা English-এ scan task লিখুন।" }
        require(allowedWords.any { request.lowercase().contains(it.lowercase()) }) {
            "এখন শুধু authorised private-lab TCP scan সমর্থিত। উদাহরণ: “scan 192.168.1.10” / “192.168.1.10 পোর্ট স্ক্যান করো”"
        }
        val match = ipPattern.find(request) ?: error("Request-এ IPv4 target/CIDR পাওয়া যায়নি। উদাহরণ: 192.168.1.10")
        val targetText = match.groupValues[1]
        val target = parseCidr(targetText)
        require(isRfc1918(target.start) && isRfc1918(target.end)) {
            "Public বা non-private target block করা হয়েছে। শুধু RFC1918 private lab address ব্যবহার করুন।"
        }
        require(target.prefix >= 28) {
            "একটি run-এ সর্বোচ্চ ১৬টি host scan করা যায়। Target range /28 বা তার চেয়ে ছোট রাখুন, অথবা একটি IP দিন।"
        }
        val scopes = parseApprovedScopes(approvedScopeText)
        require(scopes.isNotEmpty()) { "Settings-এ আগে আপনার authorised private lab CIDR যোগ করুন।" }
        require(scopes.any { target.start >= it.start && target.end <= it.end }) {
            "Target আপনার approved lab scope-এর মধ্যে নেই। Settings-এ scope অনুমোদন করুন।"
        }
        val hosts = if (target.prefix == 32) {
            listOf(toIp(target.start))
        } else if (target.prefix == 31) {
            listOf(toIp(target.start), toIp(target.end))
        } else {
            (target.start + 1 until target.end).map(::toIp)
        }
        require(hosts.isNotEmpty() && hosts.size <= 16) { "Host limit exceeded." }
        val language = if (request.any { it.code > 127 }) "bn" else "en"
        return SafeScanPlan(request, targetText, hosts, language = language)
    }

    fun validateScopeText(text: String): String {
        val scopes = parseApprovedScopes(text)
        require(scopes.isNotEmpty()) { "কমপক্ষে একটি private CIDR দিন, যেমন 192.168.1.0/24" }
        return scopes.joinToString(", ") { it.text }
    }

    private fun parseApprovedScopes(text: String): List<Ipv4Cidr> =
        text.split(',', ';', '\n', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseCidr(it) }
            .onEach {
                require(isRfc1918(it.start) && isRfc1918(it.end)) {
                    "Approved scope-ও RFC1918 private address হতে হবে; public CIDR গ্রহণযোগ্য নয়।"
                }
            }

    private fun parseCidr(raw: String): Ipv4Cidr {
        val parts = raw.split('/')
        require(parts.size in 1..2) { "Invalid CIDR: $raw" }
        val ip = parseIpv4(parts[0])
        val prefix = if (parts.size == 2) parts[1].toIntOrNull() ?: -1 else 32
        require(prefix in 0..32) { "Invalid CIDR prefix: $raw" }
        val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val start = ip and mask
        val size = 1L shl (32 - prefix)
        val end = start + size - 1
        return Ipv4Cidr(start, end, prefix, "$${toIp(start)}/$prefix")
    }

    private fun parseIpv4(value: String): Long {
        val octets = value.split('.')
        require(octets.size == 4) { "Invalid IPv4 address: $value" }
        return octets.fold(0L) { acc, part ->
            val octet = part.toIntOrNull()
            require(octet != null && octet in 0..255) { "Invalid IPv4 address: $value" }
            (acc shl 8) or octet.toLong()
        }
    }

    private fun isRfc1918(ip: Long): Boolean {
        val a = (ip ushr 24).toInt()
        val b = ((ip ushr 16) and 255).toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun toIp(ip: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((ip ushr it) and 255).toString() }
}
