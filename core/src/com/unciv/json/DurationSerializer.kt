package com.unciv.json

import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.Json.Serializer
import com.badlogic.gdx.utils.JsonValue
import org.threeten.bp.Duration

class DurationSerializer : Serializer<Duration> {
    override fun write(json: Json, duration: Duration, knownType: Class<*>?) {
        json.writeValue(duration.toString())
    }

    override fun read(json: Json, jsonData: JsonValue, type: Class<*>?) : Duration {
        return Duration.parse(jsonData.asString())
    }
}
