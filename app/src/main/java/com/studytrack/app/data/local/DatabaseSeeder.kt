package com.studytrack.app.data.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class SeedCourse(
    val name: String,
    val courseType: String = "Certification",
    val targetDate: String? = null,
    val priority: Int = 3,
    val description: String? = null,
    val subjectLabel: String = "Subject",
    val topicLabel: String = "Topic",
    val losLabel: String = "Learning Outcome"
)

@Serializable
data class SeedLos(val code: String, val title: String, val estimatedMinutes: Int = 30)

@Serializable
data class SeedTopic(val name: String, val topicNumber: Int? = null, val los: List<SeedLos> = emptyList())

@Serializable
data class SeedModule(val name: String, val aliases: String? = null, val topics: List<SeedTopic> = emptyList())

@Serializable
data class SeedRoot(val course: SeedCourse, val modules: List<SeedModule>)

/**
 * Populates a fresh install with the bundled sample CFA Level I subset
 * (assets/cfa_level1_seed.json) so every Phase 1 screen has real data to
 * show immediately. Only ever runs once — if any course already exists,
 * this is a no-op, so it never overwrites imported or user-entered data.
 */
object DatabaseSeeder {

    private fun isoDateToMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun seedIfEmpty(context: Context, database: StudyTrackDatabase) {
        if (database.courseDao().getCourseCount() > 0) return

        val json = Json { ignoreUnknownKeys = true }
        val text = context.assets.open("cfa_level1_seed.json").bufferedReader().use { it.readText() }
        val seed = json.decodeFromString(SeedRoot.serializer(), text)

        val courseId = database.courseDao().insert(
            CourseEntity(
                name = seed.course.name,
                courseType = seed.course.courseType,
                targetDate = isoDateToMillis(seed.course.targetDate),
                startDate = System.currentTimeMillis(),
                priority = seed.course.priority,
                description = seed.course.description,
                subjectLabel = seed.course.subjectLabel,
                topicLabel = seed.course.topicLabel,
                losLabel = seed.course.losLabel,
                usesLearningOutcomes = true
            )
        )

        seed.modules.forEachIndexed { moduleIndex, seedModule ->
            val moduleId = database.moduleDao().insert(
                ModuleEntity(courseId = courseId, name = seedModule.name, aliases = seedModule.aliases, orderIndex = moduleIndex)
            )
            seedModule.topics.forEachIndexed { topicIndex, seedTopic ->
                val topicEstimate = seedTopic.los.sumOf { it.estimatedMinutes }
                val topicId = database.topicDao().insert(
                    TopicEntity(
                        moduleId = moduleId,
                        name = seedTopic.name,
                        topicNumber = seedTopic.topicNumber,
                        orderIndex = topicIndex,
                        estimatedMinutes = topicEstimate
                    )
                )
                seedTopic.los.forEachIndexed { losIndex, seedLos ->
                    database.learningOutcomeDao().insert(
                        LearningOutcomeEntity(
                            topicId = topicId,
                            code = seedLos.code,
                            title = seedLos.title,
                            estimatedMinutes = seedLos.estimatedMinutes,
                            orderIndex = losIndex
                        )
                    )
                }
            }
        }

        // Starter goals so the Dashboard and Plan tab aren't empty on
        // first launch — both are computed from real LOS/session data
        // from this point forward, nothing here is a hard-coded stat.
        database.goalDao().insert(
            GoalEntity(
                courseId = courseId,
                title = "${seed.course.name} reading complete",
                goalType = GoalType.COURSE_COMPLETION,
                targetDate = isoDateToMillis(seed.course.targetDate),
                priority = 1
            )
        )
        database.goalDao().insert(
            GoalEntity(
                courseId = null,
                title = "Study 10 hours per week",
                goalType = GoalType.WEEKLY_HOURS,
                targetValue = 10.0,
                priority = 2
            )
        )
    }
}
