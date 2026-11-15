package live.geargrid.phone.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "active_calls")
data class ActiveCallEntity(
    @PrimaryKey val id: Int = 1,
    val clientCallId: String,
    val rawNumber: String?,
    val phoneE164: String?,
    val direction: String,
    val startedAtIso: String,
    val startedAtMs: Long,
    val answered: Boolean,
    val ringingPosted: Boolean
)

@Dao
interface ActiveCallDao {
    @Query("SELECT * FROM active_calls WHERE id = 1 LIMIT 1")
    suspend fun getActiveCall(): ActiveCallEntity?

    @Query("SELECT * FROM active_calls WHERE id = 1 LIMIT 1")
    fun getActiveCallSync(): ActiveCallEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActiveCall(entity: ActiveCallEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertActiveCallSync(entity: ActiveCallEntity)

    @Query("DELETE FROM active_calls")
    suspend fun clearActiveCall()

    @Query("DELETE FROM active_calls")
    fun clearActiveCallSync()
}
