package com.github.project_fredica.orm

import com.github.project_fredica.apputil.AppUtil
import com.github.project_fredica.apputil.caseCastLowerCamelToLowerUnderscore
import com.github.project_fredica.apputil.createLogger
import com.github.project_fredica.apputil.exception
import com.github.project_fredica.apputil.getString
import com.github.project_fredica.orm.app_entities.ModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.primaryConstructor

object SqliteOrm {
    class EntityParserFailedException(msg: String) : IllegalArgumentException(msg)

    interface Executor {
        sealed interface InvokeResult

        object Finish : InvokeResult {
            override fun toString(): String {
                return Finish::class.simpleName!!
            }
        }

        @JvmInline
        value class RowResult(val jsonObject: JsonObject) : InvokeResult {
            override fun toString(): String {
                return jsonObject.toString()
            }
        }

        operator fun invoke(s: String, args: List<Any>?): Flow<InvokeResult>
    }

    suspend fun Flow<Executor.InvokeResult>.toRows(): List<Executor.RowResult> {
        return this.toList().filterIsInstance<Executor.RowResult>()
    }

    @Serializable
    data class TableDefine<T : Any>(
        val ktClassSimpleName: String, val tableName: String, val columnList: List<ColumnDefine<T>>
    ) {
        fun sqlCreateTable(sb: StringBuilder) {
            checkIdSafe(tableName)
            sb.append("CREATE TABLE IF NOT EXISTS `$tableName` (")
            for (idx in 0 until columnList.size) {
                val col = columnList[idx]
                sb.append("\n    ")
                col.sqlPartOfCreateTable(sb)
                if (idx != columnList.lastIndex) {
                    sb.append(",")
                }
            }
            sb.append("\n)")
        }

        fun sqlPragmaTable(): String {
            return "PRAGMA table_info(${checkIdSafe(tableName)})"
        }

        suspend fun createTable(executor: Executor) {
            val logger = createLogger()
            executor(StringBuilder().also { sqlCreateTable(it) }.toString(), null).collect {}
            val existedCols = executor(sqlPragmaTable(), null).toRows()
            logger.debug("existedCols is $existedCols")
            this.columnList.forEach { col ->
                val colInfoInDb = try {
                    existedCols.find { it.jsonObject.getString("name").getOrThrow() == col.columnName }
                } catch (err: Throwable) {
                    logger.exception("why cast json failed ?", err)
                    null
                }
                if (colInfoInDb == null) {
                    logger.debug("start add column : $col")
                    executor(
                        StringBuilder().also { col.sqlAlterTableAddColumn(it, tableName) }.toString(), null
                    ).toRows()
                } else if (colInfoInDb.jsonObject.getString("type").getOrThrow()
                        .lowercase() != col.colType.name.lowercase()
                ) {
                    throw IllegalStateException("DataType not match : col is $col , colInfoInDb is $colInfoInDb")
                }
            }
        }
    }

    @Serializable
    data class ColumnDefine<T : Any>(
        val ktPropertyName: String,
        val columnName: String,
        val isId: Boolean,
        val ktType: String,
        val colType: ColType,
        val isNullable: Boolean,
        val defaultValue: String,
    ) {
        private fun sqlPartOfNotNullAndDefault(sb: StringBuilder) {
            if (!isNullable) {
                sb.append(" NOT NULL")
            }
            if (defaultValue.isNotBlank()) {
                sb.append(" DEFAULT $defaultValue")
            }
        }

        fun sqlPartOfCreateTable(sb: StringBuilder) {
            checkIdSafe(columnName)
            sb.append("`$columnName` ${colType.name}")
            sqlPartOfNotNullAndDefault(sb)
            if (isId) {
                sb.append(" PRIMARY KEY AUTOINCREMENT")
            }
        }

        fun sqlAlterTableAddColumn(sb: StringBuilder, tableName: String) {
            if (isId) {
                throw IllegalStateException("Id column should not alter")
            }
            checkIdSafe(tableName)
            checkIdSafe(columnName)
            sb.append("ALTER TABLE `$tableName` ADD COLUMN `$columnName` ${colType.name}")
            sqlPartOfNotNullAndDefault(sb)
        }
    }

    enum class ColType {
        TEXT, INTEGER
    }

    private fun checkIdSafe(s: String): String {
        if (!Regex("[a-z0-9_]*").matches(s)) {
            throw IllegalArgumentException("Invalid id string : $s")
        }
        return s
    }

    fun <T : Any> parse(clz: KClass<T>): TableDefine<T> {
        if (!clz.isData) {
            throw EntityParserFailedException("$clz not data class")
        }
        if (null == clz.annotations.find { it.annotationClass == Serializable::class }) {
            throw EntityParserFailedException("$clz need @Serializable")
        }

        val tableName = checkIdSafe(clz.simpleName!!.let { AppUtil.StrUtil.caseCastLowerCamelToLowerUnderscore(it) })

        val paramNames = clz.primaryConstructor!!.parameters.map { it.name }

        val columnList = clz.declaredMemberProperties.sortedBy {
            paramNames.indexOf(it.name)
                .also { idx -> if (idx < 0) throw EntityParserFailedException("Invalid field $it") }
        }.map { prop ->
            val columnName = checkIdSafe(prop.name.let { AppUtil.StrUtil.caseCastLowerCamelToLowerUnderscore(it) })
            val colAnno = prop.annotations.find { it.annotationClass == Col::class }
            val col = colAnno?.let { it as Col }
            val isId = col?.isId ?: false
            val isNullable = prop.returnType.isMarkedNullable
            val defaultValue = col?.defaultValue ?: ""
            val colType = when (prop.returnType.classifier) {
                String::class -> {
                    ColType.TEXT
                }

                Long::class -> {
                    ColType.INTEGER
                }

                else -> {
                    throw EntityParserFailedException("Not support colType of $prop")
                }
            }
            if (isId) {
                if (colType != ColType.INTEGER) {
                    throw EntityParserFailedException("Id column should be Long , $prop")
                }
                if (defaultValue.isNotBlank()) {
                    throw EntityParserFailedException("Id column should not set default value , $prop")
                }
            }
            if (!isNullable && defaultValue.isBlank() && !isId) {
                throw EntityParserFailedException("Column NOT NULL , but not set DEFAULT value , $prop")
            }
            return@map ColumnDefine<T>(
                ktPropertyName = prop.name,
                columnName = columnName,
                isId = isId,
                ktType = "${prop.returnType}",
                isNullable = isNullable,
                colType = colType,
                defaultValue = defaultValue,
            )
        }

        if (columnList.filter { it.isId }.size != 1) {
            throw EntityParserFailedException("Invalid id fields , clz is $clz")
        }

        return TableDefine(
            ktClassSimpleName = clz.simpleName!!,
            tableName = tableName,
            columnList = columnList,
        )
    }

    val allTable by lazy {
        listOf(
            ModelConfig::class
        ).map { parse(it) }
    }
}