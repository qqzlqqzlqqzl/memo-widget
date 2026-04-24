package dev.aria.memo.data.local

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalTime

class Converters {
    @TypeConverter fun dateToEpochDay(v: LocalDate?): Long? = v?.toEpochDay()
    @TypeConverter fun epochDayToDate(v: Long?): LocalDate? = v?.let(LocalDate::ofEpochDay)

    /** P5 single-note format: store LocalTime as seconds-of-day so the column
     *  sorts chronologically in SQL and avoids string-format bugs. */
    @TypeConverter fun timeToSecondOfDay(v: LocalTime?): Int? = v?.toSecondOfDay()
    @TypeConverter fun secondOfDayToTime(v: Int?): LocalTime? =
        v?.let { LocalTime.ofSecondOfDay(it.toLong()) }
}
