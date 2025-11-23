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
    @TypeConverter
    fun fromStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        // Split by | and filter empty strings just in case
        return value.split("|").filter { it.isNotBlank() }
    }

    @TypeConverter
    fun toStringList(list: List<String>?): String {
        if (list.isNullOrEmpty()) return ""
        // Join with |
        return list.joinToString("|")
    }
}