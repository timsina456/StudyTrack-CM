package com.studytrack.app.domain

import com.studytrack.app.data.local.CourseEntity
import com.studytrack.app.data.local.LosStatus
import com.studytrack.app.data.local.LearningOutcomeEntity
import com.studytrack.app.data.local.ModuleEntity
import com.studytrack.app.data.local.TopicEntity
import com.studytrack.app.data.repository.HierarchyRepository
import com.studytrack.app.data.repository.PlanningRepository
import com.studytrack.app.data.repository.SessionRepository
import com.studytrack.app.util.formatDateShort
import com.studytrack.app.util.formatMinutes
import com.studytrack.app.util.startOfWeekMillis
import com.studytrack.app.util.endOfWeekMillis
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class CommandIntent {
    START, PAUSE, RESUME, STOP, LOG_TIME, COMPLETE_LOS, MARK_DIFFICULT,
    SCHEDULE_REVISION, QUERY_TIME_SPENT, QUERY_INCOMPLETE, QUERY_WEAK_AREAS,
    QUERY_TODAY_PLAN, HELP, UNKNOWN
}

data class ResolvedEntity(
    val course: CourseEntity? = null,
    val module: ModuleEntity? = null,
    val topic: TopicEntity? = null,
    val los: LearningOutcomeEntity? = null
) {
    val isEmpty: Boolean get() = course == null && module == null && topic == null && los == null
    fun label(): String = listOfNotNull(
        course?.name, module?.name, topic?.name, los?.let { "${it.code} \u2013 ${it.title}" }
    ).joinToString(" \u2192 ")
}

data class ClarificationOption(val label: String, val entity: ResolvedEntity)

sealed interface ParsedCommand {
    val rawText: String

    data class Resolved(
        val intent: CommandIntent,
        val entity: ResolvedEntity,
        val durationMinutes: Int? = null,
        val dateMillis: Long? = null,
        override val rawText: String
    ) : ParsedCommand

    data class NeedsClarification(
        val intent: CommandIntent,
        val question: String,
        val options: List<ClarificationOption>,
        override val rawText: String
    ) : ParsedCommand

    data class Unrecognized(override val rawText: String) : ParsedCommand
}

/**
 * Small, deterministic, fully offline rule-based parser (spec section 3:
 * basic commands must work with zero external AI call). Scoring is a
 * transparent containment/overlap function rather than a black box, so
 * behaviour stays predictable — see spec section 45 for how an external
 * AI API could later be layered on top without this parser changing.
 */
class CommandParser(private val hierarchyRepository: HierarchyRepository) {

    private val durationRegex = Regex("""(\d+)\s*(hours?|hrs?|h)\b|(\d+)\s*(minutes?|mins?|m)\b""", RegexOption.IGNORE_CASE)
    private val losCodeRegex = Regex("""\b(\d{1,3}\.[a-zA-Z])\b""")
    private val readingNumberRegex = Regex("""reading\s+(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val relativeDateRegex = Regex("""next\s+\w+|tomorrow|today""", RegexOption.IGNORE_CASE)

    suspend fun parse(rawText: String): ParsedCommand {
        val text = rawText.trim()
        if (text.isEmpty()) return ParsedCommand.Unrecognized(rawText)
        val lower = text.lowercase(Locale.getDefault())

        val intent = detectIntent(lower) ?: return ParsedCommand.Unrecognized(rawText)

        if (intent in NO_ENTITY_INTENTS) {
            return ParsedCommand.Resolved(intent, ResolvedEntity(), rawText = rawText)
        }

        val durationMinutes = extractDurationMinutes(lower)
        val dateMillis = extractDateMillis(lower)

        var remainder = lower.replace(durationRegex, " ").replace(relativeDateRegex, " ")
        remainder = stripIntentKeywords(remainder)

        val courses = hierarchyRepository.getAllCoursesOnce()
        val modules = hierarchyRepository.getAllModulesOnce()
        val topics = hierarchyRepository.getAllTopicsOnce()
        val losList = hierarchyRepository.getAllLosOnce()

        // Direct LOS-code reference, e.g. "LOS 28.a" / "I completed LOS 28.a".
        val losCodeMatch = losCodeRegex.find(remainder)
        if (losCodeMatch != null) {
            val code = losCodeMatch.groupValues[1]
            val candidates = losList.filter { it.code.equals(code, ignoreCase = true) }
            when {
                candidates.size == 1 -> {
                    val los = candidates.first()
                    val topic = topics.find { it.id == los.topicId }
                    val module = modules.find { it.id == topic?.moduleId }
                    val course = courses.find { it.id == module?.courseId }
                    return ParsedCommand.Resolved(intent, ResolvedEntity(course, module, topic, los), durationMinutes, dateMillis, rawText)
                }
                candidates.size > 1 -> {
                    return clarification(intent, candidates.take(4).map { los ->
                        val topic = topics.find { it.id == los.topicId }
                        val module = modules.find { it.id == topic?.moduleId }
                        val course = courses.find { it.id == module?.courseId }
                        val entity = ResolvedEntity(course, module, topic, los)
                        ClarificationOption(entity.label(), entity)
                    }, rawText, "Which \"$code\"?")
                }
            }
        }

        // Direct reading-number reference, e.g. "Start CFA Reading 28".
        val readingMatch = readingNumberRegex.find(lower)
        if (readingMatch != null) {
            val number = readingMatch.groupValues[1].toIntOrNull()
            val candidates = topics.filter { it.topicNumber == number }
            when {
                candidates.size == 1 -> {
                    val topic = candidates.first()
                    val module = modules.find { it.id == topic.moduleId }
                    val course = courses.find { it.id == module?.courseId }
                    return ParsedCommand.Resolved(intent, ResolvedEntity(course, module, topic, null), durationMinutes, dateMillis, rawText)
                }
                candidates.size > 1 -> {
                    return clarification(intent, candidates.take(4).map { topic ->
                        val module = modules.find { it.id == topic.moduleId }
                        val course = courses.find { it.id == module?.courseId }
                        val entity = ResolvedEntity(course, module, topic, null)
                        ClarificationOption(entity.label(), entity)
                    }, rawText, "Which reading?")
                }
            }
        }

        val cleaned = remainder.replace(losCodeRegex, " ").replace(readingNumberRegex, " ").trim()

        if (cleaned.isBlank() && intent in REQUIRES_SUBJECT_INTENTS) {
            return ParsedCommand.Unrecognized(rawText)
        }

        val courseMatches = scoreAll(cleaned, courses.map { it.id to listOf(it.name) })
        val moduleMatches = scoreAll(cleaned, modules.map { it.id to listOfNotNull(it.name, it.aliases) })
        val topicMatches = scoreAll(cleaned, topics.map { it.id to listOf(it.name) })

        val sortedModules = moduleMatches.filter { it.second >= 55 }.sortedByDescending { it.second }
        if (sortedModules.size > 1 && sortedModules[0].second - sortedModules[1].second < 15) {
            return clarification(intent, sortedModules.take(4).map { (id, _) ->
                val module = modules.find { it.id == id }!!
                val course = courses.find { it.id == module.courseId }
                val entity = ResolvedEntity(course, module, null, null)
                ClarificationOption(entity.label(), entity)
            }, rawText, "Which one?")
        }

        val bestTopic = topicMatches.maxByOrNull { it.second }
        val bestModule = moduleMatches.maxByOrNull { it.second }
        val bestCourse = courseMatches.maxByOrNull { it.second }

        val resolvedTopic = if (bestTopic != null && bestTopic.second >= 60) topics.find { it.id == bestTopic.first } else null
        val resolvedModule = resolvedTopic?.let { t -> modules.find { it.id == t.moduleId } }
            ?: (if (bestModule != null && bestModule.second >= 55) modules.find { it.id == bestModule.first } else null)
        val resolvedCourse = resolvedModule?.let { m -> courses.find { it.id == m.courseId } }
            ?: (if (bestCourse != null && bestCourse.second >= 55) courses.find { it.id == bestCourse.first } else null)

        val entity = ResolvedEntity(resolvedCourse, resolvedModule, resolvedTopic, null)

        if (entity.isEmpty && intent in REQUIRES_SUBJECT_INTENTS) {
            return ParsedCommand.Unrecognized(rawText)
        }

        return ParsedCommand.Resolved(intent, entity, durationMinutes, dateMillis, rawText)
    }

    private fun clarification(intent: CommandIntent, options: List<ClarificationOption>, rawText: String, question: String) =
        ParsedCommand.NeedsClarification(intent, question, options, rawText)

    private fun detectIntent(lower: String): CommandIntent? = when {
        lower.startsWith("pause") -> CommandIntent.PAUSE
        lower.startsWith("resume") || lower.startsWith("continue") -> CommandIntent.RESUME
        lower.startsWith("stop") || lower.startsWith("finish") || lower == "done" -> CommandIntent.STOP
        lower.contains("i studied") || lower.contains("i spent") || lower.startsWith("log ") -> CommandIntent.LOG_TIME
        lower.contains("mark") && (lower.contains("difficult") || lower.contains("hard")) -> CommandIntent.MARK_DIFFICULT
        lower.contains("complete") -> CommandIntent.COMPLETE_LOS
        lower.contains("schedule") && lower.contains("revis") -> CommandIntent.SCHEDULE_REVISION
        lower.contains("weakest") || lower.contains("weak area") || lower.contains("struggling") -> CommandIntent.QUERY_WEAK_AREAS
        lower.contains("not completed") || lower.contains("incomplete") || lower.contains("remaining los") -> CommandIntent.QUERY_INCOMPLETE
        lower.contains("what should i study") || lower.contains("today's plan") || lower.contains("plan today") -> CommandIntent.QUERY_TODAY_PLAN
        lower.startsWith("how much") || lower.startsWith("how many hours") || lower.contains("time have i spent") -> CommandIntent.QUERY_TIME_SPENT
        lower.startsWith("start") || lower.startsWith("begin") -> CommandIntent.START
        lower == "help" || lower.contains("what can you do") -> CommandIntent.HELP
        else -> null
    }

    private val stripPhrases = listOf(
        "start studying", "start", "begin", "i studied", "i spent", "log", "mark this", "mark",
        "as difficult", "as hard", "difficult", "hard", "i completed", "complete", "finished",
        "schedule", "for revision", "revision", "revise", "how much have i studied", "how much",
        "how many hours have i studied", "time have i spent on", "have i spent on", "have i studied",
        "this week", "for", "on"
    )

    private fun stripIntentKeywords(text: String): String {
        var result = text
        for (phrase in stripPhrases) {
            result = result.replace(Regex("\\b${Regex.escape(phrase)}\\b"), " ")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun extractDurationMinutes(text: String): Int? {
        var total = 0
        var found = false
        for (m in durationRegex.findAll(text)) {
            m.groupValues[1].toIntOrNull()?.let { total += it * 60; found = true }
            m.groupValues[3].toIntOrNull()?.let { total += it; found = true }
        }
        return if (found) total else null
    }

    private fun extractDateMillis(text: String): Long? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val target: LocalDate? = when {
            text.contains("tomorrow") -> today.plusDays(1)
            text.contains("today") -> today
            else -> {
                val dayNames = mapOf(
                    "sunday" to DayOfWeek.SUNDAY, "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
                    "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
                    "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY
                )
                dayNames.entries.firstOrNull { text.contains(it.key) }?.let { (_, dow) ->
                    var d = today.plusDays(1)
                    while (d.dayOfWeek != dow) d = d.plusDays(1)
                    d
                }
            }
        }
        return target?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
    }

    private fun scoreAll(query: String, candidates: List<Pair<Long, List<String>>>): List<Pair<Long, Int>> {
        if (query.isBlank()) return emptyList()
        return candidates.mapNotNull { (id, names) ->
            val best = names.filter { it.isNotBlank() }.maxOfOrNull { matchScore(query, it.lowercase(Locale.getDefault())) }
            if (best != null && best > 0) id to best else null
        }
    }

    /** Simple, explainable containment/overlap scoring \u2014 no external library, no network. */
    private fun matchScore(query: String, candidate: String): Int {
        val q = query.trim()
        val c = candidate.trim()
        if (q.isBlank() || c.isBlank()) return 0
        if (c == q) return 100
        if (c.startsWith(q) || q.startsWith(c)) return 80
        if (c.contains(q) || q.contains(c)) return 65
        val qTokens = q.split(" ").filter { it.length > 2 }.toSet()
        val cTokens = c.split(" ").filter { it.length > 2 }.toSet()
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0
        val overlap = qTokens.intersect(cTokens).size
        if (overlap == 0) return 0
        return (overlap.toDouble() / qTokens.union(cTokens).size * 50).toInt()
    }

    companion object {
        private val NO_ENTITY_INTENTS = setOf(
            CommandIntent.PAUSE, CommandIntent.RESUME, CommandIntent.STOP,
            CommandIntent.QUERY_TODAY_PLAN, CommandIntent.QUERY_WEAK_AREAS,
            CommandIntent.QUERY_INCOMPLETE, CommandIntent.HELP
        )
        private val REQUIRES_SUBJECT_INTENTS = setOf(CommandIntent.START, CommandIntent.LOG_TIME)
    }
}

/**
 * Executes an already-resolved command. Kept separate from [CommandParser]
 * so parsing (pure text \u2192 structure) and execution (repository writes)
 * stay independently testable, and so both the Dashboard's quick command
 * bar and the full Command Center can share one implementation.
 */
class CommandExecutor(
    private val hierarchyRepository: HierarchyRepository,
    private val sessionRepository: SessionRepository,
    private val planningRepository: PlanningRepository
) {
    suspend fun execute(command: ParsedCommand.Resolved): String {
        val entity = command.entity
        return when (command.intent) {
            CommandIntent.START -> {
                val course = entity.course
                val module = entity.module
                if (course == null || module == null) {
                    "Couldn't tell what to start \u2014 try including a subject, e.g. \"Start CFA Ethics.\""
                } else {
                    sessionRepository.start(course.id, module.id, entity.topic?.id, entity.los?.id)
                    "Started: ${entity.label()}"
                }
            }
            CommandIntent.PAUSE -> {
                sessionRepository.pause()
                "Paused."
            }
            CommandIntent.RESUME -> {
                sessionRepository.resume()
                "Resumed."
            }
            CommandIntent.STOP -> {
                val record = sessionRepository.finish()
                if (record != null) "Logged ${formatMinutes(record.durationMinutes)}." else "Nothing was running."
            }
            CommandIntent.LOG_TIME -> {
                val course = entity.course
                val module = entity.module
                val minutes = command.durationMinutes
                if (course == null || module == null || minutes == null) {
                    "Tell me the subject and how long, e.g. \"I studied FSA for 1 hour.\""
                } else {
                    sessionRepository.logManualTime(course.id, module.id, entity.topic?.id, entity.los?.id, minutes)
                    "Logged ${formatMinutes(minutes)} on ${entity.label()}."
                }
            }
            CommandIntent.COMPLETE_LOS -> {
                val losId = entity.los?.id ?: sessionRepository.getActiveSessionOnce()?.losId
                if (losId == null) {
                    "Which LOS? Try including its code, e.g. \"I completed LOS 28.a.\""
                } else {
                    planningRepository.completeLosAndScheduleFirstRevision(losId)
                    "Marked complete. First revision scheduled in 1 day."
                }
            }
            CommandIntent.MARK_DIFFICULT -> {
                val losId = entity.los?.id ?: sessionRepository.getActiveSessionOnce()?.losId
                if (losId == null) {
                    "Which LOS should I mark as difficult?"
                } else {
                    hierarchyRepository.setDifficulty(losId, 4)
                    "Marked as difficult."
                }
            }
            CommandIntent.SCHEDULE_REVISION -> {
                val losId = entity.los?.id ?: sessionRepository.getActiveSessionOnce()?.losId
                if (losId == null) {
                    "Which LOS should I schedule for revision?"
                } else {
                    val date = command.dateMillis ?: (System.currentTimeMillis() + 7L * 86_400_000L)
                    planningRepository.scheduleCustomRevision(losId, date)
                    "Revision scheduled for ${formatDateShort(date)}."
                }
            }
            CommandIntent.QUERY_TIME_SPENT -> {
                val from = startOfWeekMillis()
                val to = endOfWeekMillis()
                val minutes = when {
                    entity.module != null -> sessionRepository.totalMinutesForModule(entity.module.id)
                    entity.course != null -> sessionRepository.totalMinutesForCourseBetween(entity.course.id, from, to)
                    else -> sessionRepository.totalMinutesBetween(from, to)
                }
                val scope = entity.module?.name ?: entity.course?.name ?: "total"
                "${formatMinutes(minutes)} on $scope this week."
            }
            CommandIntent.QUERY_INCOMPLETE -> {
                val allLos = hierarchyRepository.getAllLosOnce()
                val allTopics = hierarchyRepository.getAllTopicsOnce()
                val scopeModuleIds = entity.course?.let { c ->
                    hierarchyRepository.getAllModulesOnce().filter { it.courseId == c.id }.map { it.id }.toSet()
                }
                val incomplete = allLos.filter { it.status != LosStatus.COMPLETED }.filter { los ->
                    scopeModuleIds == null || allTopics.find { it.id == los.topicId }?.moduleId in scopeModuleIds
                }
                if (incomplete.isEmpty()) "Nothing outstanding \u2014 everything's marked complete."
                else "${incomplete.size} not completed: " +
                    incomplete.take(5).joinToString(", ") { "${it.code} ${it.title}" } +
                    if (incomplete.size > 5) ", and ${incomplete.size - 5} more." else "."
            }
            CommandIntent.QUERY_WEAK_AREAS -> {
                val modules = hierarchyRepository.getAllModulesOnce()
                val topics = hierarchyRepository.getAllTopicsOnce()
                val allLos = hierarchyRepository.getAllLosOnce()
                val losByModule = allLos.groupBy { los -> topics.find { it.id == los.topicId }?.moduleId }
                    .filterKeys { it != null }.mapKeys { it.key!! }
                val minutesByModule = modules.associate { it.id to sessionRepository.totalMinutesForModule(it.id) }
                val weak = Calculators.findWeakAreas(modules, losByModule, minutesByModule)
                if (weak.isEmpty()) "Not enough data yet to identify weak areas."
                else "Weakest areas: " + weak.joinToString("; ") { w ->
                    w.module.name + (w.averageConfidence?.let { " (confidence ${it.toInt()}%)" } ?: "")
                }
            }
            CommandIntent.QUERY_TODAY_PLAN -> {
                val incomplete = hierarchyRepository.getAllLosOnce().filter { it.status != LosStatus.COMPLETED }
                val topicsById = hierarchyRepository.getAllTopicsOnce().associateBy { it.id }
                val studiedToday = sessionRepository.totalMinutesToday()
                val remaining = (120 - studiedToday).coerceAtLeast(0)
                val plan = Calculators.generateTodayPlan(incomplete, topicsById, remaining)
                if (plan.isEmpty()) "Nothing scheduled \u2014 you may already be caught up for today."
                else plan.joinToString("; ") { "${it.los.code} ${it.los.title} (${formatMinutes(it.minutesSuggested)})" }
            }
            CommandIntent.HELP -> HELP_TEXT
            CommandIntent.UNKNOWN -> "I didn't catch that. Try \"help\" for example commands."
        }
    }

    companion object {
        const val HELP_TEXT = "Try: \"Start CFA Ethics\", \"Pause\", \"Resume\", \"Stop\", " +
            "\"I studied FSA for 1 hour\", \"I completed LOS 28.a\", \"Mark this as difficult\", " +
            "\"Schedule LOS 28.a for revision next Sunday\", \"How much have I studied this week?\", " +
            "\"What LOS have I not completed?\", \"Show me my weakest areas\", \"What should I study today?\""
    }
}
