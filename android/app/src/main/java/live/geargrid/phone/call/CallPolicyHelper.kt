package live.geargrid.phone.call

import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

internal object CallPolicyHelper {

    fun shouldLogForScope(scope: String, isCustomer: Boolean, isContact: Boolean): Boolean =
        when (scope) {
            "off" -> false
            "customers_only" -> isCustomer
            "customers_and_contacts" -> isCustomer || isContact
            "all" -> true
            else -> isCustomer || isContact
        }

    fun matchesSimFilter(filter: String?, simLabel: String?, accountId: String?): Boolean {
        val normalizedFilter = filter?.trim()?.takeIf { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
            ?: return true
        val candidate = simLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?: accountId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return true
        return candidate.equals(normalizedFilter, ignoreCase = true) ||
            (accountId != null && accountId.trim().equals(normalizedFilter, ignoreCase = true))
    }

    fun resolveSimLabel(context: Context, handle: PhoneAccountHandle?): String? {
        if (handle == null) return null
        return runCatching {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val label = tm?.getPhoneAccount(handle)?.label?.toString()?.trim()
            label?.takeIf { it.isNotEmpty() } ?: handle.id?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrElse {
            handle.id?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
