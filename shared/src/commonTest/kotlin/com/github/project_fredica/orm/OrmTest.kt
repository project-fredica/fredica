package com.github.project_fredica.orm

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.jsonPretty
import com.github.project_fredica.orm.app_entities.ModelConfig
import kotlin.test.Test

class OrmTest {

    @Test
    fun parseClassTest() {
        val tableDefine = SqliteOrm.parse(ModelConfig::class)
        println(AppUtil.GlobalVars.jsonPretty.encodeToString(tableDefine))
        println(StringBuilder().also { sb -> tableDefine.sqlCreateTable(sb) })
        tableDefine.columnList.forEach { col ->
            if (col.isId) {
                return@forEach
            }
            println(StringBuilder().also { sb -> col.sqlAlterTableAddColumn(sb, tableDefine.tableName) })
        }
    }
}