package live.geargrid.phone.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "briefs")
data class BriefEntity(
    @PrimaryKey val customerId: Long,
    val displayName: String,
    val companyName: String?,
    val parentCompany: String?,
    val rating: Double?,
    val status: String,
    val balanceDue: Double,
    val depositBalance: Double,
    val idRetained: Boolean,
    val activeItemsJson: String,
    val invoiceCount: Int,
    val firstRental: String?,
    val lastRental: String?,
    val lastPaymentJson: String?,
    val lastReviewComment: String?,
    val summaryLine: String,
    val updatedAt: String
)

@Entity(
    tableName = "brief_phones",
    primaryKeys = ["phoneE164", "customerId"],
    indices = [Index(value = ["phoneE164"])]
)
data class BriefPhoneEntity(
    val phoneE164: String,
    val customerId: Long
)

@Entity(tableName = "contact_index")
data class ContactIndexEntity(
    @PrimaryKey val phoneE164: String,
    val displayName: String,
    val contactKey: String,
    val updatedAt: Long
)

@Entity(tableName = "contact_state")
data class ContactStateEntity(
    @PrimaryKey val contactKey: String,
    val payloadHash: String,
    val updatedAt: Long
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val payloadJson: String,
    val attempts: Int = 0,
    val nextRetryAt: Long = 0,
    val createdAt: Long
)

@Dao
interface ContactSyncDao {
    @Query("SELECT * FROM contact_state")
    suspend fun getAllState(): List<ContactStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStates(rows: List<ContactStateEntity>)

    @Query("DELETE FROM contact_state WHERE contactKey IN (:keys)")
    suspend fun deleteStatesByKeys(keys: List<String>)

    @Query("DELETE FROM contact_state")
    suspend fun clearAllState()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContactIndex(rows: List<ContactIndexEntity>)

    @Query("DELETE FROM contact_index WHERE contactKey IN (:keys)")
    suspend fun deleteContactIndexByKeys(keys: List<String>)

    @Query("DELETE FROM contact_index")
    suspend fun clearAllContactIndex()

    @Insert
    suspend fun enqueueOutbox(row: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE nextRetryAt <= :nowMs ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getDueOutbox(nowMs: Long, limit: Int = 50): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteOutboxById(id: Long)

    @Query("UPDATE outbox SET attempts = :attempts, nextRetryAt = :nextRetryAt WHERE id = :id")
    suspend fun updateOutboxRetry(id: Long, attempts: Int, nextRetryAt: Long)
}

@Dao
interface BriefDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBriefs(rows: List<BriefEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBriefPhones(rows: List<BriefPhoneEntity>)

    @Query("DELETE FROM brief_phones WHERE customerId IN (:customerIds)")
    suspend fun deleteBriefPhonesByCustomerIds(customerIds: List<Long>)

    @Query("DELETE FROM briefs WHERE customerId IN (:customerIds)")
    suspend fun deleteBriefsByCustomerIds(customerIds: List<Long>)

    @Query(
        "SELECT b.* FROM briefs b " +
            "INNER JOIN brief_phones bp ON bp.customerId = b.customerId " +
            "WHERE bp.phoneE164 = :phoneE164 LIMIT 1"
    )
    suspend fun findBriefByPhone(phoneE164: String): BriefEntity?

    @Query("SELECT * FROM contact_index WHERE phoneE164 = :phoneE164 LIMIT 1")
    suspend fun findContactByPhone(phoneE164: String): ContactIndexEntity?

    @Query("DELETE FROM briefs")
    suspend fun clearBriefs()

    @Query("DELETE FROM brief_phones")
    suspend fun clearBriefPhones()
}

@Database(
    entities = [
        BriefEntity::class,
        BriefPhoneEntity::class,
        ContactIndexEntity::class,
        ContactStateEntity::class,
        OutboxEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactSyncDao(): ContactSyncDao
    abstract fun briefDao(): BriefDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "geargrid_phone_assistant.db"
                ).build().also { INSTANCE = it }
            }
    }
}
