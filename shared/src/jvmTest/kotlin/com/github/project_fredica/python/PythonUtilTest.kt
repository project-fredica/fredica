package com.github.project_fredica.python

import com.github.project_fredica.apputil.createLogger
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class PythonUtilTest {
    private val logger = createLogger()

    @BeforeTest
    fun beforeTest() {
        runBlocking {
            PythonUtil.Py314Embed.init()
            PythonUtil.Py314Embed.PyUtilServer.start()
        }
    }

    @Test
    fun testPing() = runBlocking {
        val resp = PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Get, "/ping")
        assertTrue(resp.contains("pong"))
    }

    @Test
    fun testDeviceInfo() = runBlocking {
        val resp = PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Get, "/device/info")
        assertTrue(resp.contains("device_info_json"))
        assertTrue(resp.contains("ffmpeg_probe_json"))
    }

    @Test
    fun testDeviceDetect() = runBlocking {
        val resp = PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Post, "/device/detect")
        assertTrue(resp.contains("device_info_json"))
        assertTrue(resp.contains("ffmpeg_probe_json"))
    }

    @Test
    fun testDeviceFfmpegFind() = runBlocking {
        val resp = PythonUtil.Py314Embed.PyUtilServer.requestText(HttpMethod.Get, "/device/ffmpeg-find")
        assertTrue(resp.contains("ffmpeg_probe_json"))
    }
}
