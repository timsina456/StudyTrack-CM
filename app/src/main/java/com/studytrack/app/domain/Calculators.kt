package com.studytrack.app.domain

import com.studytrack.app.data.local.LearningOutcomeEntity
import com.studytrack.app.data.local.LosStatus
import com.studytrack.app.data.local.ModuleEntity
import com.studytrack.app.data.local.TopicEntity

enum class PaceStatus { AHEAD, ON_TRACK, BEHIND, SIGNIFICANTLY_BEHIND }

data class CourseProjection(
    val remainingLosCount: Int,
    val estimatedRemainingMinutes: Int,
    val daysRemaining: Long,
    val requiredMinutesPerDay: Double,
    val requiredMinutesPerWeek: Double,
    val status: PaceStatus
)

data class WeakArea(val module: ModuleEntity, val averageConfidence: Double?, val averageMinutesPerLos: Double)

data class TodayPlanItem(val los: LearningOutcomeEntity, val topic: TopicEntity, val minutesSuggested: Int, val reason: String)

/**
 * All functions here are pure (no DB/IO access) so the projection math in
 * particular — spec section 7's "X LOS remaining, Y hours left, Z
 * required per day" — is easy to read, test and trust. Callers fetch the
 * data first, then hand it to these.
 */
object Calculators {

    fun courseProgressPercent(losList: List<LearningOutcomeEntity>): Double {
        if (losList.isEmpty()) return 0.0
        val completed = losList.count { it.status == LosStatus.COMPLETED }
        return completed * 100.0 / losList.size
    }

    fun remainingMinutesForLos(los: LearningOutcomeEntity, actualMinutes: Int): Int =
        (los.estimatedMinutes - actualMinutes).coerceAtLeast(0)

    /**
     * [recentDailyPaceMinutes] should be the user's actual average daily
     * study time over a recent window (Dashboard passes this week's
     * average) — it's a parameter rather than computed here so this
     * function stays pure and easy to verify independently.
     */
    fun computeCourseProjection(
        incompleteLos: List<LearningOutcomeEntity>,
        actualMinutesByLos: Map<Long, Int>,
        targetDateMillis: Long?,
        nowMillis: Long,
        recentDailyPaceMinutes: Double
    ): CourseProjection {
        val remainingMinutes = incompleteLos.sumOf { los -> remainingMinutesForLos(los, actualMinutesByLos[los.id] ?: 0) }
        val daysRemaining = if (targetDateMillis != null) {
            ((targetDateMillis - nowMillis) / 86_400_000L).coerceAtLeast(0)
        } else 0L

        val requiredPerDay = if (daysRemaining > 0) remainingMinutes.toDouble() / daysRemaining else remainingMinutes.toDouble()
        val requiredPerWeek = requiredPerDay * 7

        val status = when {
            targetDateMillis == null -> PaceStatus.ON_TRACK
            requiredPerDay <= 0.0 -> PaceStatus.AHEAD
            recentDailyPaceMinutes >= requiredPerDay * 1.1 -> PaceStatus.AHEAD
            recentDailyPaceMinutes >= requiredPerDay * 0.9 -> PaceStatus.ON_TRACK
            recentDailyPaceMinutes >= requiredPerDay * 0.6 -> PaceStatus.BEHIND
            else -> PaceStatus.SIGNIFICANTLY_BEHIND
        }

        return CourseProjection(incompleteLos.size, remainingMinutes, daysRemaining, requiredPerDay, requiredPerWeek, status)
    }

    fun findWeakAreas(
        modules: List<ModuleEntity>,
        losByModule: Map<Long, List<LearningOutcomeEntity>>,
        minutesByModule: Map<Long, Int>
    ): List<WeakArea> {
        return modules.mapNotNull { module ->
            val los = losByModule[module.id].orEmpty()
            if (los.isEmpty()) return@mapNotNull null
            val confidences = los.mapNotNull { it.confidence }
            val avgConfidence = if (confidences.isNotEmpty()) confidences.average() else null
            val completedCount = los.count { it.status == LosStatus.COMPLETED }.coerceAtLeast(1)
            val avgMinutesPerLos = (minutesByModule[module.id] ?: 0).toDouble() / completedCount
            WeakArea(module, avgConfidence, avgMinutesPerLos)
        }.sortedWith(compareBy { it.averageConfidence ?: 0.0 }).take(5)
    }

    /**
     * A simple, explainable heuristic: highest-priority / most-overdue
     * incomplete LOS first, capped to the day's remaining time budget.
     * Full adaptive planning (spec section 40, historical-speed-aware)
     * is Phase 2 — this stays transparent on purpose rather than clever.
     */
    fun generateTodayPlan(
        incompleteLos: List<LearningOutcomeEntity>,
        topicById: Map<Long, TopicEntity>,
        remainingMinutesToday: Int
    ): List<TodayPlanItem> {
        if (remainingMinutesToday <= 0) return emptyList()
        val sorted = incompleteLos.sortedWith(
            compareBy(
                { it.targetDate ?: Long.MAX_VALUE },
                { -(it.difficulty ?: 0) },
                { it.orderIndex }
            )
        )
        val plan = mutableListOf<TodayPlanItem>()
        var budget = remainingMinutesToday
        for (los in sorted) {
            if (budget <= 0) break
            val topic = topicById[los.topicId] ?: continue
            val suggested = los.estimatedMinutes.coerceAtMost(budget).coerceAtLeast(15)
            val reason = if (los.targetDate != null && los.targetDate < System.currentTimeMillis()) "Overdue" else "Next in sequence"
            plan.add(TodayPlanItem(los, topic, suggested, reason))
            budget -= suggested
        }
        return plan
    }
}
