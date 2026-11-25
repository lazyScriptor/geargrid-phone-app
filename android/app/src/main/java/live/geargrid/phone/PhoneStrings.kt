package live.geargrid.phone

import java.util.Locale

/**
 * Words the app draws natively (call screen labels, footer, buttons). Briefs carry structured values only;
 * the phone composes the text in the language chosen under Settings > Language & text.
 */
object PhoneStrings {
    private val en = mapOf(
        "footer_live" to "Live",
        "footer_synced" to "Synced %d min ago",
        "footer_offline" to "Offline · data from %s",
        "not_in_geargrid" to "Not in GearGrid",
        "saved_contact" to "Saved in phone contacts",
        "blacklisted_banner" to "BLACKLISTED CUSTOMER",
        "blacklisted" to "Blacklisted customer",
        "has_dues" to "Has dues",
        "id_kept" to "ID card kept",
        "overdue_days" to "Overdue %d days",
        "more_rows" to "+%d more · tap to expand",
        "less_rows" to "Show less",
        "field_name" to "Name", "field_company" to "Company", "field_rating" to "Rating",
        "field_status_warning" to "Status", "field_balance_due" to "Balance due", "field_deposit_balance" to "Deposit",
        "field_id_retained" to "ID", "field_active_items" to "Machines out", "field_overdue" to "Overdue",
        "field_invoice_count" to "Bills", "field_rental_dates" to "Rental dates", "field_last_payment" to "Last payment",
        "field_last_review_comment" to "Last review", "field_recent_call_notes" to "Call notes",
        "action_dismiss" to "Dismiss", "action_add_note" to "Add note", "action_open_on_desktop" to "Open on desktop",
        "action_open_app" to "Open app", "action_call_back_later" to "Call back later",
        "sample_name" to "Sample Customer",
        "action_mark_checked" to "Mark checked", "action_open_bill" to "Open bill",
        "check_done" to "Marked checked", "check_failed" to "Couldn't mark it checked. Try again.",
        "check_not_allowed" to "You can't mark events checked",
        "call_note_title" to "Add the call note",
        "group_new" to "%d new notifications",
        "group_payments" to "%d payments", "group_discounts" to "%d discounts", "group_backdated" to "%d back-dated entries",
        "group_returns" to "%d returns", "group_bills" to "%d bills", "group_system" to "%d system alerts",
        "group_alerts" to "%d alerts",
    )
    private val si = mapOf(
        "footer_live" to "සජීවී",
        "footer_synced" to "මිනිත්තු %d කට පෙර සමමුහුර්ත කළා",
        "footer_offline" to "නොබැඳි · දත්ත %s සිට",
        "not_in_geargrid" to "GearGrid හි නැත",
        "saved_contact" to "දුරකථන සම්බන්ධතාවල සුරැකී ඇත",
        "blacklisted_banner" to "අවහිර කළ පාරිභෝගිකයා",
        "blacklisted" to "අවහිර කළ පාරිභෝගිකයා",
        "has_dues" to "ගෙවීමට ඇත",
        "id_kept" to "හැඳුනුම්පත රඳවා ඇත",
        "overdue_days" to "දින %d ක් පමා වී ඇත",
        "more_rows" to "තවත් %d ක් · විවෘත කිරීමට තට්ටු කරන්න",
        "less_rows" to "අඩුවෙන් පෙන්වන්න",
        "field_name" to "නම", "field_company" to "සමාගම", "field_rating" to "ශ්‍රේණිගත කිරීම",
        "field_status_warning" to "තත්ත්වය", "field_balance_due" to "ගෙවිය යුතු ශේෂය", "field_deposit_balance" to "තැන්පතුව",
        "field_id_retained" to "හැඳුනුම්පත", "field_active_items" to "පිටත ඇති යන්ත්‍ර", "field_overdue" to "පමා",
        "field_invoice_count" to "බිල්පත්", "field_rental_dates" to "කුලී දින", "field_last_payment" to "අවසන් ගෙවීම",
        "field_last_review_comment" to "අවසන් සමාලෝචනය", "field_recent_call_notes" to "ඇමතුම් සටහන්",
        "action_dismiss" to "වසන්න", "action_add_note" to "සටහනක්", "action_open_on_desktop" to "ඩෙස්ක්ටොප් එකේ විවෘත කරන්න",
        "action_open_app" to "යෙදුම විවෘත කරන්න", "action_call_back_later" to "පසුව අමතන්න",
        "sample_name" to "නියැදි පාරිභෝගිකයා",
        "action_mark_checked" to "පරීක්ෂා කළා", "action_open_bill" to "බිල්පත විවෘත කරන්න",
        "check_done" to "පරීක්ෂා කළ ලෙස සලකුණු කළා", "check_failed" to "සලකුණු කළ නොහැකි විය. නැවත උත්සාහ කරන්න.",
        "check_not_allowed" to "ඔබට පරීක්ෂා කළ ලෙස සලකුණු කළ නොහැක",
        "call_note_title" to "ඇමතුම් සටහන එක් කරන්න",
        "group_new" to "නව දැනුම්දීම් %d",
        "group_payments" to "ගෙවීම් %d", "group_discounts" to "වට්ටම් %d", "group_backdated" to "පසු දිනැති ඇතුළත් කිරීම් %d",
        "group_returns" to "ආපසු භාරගැනීම් %d", "group_bills" to "බිල්පත් %d", "group_system" to "පද්ධති දැනුම්දීම් %d",
        "group_alerts" to "අනතුරු ඇඟවීම් %d",
    )
    private val ta = mapOf(
        "footer_live" to "நேரலை",
        "footer_synced" to "%d நிமிடங்களுக்கு முன் ஒத்திசைக்கப்பட்டது",
        "footer_offline" to "இணைப்பு இல்லை · தரவு %s முதல்",
        "not_in_geargrid" to "GearGrid இல் இல்லை",
        "saved_contact" to "தொலைபேசி தொடர்புகளில் சேமிக்கப்பட்டுள்ளது",
        "blacklisted_banner" to "தடைசெய்யப்பட்ட வாடிக்கையாளர்",
        "blacklisted" to "தடைசெய்யப்பட்ட வாடிக்கையாளர்",
        "has_dues" to "நிலுவை உள்ளது",
        "id_kept" to "அடையாள அட்டை வைக்கப்பட்டுள்ளது",
        "overdue_days" to "%d நாட்கள் தாமதம்",
        "more_rows" to "மேலும் %d · விரிவாக்க தட்டவும்",
        "less_rows" to "குறைவாகக் காட்டு",
        "field_name" to "பெயர்", "field_company" to "நிறுவனம்", "field_rating" to "மதிப்பீடு",
        "field_status_warning" to "நிலை", "field_balance_due" to "நிலுவைத் தொகை", "field_deposit_balance" to "வைப்புத் தொகை",
        "field_id_retained" to "அடையாள அட்டை", "field_active_items" to "வெளியே உள்ள இயந்திரங்கள்", "field_overdue" to "தாமதம்",
        "field_invoice_count" to "பில்கள்", "field_rental_dates" to "வாடகை தேதிகள்", "field_last_payment" to "கடைசி கட்டணம்",
        "field_last_review_comment" to "கடைசி மதிப்புரை", "field_recent_call_notes" to "அழைப்புக் குறிப்புகள்",
        "action_dismiss" to "மூடு", "action_add_note" to "குறிப்பு சேர்", "action_open_on_desktop" to "கணினியில் திற",
        "action_open_app" to "செயலியைத் திற", "action_call_back_later" to "பின்னர் அழை",
        "sample_name" to "மாதிரி வாடிக்கையாளர்",
        "action_mark_checked" to "சரிபார்த்ததாகக் குறி", "action_open_bill" to "பில்லைத் திற",
        "check_done" to "சரிபார்த்ததாகக் குறிக்கப்பட்டது", "check_failed" to "குறிக்க முடியவில்லை. மீண்டும் முயலவும்.",
        "check_not_allowed" to "நீங்கள் சரிபார்த்ததாகக் குறிக்க முடியாது",
        "call_note_title" to "அழைப்புக் குறிப்பைச் சேர்க்கவும்",
        "group_new" to "%d புதிய அறிவிப்புகள்",
        "group_payments" to "%d கட்டணங்கள்", "group_discounts" to "%d தள்ளுபடிகள்", "group_backdated" to "%d பின் தேதியிட்ட பதிவுகள்",
        "group_returns" to "%d திருப்புதல்கள்", "group_bills" to "%d பில்கள்", "group_system" to "%d கணினி அறிவிப்புகள்",
        "group_alerts" to "%d எச்சரிக்கைகள்",
    )

    /** "follow" (or anything unknown) means the phone's own language, limited to the three the app has. */
    fun resolveLanguage(choice: String?): String {
        val c = choice?.lowercase(Locale.ROOT).orEmpty()
        if (c == "en" || c == "si" || c == "ta") return c
        return when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
            "si" -> "si"
            "ta" -> "ta"
            else -> "en"
        }
    }

    fun get(language: String, key: String): String =
        (when (language) { "si" -> si; "ta" -> ta; else -> en })[key] ?: en[key] ?: key

    fun format(language: String, key: String, vararg args: Any): String =
        String.format(Locale.US, get(language, key), *args)
}
