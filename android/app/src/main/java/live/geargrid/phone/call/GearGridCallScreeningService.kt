package live.geargrid.phone.call

import android.telecom.Call
import android.telecom.CallScreeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import live.geargrid.phone.CredentialStore
import live.geargrid.phone.PhoneNormalizer
import live.geargrid.phone.api.DeviceApiClient
import live.geargrid.phone.db.AppDatabase
import live.geargrid.phone.db.BriefEntity
import live.geargrid.phone.db.BriefPhoneEntity
import live.geargrid.phone.db.ContactIndexEntity
import live.geargrid.phone.db.OutboxEntity
import live.geargrid.phone.sync.OutboxWorker
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class GearGridCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Always let the call ring immediately without delay or blocking.
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )

        val appContext = applicationContext
        val store = CredentialStore(appContext)
        if (!store.hasCredentials()) return
        if (!store.isWithinBusinessHours()) return

        val direction = if (callDetails.callDirection == Call.Details.DIRECTION_OUTGOING) {
            "OUTGOING"
        } else {
            "INCOMING"
        }

        val accountHandle = callDetails.accountHandle
        val simLabel = CallPolicyHelper.resolveSimLabel(appContext, accountHandle)
        val accountId = accountHandle?.id?.trim()?.takeIf { it.isNotEmpty() }
        val simMatches = CallPolicyHelper.matchesSimFilter(store.getSimFilter(), simLabel, accountId)

        val rawNumber = callDetails.handle?.schemeSpecificPart?.trim()?.takeIf { it.isNotEmpty() }
        val phoneE164 = rawNumber?.let { PhoneNormalizer.normalizeToDigits(it) }
        val phoneDisplay = rawNumber ?: "Unknown caller"

        val session = ActiveCallSession.startOrReuse(rawNumber, phoneE164, direction, appContext)

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(appContext)
            val briefDao = db.briefDao()
            val contactDao = db.contactSyncDao()
            val api = DeviceApiClient(store)

            val localBrief = phoneE164?.let { briefDao.findBriefByPhone(it) }
            val localContact = if (localBrief == null && phoneE164 != null) {
                briefDao.findContactByPhone(phoneE164)
            } else {
                null
            }

            val shouldShowOverlay = direction == "INCOMING" || store.getOverlayShowFor() == "incoming_and_outgoing"
            if (shouldShowOverlay) {
                CallerOverlayManager.showBrief(appContext, phoneDisplay, localBrief, localContact)
            }

            val callLoggingScope = store.getCallLoggingScope()
            val isLocallyKnown = localBrief != null || localContact != null
            if (simMatches &&
                (isLocallyKnown || callLoggingScope == "all") &&
                CallPolicyHelper.shouldLogForScope(callLoggingScope, localBrief != null, localContact != null) &&
                ActiveCallSession.markRingingPosted(session.clientCallId, appContext)
            ) {
                postOrEnqueueRinging(appContext, api, contactDao, session, rawNumber, direction, simLabel)
            }

            if (!isLocallyKnown && phoneE164 != null) {
                val lookupJson = withTimeoutOrNull(2_000L) {
                    try {
                        val encoded = URLEncoder.encode(phoneE164, "UTF-8")
                        api.get("/device/briefs/lookup?phone=$encoded")
                    } catch (_: Exception) {
                        null
                    }
                }
                val data = lookupJson?.optJSONObject("data") ?: return@launch
                if (ActiveCallSession.peek()?.clientCallId != session.clientCallId) return@launch

                when (data.optString("match")) {
                    "customer" -> {
                        val obj = data.optJSONObject("brief") ?: return@launch
                        val customerId = obj.optLong("customer_id", 0L)
                        if (customerId <= 0L) return@launch
                        val freshBrief = BriefEntity(
                            customerId = customerId,
                            displayName = obj.optString("display_name", "Customer #$customerId"),
                            companyName = obj.optNullableString("company_name"),
                            parentCompany = obj.optNullableString("parent_company"),
                            rating = if (obj.isNull("rating")) null else obj.optDouble("rating"),
                            status = obj.optString("status", "Active"),
                            balanceDue = obj.optDouble("balance_due", 0.0),
                            depositBalance = obj.optDouble("deposit_balance", 0.0),
                            idRetained = obj.optBoolean("id_retained", false),
                            activeItemsJson = (obj.optJSONArray("active_items") ?: JSONArray()).toString(),
                            invoiceCount = obj.optInt("invoice_count", 0),
                            firstRental = obj.optNullableString("first_rental"),
                            lastRental = obj.optNullableString("last_rental"),
                            lastPaymentJson = obj.optJSONObject("last_payment")?.toString(),
                            lastReviewComment = obj.optNullableString("last_review_comment"),
                            summaryLine = obj.optString("summary_line", ""),
                            updatedAt = obj.optString("updated_at", ""),
                            syncedAt = java.time.Instant.now().toString(),
                            hasDues = obj.optBoolean("has_dues", false),
                            nicNumber = obj.optNullableString("nic_number")
                        )
                        val phonesJson = obj.optJSONArray("phones") ?: JSONArray()
                        val phoneRows = ArrayList<BriefPhoneEntity>(phonesJson.length())
                        for (i in 0 until phonesJson.length()) {
                            val num = phonesJson.optString(i).trim()
                            if (num.isNotEmpty()) {
                                phoneRows.add(BriefPhoneEntity(phoneE164 = num, customerId = customerId))
                            }
                        }
                        briefDao.deleteBriefPhonesByCustomerIds(listOf(customerId))
                        briefDao.upsertBriefs(listOf(freshBrief))
                        if (phoneRows.isNotEmpty()) briefDao.upsertBriefPhones(phoneRows)
                        if (shouldShowOverlay) {
                            CallerOverlayManager.showBrief(appContext, phoneDisplay, freshBrief, null, live = true)
                        }
                        if (simMatches &&
                            CallPolicyHelper.shouldLogForScope(callLoggingScope, true, false) &&
                            ActiveCallSession.markRingingPosted(session.clientCallId, appContext)
                        ) {
                            postOrEnqueueRinging(appContext, api, contactDao, session, rawNumber, direction, simLabel)
                        }
                    }
                    "contact" -> {
                        val cObj = data.optJSONObject("contact") ?: return@launch
                        val name = cObj.optString("display_name", "").trim()
                        if (name.isNotEmpty()) {
                            val remoteContact = ContactIndexEntity(
                                phoneE164 = phoneE164,
                                displayName = name,
                                contactKey = "remote:${cObj.optLong("contact_id", 0L)}",
                                updatedAt = System.currentTimeMillis()
                            )
                            if (shouldShowOverlay) {
                                CallerOverlayManager.showBrief(appContext, phoneDisplay, null, remoteContact)
                            }
                            if (simMatches &&
                                CallPolicyHelper.shouldLogForScope(callLoggingScope, false, true) &&
                                ActiveCallSession.markRingingPosted(session.clientCallId, appContext)
                            ) {
                                postOrEnqueueRinging(appContext, api, contactDao, session, rawNumber, direction, simLabel)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun postOrEnqueueRinging(
        appContext: android.content.Context,
        api: DeviceApiClient,
        contactDao: live.geargrid.phone.db.ContactSyncDao,
        session: CallSession,
        rawNumber: String?,
        direction: String,
        simLabel: String?
    ) {
        val ringingBody = JSONObject().apply {
            put("client_call_id", session.clientCallId)
            if (rawNumber != null) put("raw_number", rawNumber) else put("raw_number", JSONObject.NULL)
            put("direction", direction)
            put("state", "RINGING")
            put("started_at", session.startedAtIso)
            if (!simLabel.isNullOrBlank()) put("sim_label", simLabel.take(40))
        }

        try {
            api.post("/device/calls", ringingBody)
        } catch (e: DeviceApiClient.HttpException) {
            if (e.code !in listOf(400, 401, 403, 409)) {
                contactDao.enqueueOutbox(
                    OutboxEntity(
                        kind = OutboxWorker.KIND_CALL_EVENT,
                        payloadJson = ringingBody.toString(),
                        createdAt = System.currentTimeMillis()
                    )
                )
                OutboxWorker.enqueueOneShot(appContext)
            }
        } catch (_: Exception) {
            contactDao.enqueueOutbox(
                OutboxEntity(
                    kind = OutboxWorker.KIND_CALL_EVENT,
                    payloadJson = ringingBody.toString(),
                    createdAt = System.currentTimeMillis()
                )
            )
            OutboxWorker.enqueueOneShot(appContext)
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key, "").trim()
        return if (value.isEmpty() || value == "null") null else value
    }
}
