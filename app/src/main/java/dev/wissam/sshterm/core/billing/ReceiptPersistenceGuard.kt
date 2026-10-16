package dev.wissam.sshterm.core.billing

/** Main-thread ownership fence. Failed/unverified queries are not ownership changes. */
internal class ReceiptPersistenceGuard {
    class Ticket internal constructor(val token: String, internal val revision: Long)
    private var token: String? = null
    private var revision = 0L

    fun confirmedOwnership(purchaseToken: String?): Ticket? {
        if (token != purchaseToken) {
            token = purchaseToken
            revision++
        }
        return purchaseToken?.let { Ticket(it, revision) }
    }

    fun isCurrent(ticket: Ticket): Boolean = ticket.token == token && ticket.revision == revision

    fun persistIfCurrent(ticket: Ticket, persist: () -> Unit): Boolean {
        if (!isCurrent(ticket)) return false
        persist()
        return true
    }
}
