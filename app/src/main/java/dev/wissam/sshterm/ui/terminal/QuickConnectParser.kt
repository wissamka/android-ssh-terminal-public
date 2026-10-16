package dev.wissam.sshterm.ui.terminal

data class QuickConnectTarget(val username: String, val host: String, val port: Int)

/** IPv6 literals may be bare at port 22; an explicit IPv6 port requires brackets. No DNS lookup. */
fun parseQuickConnectTarget(input: String): QuickConnectTarget? {
    val value = input.trim()
    if (value.any { it.isWhitespace() || it.isISOControl() }) return null
    val at = value.indexOf('@')
    if (at <= 0 || at != value.lastIndexOf('@')) return null
    val user = value.substring(0, at)
    val address = value.substring(at + 1)
    val host: String
    val portText: String?
    if (address.startsWith("[")) {
        val end = address.indexOf(']')
        if (end < 0) return null
        host = address.substring(1, end)
        if (!host.contains(':')) return null
        val suffix = address.substring(end + 1)
        if (suffix.isNotEmpty() && !suffix.startsWith(":")) return null
        portText = if (suffix.isEmpty()) null else suffix.substring(1)
    } else {
        when (address.count { it == ':' }) {
            0 -> { host = address; portText = null }
            1 -> { host = address.substringBefore(':'); portText = address.substringAfter(':') }
            else -> { host = address; portText = null }
        }
    }
    if (host.isBlank() || host.any { it in "[]/\\" }) return null
    if (host.contains(':') && !isIpv6Literal(host)) return null
    val port = if (portText == null) 22 else {
        if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
        portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    }
    return QuickConnectTarget(user, host, port)
}

/** Validate literal syntax without resolving a host or consulting network interfaces. */
private fun isIpv6Literal(host: String): Boolean {
    val address = host.substringBefore('%')
    if ('%' in host) {
        val zone = host.substringAfter('%')
        if (zone.isEmpty() || zone.any { !it.isLetterOrDigit() && it !in "_.-" }) return false
    }
    val compressed = "::" in address
    if (compressed && address.indexOf("::") != address.lastIndexOf("::")) return false
    val groups = address.split(':').filter { it.isNotEmpty() }
    if ((address.startsWith(':') && !address.startsWith("::")) ||
        (address.endsWith(':') && !address.endsWith("::"))) return false
    var units = 0
    for ((index, group) in groups.withIndex()) {
        if ('.' in group) {
            if (index != groups.lastIndex || !address.endsWith(group)) return false
            val octets = group.split('.')
            if (octets.size != 4 || octets.any {
                it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                    (it.toIntOrNull() ?: -1) !in 0..255
            }) return false
            units += 2
        } else {
            if (group.length !in 1..4 || group.any { it !in "0123456789abcdefABCDEF" }) return false
            units++
        }
    }
    return if (compressed) units < 8 else units == 8
}
