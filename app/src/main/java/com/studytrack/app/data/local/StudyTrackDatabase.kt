package com.studytrack.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun fromCourseStatus(value: CourseStatus): String = value.name
    @TypeConverter fun toCourseStatus(value: String): CourseStatus = CourseStatus.valueOf(value)

    @TypeConverter fun fromLosStatus(value: LosStatus): String = value.name
    @TypeConverter fun toLosStatus(value: String): LosStatus = LosStatus.valueOf(value)

    @TypeConverter fun fromSessionStatus(value: SessionStatus): String = value.name
    @TypeConverter fun toSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)

    @TypeConverter fun fromSessionSource(value: SessionSource): String = value.name
    @TypeConverter fun toSessionSource(value: String): SessionSource = SessionSource.valueOf(value)

    @TypeConverter fun fromGoalType(value: GoalType): String = value.name
    @TypeConverter fun toGoalType(value: String): GoalType = GoalType.valueOf(value)

    @TypeConverter fun fromGoalStatus(value: GoalStatus): String = value.name
    @TypeConverter fun toGoalStatus(value: String): GoalStatus = GoalStatus.valueOf(value)

    @TypeConverter fun fromRevisionStatus(value: RevisionStatus): String = value.name
    @TypeConverter fun toRevisionStatus(value: String): RevisionStatus = RevisionStatus.valueOf(value)
}

@Database(
    entities = [
        CourseEntity::class,
        ModuleEntity::class,
        TopicEntity::class,
        LearningOutcomeEntity::class,
        ActiveSessionEntity::class,
        StudySessionEntity::class,
        GoalEntity::class,
        RevisionEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class StudyTrackDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun moduleDao(): ModuleDao
    abstract fun topicDao(): TopicDao
    abstract fun learningOutcomeDao(): LearningOutcomeDao
    abstract fun activeSessionDao(): ActiveSessionDao
    abstract fun studySessionDao(): StudySessionDao
    abstract fun goalDao(): GoalDao
    abstract fun revisionDao(): RevisionDao

    companion object {
        @Volatile private var INSTANCE: StudyTrackDatabase? = null

        fun getInstance(context: Context): StudyTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StudyTrackDatabase::class.java,
                    "studytrack.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
