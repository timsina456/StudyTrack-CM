package com.studytrack.app.data.repository

import com.studytrack.app.data.local.ActiveSessionDao
import com.studytrack.app.data.local.ActiveSessionEntity
import com.studytrack.app.data.local.LearningOutcomeDao
import com.studytrack.app.data.local.LosStatus
import com.studytrack.app.data.local.SessionSource
import com.studytrack.app.data.local.SessionStatus
import com.studytrack.app.data.local.StudySessionDao
import com.studytrack.app.data.local.StudySessionEntity
import com.studytrack.app.util.endOfDayMillis
import com.studytrack.app.util.endOfWeekMillis
import com.studytrack.app.util.startOfDayMillis
import com.studytrack.app.util.startOfWeekMillis
import kotlinx.coroutines.flow.Flow

class SessionRepository(
    private val activeSessionDao: ActiveSessionDao,
    private val studySessionDao: StudySessionDao,
    private val losDao: LearningOutcomeDao
) {
    fun observeActiveSession(): Flow<ActiveSessionEntity?> = activeSessionDao.observe()
    suspend fun getActiveSessionOnce(): ActiveSessionEntity? = activeSessionDao.getOnce()

    fun observeHistory(): Flow<List<StudySessionEntity>> = studySessionDao.getAllSessions()
    fun observeHistoryBetween(from: Long, to: Long): Flow<List<StudySessionEntity>> = studySessionDao.getSessionsBetween(from, to)
    fun observeSessionsForLos(losId: Long): Flow<List<StudySessionEntity>> = studySessionDao.getSessionsForLos(losId)

    suspend fun start(courseId: Long, moduleId: Long, topicId: Long?, losId: Long?, note: String? = null) {
        // Starting something new implicitly closes out whatever was
        // running before, so a session can never be silently overwritten
        // and lose unrecorded time (spec section 48).
        finishActive(rating = null, confidenceAtEnd = null, notes = null)
        val now = System.currentTimeMillis()
        activeSessionDao.upsert(
            ActiveSessionEntity(
                courseId = courseId, moduleId = moduleId, topicId = topicId, losId = losId,
                startTime = now, lastResumeTime = now, accumulatedMillis = 0,
                status = SessionStatus.RUNNING, note = note
            )
        )
    }

    suspend fun pause() {
        val session = activeSessionDao.getOnce() ?: return
        if (session.status != SessionStatus.RUNNING) return
        val now = System.currentTimeMillis()
        activeSessionDao.upsert(
            session.copy(
                accumulatedMillis = session.accumulatedMillis + (now - session.lastResumeTime),
                status = SessionStatus.PAUSED
            )
        )
    }

    suspend fun resume() {
        val session = activeSessionDao.getOnce() ?: return
        if (session.status != SessionStatus.PAUSED) return
        activeSessionDao.upsert(session.copy(lastResumeTime = System.currentTimeMillis(), status = SessionStatus.RUNNING))
    }

    suspend fun switchTopic(courseId: Long, moduleId: Long, topicId: Long?, losId: Long?, note: String? = null) {
        start(courseId, moduleId, topicId, losId, note)
    }

    /** Ends the active session and files it as history. Returns null if nothing was running. */
    suspend fun finish(productivityRating: Int? = null, confidenceAtEnd: Int? = null, notes: String? = null): StudySessionEntity? =
        finishActive(productivityRating, confidenceAtEnd, notes)

    private suspend fun finishActive(rating: Int?, confidenceAtEnd: Int?, notes: String?): StudySessionEntity? {
        val session = activeSessionDao.getOnce() ?: return null
        val now = System.currentTimeMillis()
        val elapsedMs = session.elapsedMillis(now)
        activeSessionDao.clear()
        if (elapsedMs < 1000) return null // ignore accidental sub-second sessions

        val record = StudySessionEntity(
            courseId = session.courseId,
            moduleId = session.moduleId,
            topicId = session.topicId,
            losId = session.losId,
            startTime = session.startTime,
            endTime = now,
            durationMinutes = (elapsedMs / 60000).toInt().coerceAtLeast(1),
            productivityRating = rating,
            confidenceAtEnd = confidenceAtEnd,
            sessionNotes = notes ?: session.note,
            source = SessionSource.TIMER
        )
        studySessionDao.insert(record)

        session.losId?.let { losId ->
            val los = losDao.getLosByIdOnce(losId)
            if (los != null) {
                val newStatus = if (los.status == LosStatus.NOT_STARTED) LosStatus.IN_PROGRESS else los.status
                losDao.update(
                    los.copy(
                        lastStudiedAt = now,
                        status = newStatus,
                        confidence = confidenceAtEnd ?: los.confidence,
                        previousConfidence = if (confidenceAtEnd != null) los.confidence else los.previousConfidence
                    )
                )
            }
        }
        return record
    }

    suspend fun logManualTime(
        courseId: Long, moduleId: Long, topicId: Long?, losId: Long?, minutes: Int, notes: String? = null
    ): StudySessionEntity {
        val now = System.currentTimeMillis()
        val start = now - minutes * 60_000L
        val record = StudySessionEntity(
            courseId = courseId, moduleId = moduleId, topicId = topicId, losId = losId,
            startTime = start, endTime = now, durationMinutes = minutes,
            sessionNotes = notes, source = SessionSource.MANUAL
        )
        studySessionDao.insert(record)
        losId?.let {
            val los = losDao.getLosByIdOnce(it)
            if (los != null) {
                val newStatus = if (los.status == LosStatus.NOT_STARTED) LosStatus.IN_PROGRESS else los.status
                losDao.update(los.copy(lastStudiedAt = now, status = newStatus))
            }
        }
        return record
    }

    suspend fun totalMinutesToday(): Int = studySessionDao.getTotalMinutesBetween(startOfDayMillis(), endOfDayMillis())
    suspend fun totalMinutesThisWeek(): Int = studySessionDao.getTotalMinutesBetween(startOfWeekMillis(), endOfWeekMillis())
    suspend fun totalMinutesBetween(from: Long, to: Long): Int = studySessionDao.getTotalMinutesBetween(from, to)
    suspend fun totalMinutesForCourseBetween(courseId: Long, from: Long, to: Long): Int =
        studySessionDao.getTotalMinutesForCourseBetween(courseId, from, to)
    suspend fun totalMinutesForModule(moduleId: Long): Int = studySessionDao.getTotalMinutesForModule(moduleId)
    suspend fun totalMinutesForLos(losId: Long): Int = studySessionDao.getTotalMinutesForLos(losId)
    suspend fun sessionCountToday(): Int = studySessionDao.getSessionCountBetween(startOfDayMillis(), endOfDayMillis())
    suspend fun sessionCountThisWeek(): Int = studySessionDao.getSessionCountBetween(startOfWeekMillis(), endOfWeekMillis())
}
