package dev.aria.memo.data.local

import androidx.room.TypeConverter
import java.time.LocalDate

class Converters {
    @TypeConverter fun dateToEpochDay(v: LocalDate?): Long? = v?.toEpochDay()
    @TypeConverter fun epochDayToDate(v: Long?): LocalDate? = v?.let(LocalDate::ofEpochDay)
}
