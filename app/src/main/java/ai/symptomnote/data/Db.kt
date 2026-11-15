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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /**
     * 삭제된 창문 점검 기능(v1.0.x)이 남긴 기록의 source 값. 새로 저장하지 않는다.
     * 이미 저장된 기록이 있을 수 있어 값은 남기고, 목록·분석 쿼리에서 제외한다.
     */
    const val LEGACY_WINDOW = "WINDOW"
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
    val demo: Boolean = false,
    /** 수동 기록: 직전 60초 소리 전체(WAV). file 은 그중 추정 지점 앞뒤 5초를 자른 대표 구간. */
    val fullFile: String? = null,
    /** 수동 기록: 같은 60초의 1초 단위 조건 기록(CSV). */
    val condFile: String? = null,
    /** 수동 기록: 추정 지점이 버튼을 누른 때보다 몇 초 전인가. */
    val pointAgoSec: Int? = null,
    /** 수동 기록: 브레이크 구간 안에서 찾았는가(false = 60초 전체에서 가장 큰 소리). */
    val pointByBrake: Boolean? = null,
    /** 수리 날짜가 있는 증상에 넣을 때 사용자가 고른 구분(true = 수리 후 재발, false = 수리 전 기록). null = 기록 시각으로 판단. */
    val afterRepair: Boolean? = null
) {
    /** 이 기록이 가진 파일 전부(삭제용). */
    fun files(): List<String> = listOfNotNull(file, fullFile, condFile)

    /** 이 증상의 수리 날짜 기준으로 수리 후 기록인가(사용자가 고른 값 우선). */
    fun isAfterRepair(repairedAt: Long?): Boolean = ai.symptomnote.core.Grouping.isAfterRepair(t, repairedAt, afterRepair)

    companion object {
        /** 링 버퍼 10초 저장 기록(RecordSaver.save). 자동 포착이면 [autoFor] 증상에 바로 묶는다. 수리 전/후 구분은 기록 시각으로 판단(afterRepair = null). */
        fun captured(t: Long, file: String, source: String, snap: Snapshot, sampleRate: Int, peak: Int, autoFor: Long?) =
            Recording(t = t, file = file, source = source, snap = snap, sampleRate = sampleRate, peak = peak,
                symptomId = autoFor, autoForSymptomId = autoFor)
    }
}

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
    @Query("SELECT * FROM Recording WHERE source != 'WINDOW' ORDER BY t DESC") fun recordings(): Flow<List<Recording>>
    @Query("SELECT * FROM Recording WHERE symptomId = :id AND source != 'WINDOW' ORDER BY t") fun recordingsOf(id: Long): Flow<List<Recording>>
    @Query("SELECT * FROM Recording WHERE symptomId = :id AND source != 'WINDOW' ORDER BY t") suspend fun recordingsOfNow(id: Long): List<Recording>
    /** 증상에 넣거나(sid) 뺀다(sid = null). 수리 전/후 구분도 함께 정한다(null = 기록 시각으로 판단). */
    @Query("UPDATE Recording SET symptomId = :sid, afterRepair = :afterRepair WHERE id IN (:ids)")
    suspend fun assign(ids: List<Long>, sid: Long?, afterRepair: Boolean? = null)
    @Query("SELECT * FROM Recording WHERE file IS NOT NULL") suspend fun withFiles(): List<Recording>
    @Query("UPDATE Recording SET file = NULL, fullFile = NULL, condFile = NULL") suspend fun clearFiles()

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

@Database(entities = [DriveSecond::class, Recording::class, Symptom::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): NoteDao

    companion object {
        /** 2 → 3: 수리 전/후 구분을 사용자가 고른 값. */
        private val M2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE Recording ADD COLUMN afterRepair INTEGER")
        }

        /** 1 → 2: 수동 기록 60초(전체 소리, 조건 기록, 추정 지점) 열 추가. 기존 기록은 그대로 둔다. */
        private val M1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Recording ADD COLUMN fullFile TEXT")
                db.execSQL("ALTER TABLE Recording ADD COLUMN condFile TEXT")
                db.execSQL("ALTER TABLE Recording ADD COLUMN pointAgoSec INTEGER")
                db.execSQL("ALTER TABLE Recording ADD COLUMN pointByBrake INTEGER")
            }
        }

        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "symptomnote.db")
                .addMigrations(M1_2, M2_3).build().also { inst = it }
        }
    }
}
