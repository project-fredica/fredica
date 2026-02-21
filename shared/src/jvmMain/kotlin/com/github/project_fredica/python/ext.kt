package com.github.project_fredica.python

import com.github.project_fredica.apputil.AppUtil
import java.io.File

val AppUtil.Paths.desktopWinPythonExePath: File
    get() = AppUtil.Paths.appDataCacheDir.resolve("Winpython64-3.12.4.1.exe")