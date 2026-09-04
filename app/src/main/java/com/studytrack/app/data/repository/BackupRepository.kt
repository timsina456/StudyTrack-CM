package com.studytrack.app.data.repository

import com.studytrack.app.data.local.CourseEntity
import com.studytrack.app.data.local.GoalEntity
import com.studytrack.app.data.local.LearningOutcomeEntity
import com.studytrack.app.data.local.ModuleEntity
import com.studytrack.app.data.local.RevisionEntity
import com.studytrack.app.data.local.StudySessionEntity
import com.studytrack.app.data.local.StudyTrackDatabase
import com.studytrack.app.data.local.TopicEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val courses: List<CourseEntity>,
    val modules: List<ModuleEntity>,
    val topics: List<TopicEntity>,
    val learningOutcomes: List<LearningOutcomeEntity>,
    val studySessions: List<StudySessionEntity>,
    val goals: List<GoalEntity>,
    val revisions: List<RevisionEntity>
)

/**
 * Whole-database export/import (spec section 33). Data never leaves the
 * device on its own — export/import both happen through the system file
 * picker that [com.studytrack.app.ui.settings.SettingsScreen] wires up,
 * so the user always chooses where the file goes.
 */
class BackupRepository(private val database: StudyTrackDatabase) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun exportAll(): String {
        val payload = BackupPayload(
            courses = database.courseDao().getAllCourses().first(),
            modules = database.moduleDao().getAllModules().first(),
            topics = database.topicDao().getAllTopics().first(),
            learningOutcomes = database.learningOutcomeDao().getAllLos().first(),
            studySessions = database.studySessionDao().getAllSessions().first(),
            goals = database.goalDao().getAllGoals().first(),
            revisions = database.revisionDao().getAllRevisions().first()
        )
        return json.encodeToString(BackupPayload.serializer(), payload)
    }

    /**
     * Restores a previously exported backup, REPLACING all current data.
     * A merge strategy would risk duplicate/conflicting IDs across
     * devices, so this is intentionally all-or-nothing — the Settings
     * screen confirms with the user before calling this.
     */
    suspend fun importAll(jsonText: String) {
        val payload = json.decodeFromString(BackupPayload.serializer(), jsonText)
        database.clearAllTables()
        payload.courses.forEach { database.courseDao().insert(it) }
        payload.modules.forEach { database.moduleDao().insert(it) }
        payload.topics.forEach { database.topicDao().insert(it) }
        payload.learningOutcomes.forEach { database.learningOutcomeDao().insert(it) }
        payload.studySessions.forEach { database.studySessionDao().insert(it) }
        payload.goals.forEach { database.goalDao().insert(it) }
        payload.revisions.forEach { database.revisionDao().insert(it) }
    }
}
