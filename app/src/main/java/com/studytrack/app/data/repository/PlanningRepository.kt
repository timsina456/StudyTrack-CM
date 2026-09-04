package com.studytrack.app.data.repository

import com.studytrack.app.data.local.GoalDao
import com.studytrack.app.data.local.GoalEntity
import com.studytrack.app.data.local.LearningOutcomeDao
import com.studytrack.app.data.local.LosStatus
import com.studytrack.app.data.local.RevisionDao
import com.studytrack.app.data.local.RevisionEntity
import com.studytrack.app.data.local.RevisionStatus
import com.studytrack.app.util.endOfDayMillis
import kotlinx.coroutines.flow.Flow

class PlanningRepository(
    private val goalDao: GoalDao,
    private val revisionDao: RevisionDao,
    private val losDao: LearningOutcomeDao
) {
    fun observeGoals(): Flow<List<GoalEntity>> = goalDao.getAllGoals()
    fun observeActiveGoals(): Flow<List<GoalEntity>> = goalDao.getActiveGoals()
    suspend fun addGoal(goal: GoalEntity): Long = goalDao.insert(goal)
    suspend fun updateGoal(goal: GoalEntity) = goalDao.update(goal)
    suspend fun deleteGoal(goal: GoalEntity) = goalDao.delete(goal)

    fun observeDueRevisions(): Flow<List<RevisionEntity>> = revisionDao.getDueRevisions(endOfDayMillis())
    fun observeAllRevisions(): Flow<List<RevisionEntity>> = revisionDao.getAllRevisions()
    fun observeRevisionsForLos(losId: Long): Flow<List<RevisionEntity>> = revisionDao.getRevisionsForLos(losId)

    // Default spaced-revision schedule (spec section 14): 1/3/7/14/30/60
    // days, chained automatically each time the previous one is completed.
    private val intervalSequence = listOf(
        1 to "1-day", 3 to "3-day", 7 to "7-day", 14 to "14-day", 30 to "30-day", 60 to "60-day"
    )

    suspend fun completeLosAndScheduleFirstRevision(losId: Long) {
        val los = losDao.getLosByIdOnce(losId) ?: return
        losDao.update(los.copy(status = LosStatus.COMPLETED))
        val (days, label) = intervalSequence.first()
        val scheduled = System.currentTimeMillis() + days * 86_400_000L
        revisionDao.insert(RevisionEntity(losId = losId, scheduledDate = scheduled, intervalLabel = label))
    }

    suspend fun scheduleCustomRevision(losId: Long, whenMillis: Long) {
        revisionDao.insert(RevisionEntity(losId = losId, scheduledDate = whenMillis, intervalLabel = "custom"))
    }

    suspend fun completeRevision(revisionId: Long) {
        val revision = revisionDao.getByIdOnce(revisionId) ?: return
        revisionDao.update(revision.copy(status = RevisionStatus.COMPLETED, completedAt = System.currentTimeMillis()))
        revision.losId?.let { losId ->
            val los = losDao.getLosByIdOnce(losId)
            if (los != null) losDao.update(los.copy(revisionCount = los.revisionCount + 1))
        }
        val currentIndex = intervalSequence.indexOfFirst { it.second == revision.intervalLabel }
        if (currentIndex in 0 until intervalSequence.size - 1) {
            val (days, label) = intervalSequence[currentIndex + 1]
            revisionDao.insert(
                RevisionEntity(
                    losId = revision.losId,
                    topicId = revision.topicId,
                    scheduledDate = System.currentTimeMillis() + days * 86_400_000L,
                    intervalLabel = label
                )
            )
        }
    }

    suspend fun skipRevision(revisionId: Long) {
        val revision = revisionDao.getByIdOnce(revisionId) ?: return
        revisionDao.update(revision.copy(status = RevisionStatus.SKIPPED))
    }

    suspend fun rescheduleRevision(revisionId: Long, newDateMillis: Long) {
        val revision = revisionDao.getByIdOnce(revisionId) ?: return
        revisionDao.update(revision.copy(status = RevisionStatus.DUE, scheduledDate = newDateMillis))
    }
}
