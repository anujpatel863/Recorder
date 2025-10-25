package com.example.allrecorder

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

class IndicVocab(context: Context) {

    // This will hold maps like "gu" -> [token1, token2, ...]
    private val vocabMap: Map<String, List<String>>
    val blankId: Int = 256 // From simple_inference.py
    val sosId: Int = 256 // From simple_inference.py

    init {
        val path = "indic_model/vocab.json"
        vocabMap = try {
            val jsonStream = context.assets.open(path)
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            Gson().fromJson(InputStreamReader(jsonStream), type)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap() // Failed to load
        }
    }

    fun getVocabFor(language: String): List<String>? {
        return vocabMap[language]
    }

    /**
     * Decodes a list of token IDs into a string for a specific language.
     */
    fun decode(tokenIds: List<Int>, language: String): String {
        val vocab = getVocabFor(language) ?: return "ERROR: No vocab for '$language'"
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id != sosId && id != blankId) {
                if (id < vocab.size) {
                    sb.append(vocab[id])
                } else {
                    sb.append("[?]") // Unknown token
                }
            }
        }
        // From simple_inference.py: .replace('\u2581', ' ').strip()
        return sb.toString().replace("\u2581", " ").trim()
    }
}