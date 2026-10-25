package com.alex.rutasapie
import org.junit.Assert.*
import org.junit.Test
class RouteLogicTest {
    @Test fun rangeHoursUsesLowerBound() = assertEquals(180, RouteLogic.minutes("3-4 h"))
    @Test fun decimalRange() = assertEquals(90, RouteLogic.minutes("1,5-2 h"))
    @Test fun hoursAndMinutes() = assertEquals(150, RouteLogic.minutes("2 h 30 min"))
    @Test fun minutesRange() = assertEquals(30, RouteLogic.minutes("30-45 min"))
    @Test fun mixedUnitRange() = assertEquals(30, RouteLogic.minutes("30 min - 1 h"))
    @Test fun compoundRange() = assertEquals(90, RouteLogic.minutes("1 h 30 min - 2 h"))
    @Test fun missingUnitsNotGuessed() = assertEquals(Int.MAX_VALUE, RouteLogic.minutes("2"))
    @Test fun unknownTimeLast() = assertEquals(Int.MAX_VALUE, RouteLogic.minutes("Sin dato"))
    @Test fun rangeDistance() = assertEquals(10.0, RouteLogic.distance("10-15 km"), 0.0)
    @Test fun distanceInMeters() = assertEquals(0.5, RouteLogic.distance("500 m"), 0.0)
    @Test fun decimalDistance() = assertEquals(2.5, RouteLogic.distance("2,5 km"), 0.0)
    @Test fun mixedDifficultyUsesHigher() = assertEquals(3, RouteLogic.difficulty("Media - Alta"))
    @Test fun easyMediumNotUnknown() = assertEquals(2, RouteLogic.difficulty("Fácil - Media"))
    @Test fun normalizesAccents() = assertEquals("penalara", RouteLogic.normalize("Peñalara"))
    @Test fun normalizeIndependentOfLocale() {
        val old=java.util.Locale.getDefault()
        try { java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR")); assertEquals("id",RouteLogic.normalize("ID")) }
        finally {java.util.Locale.setDefault(old)}
    }
}
