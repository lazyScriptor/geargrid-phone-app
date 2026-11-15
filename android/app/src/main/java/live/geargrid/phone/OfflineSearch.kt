package live.geargrid.phone

import android.content.Context
import live.geargrid.phone.db.AppDatabase
import live.geargrid.phone.db.BriefEntity

/**
 * Offline customer lookup over the phone's local cache (Release 2 D8).
 *
 * Customers are found by name, company, NIC, customer ID or phone, but only among what the shop's brief profile
 * let this phone download (a profile that does not send NICs never matches by NIC). Synced phone contacts are
 * found by phone number only, with at least [MIN_CONTACT_SEARCH_DIGITS] typed digits, and only when no
 * customer matched.
 */
object OfflineSearch {
    // FUTURE (super admin setting): mirrors MIN_CONTACT_SEARCH_DIGITS on the server; see docs/FUTURE_SETTINGS.md.
    const val MIN_CONTACT_SEARCH_DIGITS = 9
    private const val MIN_PHONE_DIGITS = 3

    data class Hit(
        val kind: String,
        val id: Long,
        val name: String,
        val company: String?,
        val phone: String?,
        val rating: Double?,
        val status: String?,
        val summary: String?,
        val hasDues: Boolean
    )

    data class Result(val hits: List<Hit>, val cachedBriefs: Int)

    /** "0771234567", "+94 77 123 4567" and "94771234567" all become 771234567, like the server. */
    fun phoneCore(term: String): String? {
        var digits = term.filter { it.isDigit() }
        if (digits.startsWith("0094")) digits = digits.drop(4)
        else if (digits.startsWith("94") && digits.length > 9) digits = digits.drop(2)
        else if (digits.startsWith("0")) digits = digits.drop(1)
        return digits.takeIf { it.length >= MIN_PHONE_DIGITS }
    }

    fun looksLikePhone(term: String): Boolean = term.isNotBlank() && term.all { it.isDigit() || it in "+ ()-" }

    fun localPhone(e164: String): String {
        val digits = e164.filter { it.isDigit() }
        return if (digits.startsWith("94") && digits.length > 9) "0" + digits.drop(2) else digits
    }

    suspend fun search(context: Context, rawQuery: String, limit: Int = 15): Result {
        val db = AppDatabase.getInstance(context)
        val dao = db.briefDao()
        val cached = dao.countBriefs()
        val q = rawQuery.trim()
        if (q.length < 2) return Result(emptyList(), cached)

        val phoneLike = looksLikePhone(q)
        val core = if (phoneLike) phoneCore(q) else null
        val nic = q.replace(" ", "").uppercase().takeIf { it.length >= 5 && it.any { c -> c.isDigit() } && !phoneLike } ?: ""
        val id = if (q.all { it.isDigit() }) q.toLongOrNull() ?: 0L else 0L
        val matches = dao.searchCustomers(q, nic, id, core ?: "", limit * 2)

        // Exact customer ID first, then exact NIC, then phone matches, then names.
        fun rank(b: BriefEntity): Int = when {
            id > 0 && b.customerId == id -> 0
            nic.isNotEmpty() && b.nicNumber?.replace(" ", "")?.uppercase() == nic -> 1
            core != null -> 2
            else -> 3
        }
        val customerHits = matches.sortedWith(compareBy({ rank(it) }, { it.displayName })).take(limit).map { b ->
            val phone = dao.getPhonesForCustomer(b.customerId).firstOrNull()?.let { localPhone(it) }
            Hit("customer", b.customerId, b.displayName, b.companyName, phone, b.rating, b.status, b.summaryLine.ifBlank { null }, b.hasDues || b.balanceDue > 0)
        }
        if (customerHits.isNotEmpty()) return Result(customerHits, cached)

        // Phone contacts only by a long enough phone number, and only when no customer matched.
        val typedDigits = q.count { it.isDigit() }
        if (phoneLike && core != null && typedDigits >= MIN_CONTACT_SEARCH_DIGITS) {
            val contacts = dao.searchContactsByPhone(core, 10).map { c ->
                Hit("contact", c.phoneE164.filter { it.isDigit() }.toLongOrNull() ?: 0L, c.displayName, null, localPhone(c.phoneE164), null, null, null, false)
            }
            return Result(contacts, cached)
        }
        return Result(emptyList(), cached)
    }
}
