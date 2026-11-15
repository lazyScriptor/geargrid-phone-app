package live.geargrid.phone

/**
 * Matches backend `src/utils/whatsappPhone.js` normalization rules:
 * Sri Lankan local/mobile/landline numbers and explicit international numbers
 * are stored as digits-only (e.g., "94771234567", no "+" prefix).
 */
object PhoneNormalizer {

    private const val SL = "94"
    private val splitRegex = Regex("""[/,;|\n]+|\s+(?:and|or)\s+""", RegexOption.IGNORE_CASE)

    fun splitPhones(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return text.split(splitRegex).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun normalizeToDigits(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        var digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        val hasPlus = text.startsWith("+")
        val hasDoubleZero = digits.startsWith("00")
        if (!hasPlus && hasDoubleZero) {
            digits = digits.substring(2)
        }
        val international = hasPlus || hasDoubleZero

        val sl: String? = when {
            digits.startsWith(SL) && digits.length == 11 -> digits.substring(2)
            !international && digits.startsWith("0") && digits.length == 10 -> digits.substring(1)
            !international && digits.length == 9 -> digits
            digits.startsWith("${SL}0") && digits.length == 12 -> digits.substring(3)
            else -> null
        }

        if (sl != null) {
            return "$SL$sl"
        }

        if (international && digits.length in 8..15) {
            return digits
        }

        return null
    }
}
