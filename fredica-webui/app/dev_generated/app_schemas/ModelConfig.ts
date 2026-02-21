import type { FromSchema } from "json-schema-to-ts";

export const schemaOfModelConfig = {
    "$id": "com.github.project_fredica.orm.app_entities.ModelConfig",
    "$defs": {
        "com.github.project_fredica.orm.app_entities.ModelConfig": {
            "type": "object",
            "properties": {
                "id": {
                    "type": "integer"
                },
                "createTime": {
                    "type": "integer"
                },
                "updateTime": {
                    "type": "integer"
                },
                "logicDelete": {
                    "type": "integer"
                },
                "lockVersion": {
                    "type": "integer"
                },
                "title": {
                    "type": [
                        "string",
                        "null"
                    ]
                },
                "duplicateId": {
                    "type": [
                        "string",
                        "null"
                    ]
                },
                "baseUrl": {
                    "type": [
                        "string",
                        "null"
                    ]
                },
                "apiToken": {
                    "type": [
                        "string",
                        "null"
                    ]
                },
                "modelName": {
                    "type": [
                        "string",
                        "null"
                    ]
                },
                "isSupportVisual": {
                    "type": [
                        "boolean",
                        "null"
                    ]
                }
            },
            "required": [
                "id",
                "createTime",
                "updateTime",
                "logicDelete",
                "lockVersion",
                "title",
                "duplicateId",
                "baseUrl",
                "apiToken",
                "modelName",
                "isSupportVisual"
            ],
            "additionalProperties": false
        }
    },
    "$ref": "#/$defs/com.github.project_fredica.orm.app_entities.ModelConfig"
} as const;
                
export type ModelConfig = FromSchema<typeof schemaOfModelConfig>;