package ai.symptomnote.data

import ai.symptomnote.core.DriveSample
import ai.symptomnote.core.Snapshot
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 평소 주행 1초 요약. 트립(기어가 P 를 벗어난 뒤 다시 P 가 될 때까지) 단위로 쌓는다. */
@Entity(indices = [Index("t")])
data class DriveSecond(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val s: DriveSample,
    val demo: Boolean = false
)

object Source {
    const val MANUAL = "MANUAL"
    const val AUTO = "AUTO"
    const val WINDOW = "WINDOW"
}

/** 소리 기록 1개 + 저장 순간의 조건 스냅샷(직전 10초). file 은 앱 내부 저장소 경로, 삭제하면 null. */
@Entity(indices = [Index("symptomId"), Index("t")])
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val t: Long,
    val file: String?,
    val source: String,
    val symptomId: Long? = null,
    @Embedded val snap: Snapshot,
    val sampleRate: Int = 0,
    val peak: Int = 0,
    /** 자동 포착 기록이면 어떤 증상 규칙으로 잡았는지. */
    val autoForSymptomId: Long? = null,
    val demo: Boolean = false
)

/** 증상(케이스). rule 은 자동 포착을 켤 때 고정한 공통 조건(Conditions.encode). */
@Entity
data class Symptom(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val autoCapture: Boolean = false,
    val rule: String? = null,
    val repairedAt: Long? = null,
    val representativeId: Long? = null,
    val demo: Boolean = false
)

@Dao
interface NoteDao {
    @Insert suspend fun insertSecond(s: DriveSecond)
    @Insert suspend fun insertSeconds(s: List<DriveSecond>)
    @Query("SELECT * FROM DriveSecond ORDER BY tripId, t") suspend fun allSeconds(): List<DriveSecond>
    @Query("SELECT * FROM DriveSecond WHERE t >= :from ORDER BY tripId, t") suspend fun secondsFrom(from: Long): List<DriveSecond>
    @Query("SELECT COUNT(*) FROM DriveSecond") fun secondCount(): Flow<Int>

    @Insert suspend fun insertRecording(r: Recording): Long
    @Update suspend fun updateRecording(r: Recording)
    @Delete suspend fun deleteRecording(r: Recording)
    @Query("SELECT * FROM Recording ORDER BY t DESC") fun recordings(): Flow<List<Recording>>
    @Query("SELECT * FROM Recording WHERE symptomId = :id ORDER BY t") fun recordingsOf(id: Long): Flow<List<Recording>>
    @Query("SELECT * FROM Recording WHERE symptomId = :id ORDER BY t") suspend fun recordingsOfNow(id: Long): List<Recording>
    @Query("SELECT * FROM Recording WHERE source = 'WINDOW' ORDER BY t") fun windowChecks(): Flow<List<Recording>>
    @Query("SELECT * FROM Recording WHERE source = 'WINDOW' ORDER BY t") suspend fun windowChecksNow(): List<Recording>
    @Query("UPDATE Recording SET symptomId = :sid WHERE id IN (:ids)") suspend fun assign(ids: List<Long>, sid: Long?)
    @Query("SELECT * FROM Recording WHERE file IS NOT NULL") suspend fun withFiles(): List<Recording>
    @Query("UPDATE Recording SET file = NULL") suspend fun clearFiles()

    @Insert suspend fun insertSymptom(s: Symptom): Long
    @Update suspend fun updateSymptom(s: Symptom)
    @Delete suspend fun deleteSymptom(s: Symptom)
    @Query("SELECT * FROM Symptom ORDER BY createdAt DESC") fun symptoms(): Flow<List<Symptom>>
    @Query("SELECT * FROM Symptom ORDER BY createdAt DESC") suspend fun symptomsNow(): List<Symptom>
    @Query("SELECT * FROM Symptom WHERE id = :id") fun symptom(id: Long): Flow<Symptom?>
    @Query("SELECT * FROM Symptom WHERE autoCapture = 1") fun autoSymptoms(): Flow<List<Symptom>>
    @Query("SELECT COUNT(*) FROM Symptom WHERE demo = 1") fun demoCount(): Flow<Int>

    @Query("UPDATE Recording SET symptomId = NULL WHERE symptomId = :id") suspend fun unassignAll(id: Long)
    @Query("SELECT * FROM Recording WHERE demo = 1") suspend fun demoRecordings(): List<Recording>
    @Query("DELETE FROM Recording WHERE demo = 1") suspend fun deleteDemoRecordings()
    @Query("DELETE FROM Symptom WHERE demo = 1") suspend fun deleteDemoSymptoms()
    @Query("DELETE FROM DriveSecond WHERE demo = 1") suspend fun deleteDemoSeconds()
}

@Database(entities = [DriveSecond::class, Recording::class, Symptom::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): NoteDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "symptomnote.db").build().also { inst = it }
        }
    }
}
