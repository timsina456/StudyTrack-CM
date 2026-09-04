package com.studytrack.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Insert
    suspend fun insert(course: CourseEntity): Long

    @Update
    suspend fun update(course: CourseEntity)

    @Query("SELECT * FROM courses ORDER BY priority ASC, name ASC")
    fun getAllCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE id = :id")
    fun getCourseById(id: Long): Flow<CourseEntity?>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getCourseByIdOnce(id: Long): CourseEntity?

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun getCourseCount(): Int

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ModuleDao {
    @Insert
    suspend fun insert(module: ModuleEntity): Long

    @Update
    suspend fun update(module: ModuleEntity)

    @Query("SELECT * FROM modules WHERE courseId = :courseId ORDER BY orderIndex ASC, name ASC")
    fun getModulesForCourse(courseId: Long): Flow<List<ModuleEntity>>

    @Query("SELECT * FROM modules")
    fun getAllModules(): Flow<List<ModuleEntity>>

    @Query("SELECT * FROM modules WHERE id = :id")
    suspend fun getModuleByIdOnce(id: Long): ModuleEntity?

    @Query("SELECT * FROM modules WHERE courseId = :courseId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByExactName(courseId: Long, name: String): ModuleEntity?

    @Query("DELETE FROM modules WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TopicDao {
    @Insert
    suspend fun insert(topic: TopicEntity): Long

    @Update
    suspend fun update(topic: TopicEntity)

    @Query("SELECT * FROM topics WHERE moduleId = :moduleId ORDER BY orderIndex ASC, topicNumber ASC")
    fun getTopicsForModule(moduleId: Long): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics")
    fun getAllTopics(): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun getTopicByIdOnce(id: Long): TopicEntity?

    @Query("SELECT * FROM topics WHERE moduleId = :moduleId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByExactName(moduleId: Long, name: String): TopicEntity?

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface LearningOutcomeDao {
    @Insert
    suspend fun insert(los: LearningOutcomeEntity): Long

    @Update
    suspend fun update(los: LearningOutcomeEntity)

    @Query("SELECT * FROM learning_outcomes WHERE topicId = :topicId ORDER BY orderIndex ASC, code ASC")
    fun getLosForTopic(topicId: Long): Flow<List<LearningOutcomeEntity>>

    @Query("SELECT * FROM learning_outcomes")
    fun getAllLos(): Flow<List<LearningOutcomeEntity>>

    @Query("SELECT * FROM learning_outcomes WHERE id = :id")
    fun getLosById(id: Long): Flow<LearningOutcomeEntity?>

    @Query("SELECT * FROM learning_outcomes WHERE id = :id")
    suspend fun getLosByIdOnce(id: Long): LearningOutcomeEntity?

    @Query("SELECT * FROM learning_outcomes WHERE topicId = :topicId AND code = :code COLLATE NOCASE LIMIT 1")
    suspend fun findByCode(topicId: Long, code: String): LearningOutcomeEntity?

    @Query("DELETE FROM learning_outcomes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ActiveSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ActiveSessionEntity)

    @Query("SELECT * FROM active_session WHERE id = 1")
    fun observe(): Flow<ActiveSessionEntity?>

    @Query("SELECT * FROM active_session WHERE id = 1")
    suspend fun getOnce(): ActiveSessionEntity?

    @Query("DELETE FROM active_session WHERE id = 1")
    suspend fun clear()
}

@Dao
interface StudySessionDao {
    @Insert
    suspend fun insert(session: StudySessionEntity): Long

    @Delete
    suspend fun delete(session: StudySessionEntity)

    @Query("SELECT * FROM study_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<StudySessionEntity>>

    @Query("SELECT * FROM study_sessions WHERE startTime >= :from AND startTime < :to ORDER BY startTime DESC")
    fun getSessionsBetween(from: Long, to: Long): Flow<List<StudySessionEntity>>

    @Query("SELECT * FROM study_sessions WHERE losId = :losId ORDER BY startTime DESC")
    fun getSessionsForLos(losId: Long): Flow<List<StudySessionEntity>>

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM study_sessions WHERE startTime >= :from AND startTime < :to")
    suspend fun getTotalMinutesBetween(from: Long, to: Long): Int

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM study_sessions WHERE courseId = :courseId AND startTime >= :from AND startTime < :to")
    suspend fun getTotalMinutesForCourseBetween(courseId: Long, from: Long, to: Long): Int

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM study_sessions WHERE moduleId = :moduleId")
    suspend fun getTotalMinutesForModule(moduleId: Long): Int

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM study_sessions WHERE losId = :losId")
    suspend fun getTotalMinutesForLos(losId: Long): Int

    @Query("SELECT COUNT(*) FROM study_sessions WHERE startTime >= :from AND startTime < :to")
    suspend fun getSessionCountBetween(from: Long, to: Long): Int
}

@Dao
interface GoalDao {
    @Insert
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("SELECT * FROM goals ORDER BY priority ASC, targetDate ASC")
    fun getAllGoals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE status = 'ACTIVE' ORDER BY priority ASC, targetDate ASC")
    fun getActiveGoals(): Flow<List<GoalEntity>>
}

@Dao
interface RevisionDao {
    @Insert
    suspend fun insert(revision: RevisionEntity): Long

    @Update
    suspend fun update(revision: RevisionEntity)

    @Query("SELECT * FROM revisions WHERE status = 'DUE' AND scheduledDate < :endOfDay ORDER BY scheduledDate ASC")
    fun getDueRevisions(endOfDay: Long): Flow<List<RevisionEntity>>

    @Query("SELECT * FROM revisions ORDER BY scheduledDate DESC")
    fun getAllRevisions(): Flow<List<RevisionEntity>>

    @Query("SELECT * FROM revisions WHERE losId = :losId ORDER BY scheduledDate DESC")
    fun getRevisionsForLos(losId: Long): Flow<List<RevisionEntity>>

    @Query("SELECT * FROM revisions WHERE id = :id")
    suspend fun getByIdOnce(id: Long): RevisionEntity?
}
