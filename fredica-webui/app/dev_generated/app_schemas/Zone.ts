import type { FromSchema } from "json-schema-to-ts";

export const schemaOfZone = {
    "$id": "com.github.project_fredica.orm.app_entities.Zone",
    "$defs": {
        "com.github.project_fredica.orm.app_entities.Zone": {
            "type": "object",
            "properties": {
                "id": {
                    "type": "integer"
                },
                "name": {
                    "type": "string"
                }
            },
            "required": [
                "id",
                "name"
            ],
            "additionalProperties": false
        }
    },
    "$ref": "#/$defs/com.github.project_fredica.orm.app_entities.Zone"
} as const;
                
export type Zone = FromSchema<typeof schemaOfZone>;