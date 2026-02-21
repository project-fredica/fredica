import type { FromSchema } from "json-schema-to-ts";

export const schemaOfEntityRouteSchemaView = {
    "$id": "com.github.project_fredica.api.view.EntityRouteSchemaView",
    "$defs": {
        "com.github.project_fredica.orm.core.ColType": {
            "type": "string",
            "enum": [
                "TEXT",
                "INTEGER"
            ]
        },
        "com.github.project_fredica.orm.core.ColumnDefine": {
            "type": "object",
            "properties": {
                "ktPropertyName": {
                    "type": "string"
                },
                "columnName": {
                    "type": "string"
                },
                "isId": {
                    "type": "boolean"
                },
                "ktType": {
                    "type": "string"
                },
                "colType": {
                    "$ref": "#/$defs/com.github.project_fredica.orm.core.ColType"
                },
                "isNullable": {
                    "type": "boolean"
                },
                "defaultValue": {
                    "type": "string"
                },
                "isCreateTime": {
                    "type": "boolean"
                },
                "isUpdateTime": {
                    "type": "boolean"
                },
                "isLogicDelete": {
                    "type": "boolean"
                },
                "isLockVersion": {
                    "type": "boolean"
                },
                "isDuplicateId": {
                    "type": "boolean"
                },
                "isTitle": {
                    "type": "boolean"
                }
            },
            "required": [
                "ktPropertyName",
                "columnName",
                "isId",
                "ktType",
                "colType",
                "isNullable",
                "defaultValue",
                "isCreateTime",
                "isUpdateTime",
                "isLogicDelete",
                "isLockVersion",
                "isDuplicateId",
                "isTitle"
            ],
            "additionalProperties": false
        },
        "com.github.project_fredica.orm.core.TableDefine": {
            "type": "object",
            "properties": {
                "ktClassSimpleName": {
                    "type": "string"
                },
                "tableName": {
                    "type": "string"
                },
                "columnList": {
                    "type": "array",
                    "items": {
                        "$ref": "#/$defs/com.github.project_fredica.orm.core.ColumnDefine"
                    }
                }
            },
            "required": [
                "ktClassSimpleName",
                "tableName",
                "columnList"
            ],
            "additionalProperties": false
        },
        "com.github.project_fredica.api.view.EntityRouteSchemaView": {
            "type": "object",
            "properties": {
                "tableDefine": {
                    "$ref": "#/$defs/com.github.project_fredica.orm.core.TableDefine"
                },
                "entityJsonSchemaString": {
                    "type": "string"
                },
                "modifyJsonSchemaString": {
                    "type": "string"
                }
            },
            "required": [
                "tableDefine",
                "entityJsonSchemaString",
                "modifyJsonSchemaString"
            ],
            "additionalProperties": false
        }
    },
    "$ref": "#/$defs/com.github.project_fredica.api.view.EntityRouteSchemaView"
} as const;
                
export type EntityRouteSchemaView = FromSchema<typeof schemaOfEntityRouteSchemaView>;