package com.example.allrecorder

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromFloatList(value: List<Float>?): String? {
        if (value == null) return null
        return gson.toJson(value)
    }

    @TypeConverter
    fun toFloatList(value: String?): List<Float>? {
        if (value == null) return null
        val type = object : TypeToken<List<Float>>() {}.type
        return gson.fromJson(value, type)
    }
}