package com.github.project_fredica.apputil

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonCanonicalTest {

    @Test
    fun keyOrderNormalized() {
        val result = jsonCanonical("""{"b":1,"a":2}""")
        assertEquals("""{"a":2,"b":1}""", result)
    }

    @Test
    fun nestedObjectRecursive() {
        val result = jsonCanonical("""{"z":{"y":1,"x":2},"a":3}""")
        assertEquals("""{"a":3,"z":{"x":2,"y":1}}""", result)
    }

    @Test
    fun arrayPreservesOrder() {
        val result = jsonCanonical("""{"a":[3,1,2]}""")
        assertEquals("""{"a":[3,1,2]}""", result)
    }

    @Test
    fun arrayInnerObjectNormalized() {
        val result = jsonCanonical("""[{"b":1,"a":2},{"d":3,"c":4}]""")
        assertEquals("""[{"a":2,"b":1},{"c":4,"d":3}]""", result)
    }

    @Test
    fun idempotent() {
        val input = """{"a":1,"b":{"c":2,"d":3}}"""
        val once = jsonCanonical(input)
        val twice = jsonCanonical(once)
        assertEquals(once, twice)
    }
}
