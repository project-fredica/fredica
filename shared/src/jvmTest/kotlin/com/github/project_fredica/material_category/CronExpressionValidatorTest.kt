package com.github.project_fredica.material_category

import com.github.project_fredica.material_category.model.CronExpressionValidator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CronExpressionValidatorTest {

    @Test
    fun standardExpressions() {
        assertTrue(CronExpressionValidator.isValid("* * * * *"))
        assertTrue(CronExpressionValidator.isValid("0 */6 * * *"))
        assertTrue(CronExpressionValidator.isValid("30 2 * * 1-5"))
        assertTrue(CronExpressionValidator.isValid("0 0 1 1 *"))
    }

    @Test
    fun commaLists() {
        assertTrue(CronExpressionValidator.isValid("0,15,30,45 * * * *"))
        assertTrue(CronExpressionValidator.isValid("0 9,17 * * *"))
    }

    @Test
    fun ranges() {
        assertTrue(CronExpressionValidator.isValid("0 9-17 * * *"))
        assertTrue(CronExpressionValidator.isValid("* * * * 1-5"))
    }

    @Test
    fun stepValues() {
        assertTrue(CronExpressionValidator.isValid("*/5 * * * *"))
        assertTrue(CronExpressionValidator.isValid("0 */2 * * *"))
        assertTrue(CronExpressionValidator.isValid("0-30/5 * * * *"))
    }

    @Test
    fun dayOfWeekBoundary() {
        assertTrue(CronExpressionValidator.isValid("0 0 * * 0"))
        assertTrue(CronExpressionValidator.isValid("0 0 * * 7"))
    }

    @Test
    fun tooFewFields() {
        assertFalse(CronExpressionValidator.isValid("* * * *"))
        assertFalse(CronExpressionValidator.isValid("*"))
    }

    @Test
    fun tooManyFields() {
        assertFalse(CronExpressionValidator.isValid("* * * * * *"))
    }

    @Test
    fun outOfRangeMinute() {
        assertFalse(CronExpressionValidator.isValid("60 * * * *"))
    }

    @Test
    fun outOfRangeHour() {
        assertFalse(CronExpressionValidator.isValid("0 24 * * *"))
    }

    @Test
    fun outOfRangeDay() {
        assertFalse(CronExpressionValidator.isValid("0 0 32 * *"))
        assertFalse(CronExpressionValidator.isValid("0 0 0 * *"))
    }

    @Test
    fun outOfRangeMonth() {
        assertFalse(CronExpressionValidator.isValid("0 0 * 13 *"))
        assertFalse(CronExpressionValidator.isValid("0 0 * 0 *"))
    }

    @Test
    fun outOfRangeDow() {
        assertFalse(CronExpressionValidator.isValid("0 0 * * 8"))
    }

    @Test
    fun invalidStepZero() {
        assertFalse(CronExpressionValidator.isValid("*/0 * * * *"))
    }

    @Test
    fun invalidRangeReversed() {
        assertFalse(CronExpressionValidator.isValid("30-10 * * * *"))
    }

    @Test
    fun emptyString() {
        assertFalse(CronExpressionValidator.isValid(""))
    }

    @Test
    fun nonNumeric() {
        assertFalse(CronExpressionValidator.isValid("abc * * * *"))
    }

    @Test
    fun extraWhitespace() {
        assertTrue(CronExpressionValidator.isValid("  0  */6  *  *  *  "))
    }
}
