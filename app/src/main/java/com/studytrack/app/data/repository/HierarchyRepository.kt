package com.studytrack.app.data.repository

import com.studytrack.app.data.local.CourseDao
import com.studytrack.app.data.local.CourseEntity
import com.studytrack.app.data.local.LearningOutcomeDao
import com.studytrack.app.data.local.LearningOutcomeEntity
import com.studytrack.app.data.local.LosStatus
import com.studytrack.app.data.local.ModuleDao
import com.studytrack.app.data.local.ModuleEntity
import com.studytrack.app.data.local.TopicDao
import com.studytrack.app.data.local.TopicEntity
import com.studytrack.app.util.parseCsv
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

data class ImportResult(
    val modulesCreated: Int,
    val topicsCreated: Int,
    val losCreated: Int,
    val losUpdated: Int,
    val rowsSkipped: Int,
    val errors: List<String>
)

class HierarchyRepository(
    private val courseDao: CourseDao,
    private val moduleDao: ModuleDao,
    private val topicDao: TopicDao,
    private val losDao: LearningOutcomeDao
) {
    fun observeCourses(): Flow<List<CourseEntity>> = courseDao.getAllCourses()
    fun observeCourse(id: Long): Flow<CourseEntity?> = courseDao.getCourseById(id)
    fun observeModules(courseId: Long): Flow<List<ModuleEntity>> = moduleDao.getModulesForCourse(courseId)
    fun observeTopics(moduleId: Long): Flow<List<TopicEntity>> = topicDao.getTopicsForModule(moduleId)
    fun observeLos(topicId: Long): Flow<List<LearningOutcomeEntity>> = losDao.getLosForTopic(topicId)
    fun observeLosById(id: Long): Flow<LearningOutcomeEntity?> = losDao.getLosById(id)

    suspend fun getAllCoursesOnce(): List<CourseEntity> = courseDao.getAllCourses().first()
    suspend fun getAllModulesOnce(): List<ModuleEntity> = moduleDao.getAllModules().first()
    suspend fun getAllTopicsOnce(): List<TopicEntity> = topicDao.getAllTopics().first()
    suspend fun getAllLosOnce(): List<LearningOutcomeEntity> = losDao.getAllLos().first()
    suspend fun getModuleOnce(id: Long): ModuleEntity? = moduleDao.getModuleByIdOnce(id)
    suspend fun getTopicOnce(id: Long): TopicEntity? = topicDao.getTopicByIdOnce(id)
    suspend fun getLosOnce(id: Long): LearningOutcomeEntity? = losDao.getLosByIdOnce(id)
    suspend fun getCourseOnce(id: Long): CourseEntity? = courseDao.getCourseByIdOnce(id)

    suspend fun addCourse(course: CourseEntity): Long = courseDao.insert(course)
    suspend fun updateCourse(course: CourseEntity) = courseDao.update(course)
    suspend fun addModule(module: ModuleEntity): Long = moduleDao.insert(module)
    suspend fun addTopic(topic: TopicEntity): Long = topicDao.insert(topic)
    suspend fun addLos(los: LearningOutcomeEntity): Long = losDao.insert(los)
    suspend fun updateLos(los: LearningOutcomeEntity) = losDao.update(los)

    suspend fun markLosStatus(losId: Long, status: LosStatus) {
        val los = losDao.getLosByIdOnce(losId) ?: return
        losDao.update(los.copy(status = status))
    }

    suspend fun setDifficulty(losId: Long, difficulty: Int) {
        val los = losDao.getLosByIdOnce(losId) ?: return
        losDao.update(los.copy(difficulty = difficulty.coerceIn(1, 5)))
    }

    suspend fun updateConfidence(losId: Long, confidence: Int) {
        val los = losDao.getLosByIdOnce(losId) ?: return
        losDao.update(los.copy(previousConfidence = los.confidence, confidence = confidence.coerceIn(0, 100)))
    }

    suspend fun recordStudyTouch(losId: Long, whenMillis: Long) {
        val los = losDao.getLosByIdOnce(losId) ?: return
        val newStatus = if (los.status == LosStatus.NOT_STARTED) LosStatus.IN_PROGRESS else los.status
        losDao.update(los.copy(lastStudiedAt = whenMillis, status = newStatus))
    }

    private suspend fun resolveCourse(name: String, cache: MutableMap<String, CourseEntity>): CourseEntity {
        val key = name.lowercase()
        cache[key]?.let { return it }
        val existing = getAllCoursesOnce().find { it.name.equals(name, ignoreCase = true) }
        val course = existing ?: run {
            val id = courseDao.insert(CourseEntity(name = name))
            courseDao.getCourseByIdOnce(id)!!
        }
        cache[key] = course
        return course
    }

    private suspend fun resolveModule(
        courseId: Long,
        name: String,
        aliases: String?,
        cache: MutableMap<Pair<Long, String>, ModuleEntity>
    ): Pair<ModuleEntity, Boolean> {
        val key = courseId to name.lowercase()
        cache[key]?.let { return it to false }
        val existing = moduleDao.findByExactName(courseId, name)
        if (existing != null) {
            cache[key] = existing
            return existing to false
        }
        val id = moduleDao.insert(ModuleEntity(courseId = courseId, name = name, aliases = aliases))
        val created = moduleDao.getModuleByIdOnce(id)!!
        cache[key] = created
        return created to true
    }

    private suspend fun resolveTopic(
        moduleId: Long,
        name: String,
        topicNumber: Int?,
        cache: MutableMap<Pair<Long, String>, TopicEntity>
    ): Pair<TopicEntity, Boolean> {
        val key = moduleId to name.lowercase()
        cache[key]?.let { return it to false }
        val existing = topicDao.findByExactName(moduleId, name)
        if (existing != null) {
            cache[key] = existing
            return existing to false
        }
        val id = topicDao.insert(TopicEntity(moduleId = moduleId, name = name, topicNumber = topicNumber))
        val created = topicDao.getTopicByIdOnce(id)!!
        cache[key] = created
        return created to true
    }

    /**
     * Imports/updates the hierarchy from a CSV file (spec section 4).
     * Expected header columns (case-insensitive, any order):
     * course_name, subject_name, subject_aliases, reading_name,
     * reading_number, los_code, los_title, los_description,
     * estimated_minutes.
     * Matching is by exact name within the parent scope so re-importing
     * the same file updates existing rows instead of duplicating them.
     */
    suspend fun importCsv(csvContent: String): ImportResult {
        val rows = parseCsv(csvContent)
        var modulesCreated = 0
        var topicsCreated = 0
        var losCreated = 0
        var losUpdated = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        val courseCache = mutableMapOf<String, CourseEntity>()
        val moduleCache = mutableMapOf<Pair<Long, String>, ModuleEntity>()
        val topicCache = mutableMapOf<Pair<Long, String>, TopicEntity>()

        rows.forEachIndexed { index, row ->
            val courseName = row["course_name"]?.trim().orEmpty()
            val subjectName = row["subject_name"]?.trim().orEmpty()
            val readingName = row["reading_name"]?.trim().orEmpty()
            val losCode = row["los_code"]?.trim().orEmpty()
            val losTitle = row["los_title"]?.trim().orEmpty()

            if (courseName.isBlank() || subjectName.isBlank() || readingName.isBlank() || losCode.isBlank() || losTitle.isBlank()) {
                skipped++
                errors.add("Row ${index + 2}: missing a required column, skipped.")
                return@forEachIndexed
            }

            val course = resolveCourse(courseName, courseCache)
            val (module, moduleWasCreated) = resolveModule(course.id, subjectName, row["subject_aliases"]?.trim(), moduleCache)
            if (moduleWasCreated) modulesCreated++
            val (topic, topicWasCreated) = resolveTopic(module.id, readingName, row["reading_number"]?.trim()?.toIntOrNull(), topicCache)
            if (topicWasCreated) topicsCreated++

            val estimatedMinutes = row["estimated_minutes"]?.trim()?.toIntOrNull() ?: 30
            val existingLos = losDao.findByCode(topic.id, losCode)
            if (existingLos != null) {
                losDao.update(
                    existingLos.copy(
                        title = losTitle,
                        description = row["los_description"]?.trim()?.ifBlank { null } ?: existingLos.description,
                        estimatedMinutes = estimatedMinutes
                    )
                )
                losUpdated++
            } else {
                losDao.insert(
                    LearningOutcomeEntity(
                        topicId = topic.id,
                        code = losCode,
                        title = losTitle,
                        description = row["los_description"]?.trim()?.ifBlank { null },
                        estimatedMinutes = estimatedMinutes
                    )
                )
                losCreated++
            }
        }

        return ImportResult(modulesCreated, topicsCreated, losCreated, losUpdated, skipped, errors)
    }
}
