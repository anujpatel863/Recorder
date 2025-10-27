package com.example.allrecorder

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

class IndicVocab(context: Context) {

    // This will hold maps like "gu" -> [token1, token2, ...]
    private val vocabMap: Map<String, List<String>>

    // --- FIX: Corrected special token IDs for the ai4bharat model ---
    val unkId: Int = 0    // <unk>
    val sosId: Int = 1    // <s> (Start of Sentence)
    val eosId: Int = 2    // </s> (End of Sentence)
    val blankId: Int = 0  // CTC blank token is the same as <unk>
    // --- End of Fix ---

    init {
        // This path must point to your new vocab.json
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

            // --- FIX: Filter out all special tokens ---
            if (id != unkId && id != sosId && id != eosId && id != blankId) {
                // --- End of Fix ---
                if (id < vocab.size) {
                    sb.append(vocab[id])
                } else {
                    sb.append("[?]") // Unknown token
                }
            }
        }
        // From HuggingFace processor: .replace(" ", " ").strip()
        return sb.toString().replace("\u2581", " ").trim()
    }
}