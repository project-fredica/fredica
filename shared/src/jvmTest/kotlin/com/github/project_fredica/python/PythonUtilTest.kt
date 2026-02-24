package com.github.project_fredica.python

import com.github.project_fredica.apputil.createLogger
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test

class PythonUtilTest {
    private val logger = createLogger()

    @BeforeTest
    fun beforeTest() {
        runBlocking {
            PythonUtil.Py314Embed.init()
        }
    }

    @Test
    fun testRunPyUtilServer() {
        logger.debug("run pyutil server")
    }
}