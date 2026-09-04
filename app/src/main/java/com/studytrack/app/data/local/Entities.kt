package com.studytrack.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CourseStatus { ACTIVE, COMPLETED, ARCHIVED }
enum class LosStatus { NOT_STARTED, IN_PROGRESS, COMPLETED }
enum class SessionStatus { RUNNING, PAUSED }
enum class SessionSource { TIMER, MANUAL, COMMAND, VOICE }
enum class GoalType { COURSE_COMPLETION, SUBJECT_COMPLETION, WEEKLY_HOURS, ACCURACY_TARGET }
enum class GoalStatus { ACTIVE, COMPLETED, MISSED, PAUSED }
enum class RevisionStatus { DUE, COMPLETED, SKIPPED, RESCHEDULED }

/**
 * Top of the hierarchy (spec section 1/5). [usesLearningOutcomes] lets a
 * course opt out of the fourth (LOS) level entirely — e.g. a Python
 * course can stop at Course -> Module -> Topic — while
 * [subjectLabel]/[topicLabel]/[losLabel] let the UI show CFA's own
 * vocabulary ("Subject" / "Reading" / "LOS") without hard-coding it.
 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val courseType: String = "Certification",
    val startDate: Long? = null,
    val targetDate: Long? = null,
    val priority: Int = 3,
    val description: String? = null,
    val subjectLabel: String = "Subject",
    val topicLabel: String = "Topic",
    val losLabel: String = "Learning Outcome",
    val usesLearningOutcomes: Boolean = true,
    val status: CourseStatus = CourseStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "modules",
    foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("courseId")]
)
data class ModuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val name: String,
    // Comma-separated shorthand the command parser also matches against,
    // e.g. "FSA" for "Financial Statement Analysis".
    val aliases: String? = null,
    val orderIndex: Int = 0,
    val description: String? = null
)

@Entity(
    tableName = "topics",
    foreignKeys = [ForeignKey(entity = ModuleEntity::class, parentColumns = ["id"], childColumns = ["moduleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("moduleId")]
)
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleId: Long,
    val name: String,
    // e.g. 28 for "Reading 28" — lets the parser resolve "Start CFA
    // Reading 28" precisely instead of fuzzy text matching.
    val topicNumber: Int? = null,
    val orderIndex: Int = 0,
    val estimatedMinutes: Int = 0,
    val description: String? = null
)

@Entity(
    tableName = "learning_outcomes",
    foreignKeys = [ForeignKey(entity = TopicEntity::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("topicId")]
)
data class LearningOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: Long,
    val code: String,
    val title: String,
    val description: String? = null,
    val estimatedMinutes: Int = 30,
    val targetDate: Long? = null,
    val status: LosStatus = LosStatus.NOT_STARTED,
    val difficulty: Int? = null,
    val confidence: Int? = null,
    val previousConfidence: Int? = null,
    val lastStudiedAt: Long? = null,
    val nextRevisionAt: Long? = null,
    val revisionCount: Int = 0,
    val notes: String? = null,
    // Populated fields ready for Phase 2's quiz tracking without a
    // migration; unused by Phase 1 UI.
    val questionsAttempted: Int = 0,
    val questionsCorrect: Int = 0,
    val orderIndex: Int = 0
)

/**
 * Singleton row (id is always 1) holding whatever session is currently
 * running or paused. This — not a live in-memory counter — is the
 * source of truth for elapsed time, which is what makes the timer
 * survive process death and device restart (spec section 48): elapsed
 * time is always *computed* from [startTime]/[lastResumeTime]/
 * [accumulatedMillis], never counted by a ticker that could be killed.
 */
@Entity(tableName = "active_session")
data class ActiveSessionEntity(
    @PrimaryKey val id: Int = 1,
    val courseId: Long,
    val moduleId: Long,
    val topicId: Long? = null,
    val losId: Long? = null,
    val startTime: Long,
    val lastResumeTime: Long,
    val accumulatedMillis: Long = 0,
    val status: SessionStatus = SessionStatus.RUNNING,
    val note: String? = null
) {
    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long =
        if (status == SessionStatus.RUNNING) accumulatedMillis + (now - lastResumeTime) else accumulatedMillis
}

// Deliberately no FK on topicId/losId: if the hierarchy is later edited
// or a topic deleted, historical study records should still stand.
@Entity(
    tableName = "study_sessions",
    foreignKeys = [
        ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ModuleEntity::class, parentColumns = ["id"], childColumns = ["moduleId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("courseId"), Index("moduleId"), Index("topicId"), Index("losId"), Index("startTime")]
)
data class StudySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val moduleId: Long,
    val topicId: Long? = null,
    val losId: Long? = null,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val productivityRating: Int? = null,
    val confidenceAtEnd: Int? = null,
    val sessionNotes: String? = null,
    val source: SessionSource = SessionSource.TIMER,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "goals", indices = [Index("courseId")])
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long? = null,
    val moduleId: Long? = null,
    val title: String,
    val goalType: GoalType,
    val targetValue: Double? = null,
    val targetDate: Long? = null,
    val priority: Int = 3,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "revisions", indices = [Index("losId"), Index("topicId"), Index("scheduledDate")])
data class RevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val losId: Long? = null,
    val topicId: Long? = null,
    val scheduledDate: Long,
    val intervalLabel: String,
    val status: RevisionStatus = RevisionStatus.DUE,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
