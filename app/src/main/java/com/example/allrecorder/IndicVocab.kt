package com.example.allrecorder

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import java.lang.Exception

class IndicVocab(context: Context) {

    // This will hold maps like "gu" -> [token1, token2, ...]
    private val vocabMap: Map<String, List<String>>

    // --- ADDED: This will hold maps like "gu" -> [0, 1, 2, 256, 300, ...] ---
    private val languageMasks: Map<String, List<Int>>

    // --- FIX: Corrected special token IDs to match Python script ---
    val unkId: Int = 0    // <unk>
    val sosId: Int = 256  // <s> (Start of Sentence)
    val eosId: Int = 2    // </s> (End of Sentence - assuming default, as Python doesn't use it)
    val blankId: Int = 256 // CTC blank token / RNNT blank token
    // --- End of Fix ---

    companion object {
        private const val TAG = "IndicVocab"
    }

    init {
        // Load Vocab
        val vocabPath = "indic_model/vocab.json"
        vocabMap = try {
            val jsonStream = context.assets.open(vocabPath)
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            Gson().fromJson(InputStreamReader(jsonStream), type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab.json", e)
            emptyMap() // Failed to load
        }

        // --- ADDED: Load Language Masks ---
        val maskPath = "indic_model/language_masks.json"
        languageMasks = try {
            val jsonStream = context.assets.open(maskPath)
            // Gson parses JSON numbers as Double by default
            val doubleMapType = object : TypeToken<Map<String, List<Double>>>() {}.type
            val doubleMap: Map<String, List<Double>> = Gson().fromJson(InputStreamReader(jsonStream), doubleMapType)

            // Convert List<Double> to List<Int>
            doubleMap.mapValues { entry ->
                entry.value.map { it.toInt() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load language_masks.json", e)
            emptyMap() // Failed to load
        }
        // --- END OF ADDITION ---
    }

    fun getVocabFor(language: String): List<String>? {
        return vocabMap[language]
    }

    // --- ADDED: Function to get language mask ---
    fun getMaskFor(language: String): List<Int>? {
        return languageMasks[language]
    }
    // --- END OF ADDITION ---


    /**
     * Decodes a list of token IDs into a string for a specific language.
     * These token IDs are assumed to be indices into the language-specific vocab.
     */
    fun decode(tokenIds: List<Int>, language: String): String {
        val vocab = getVocabFor(language) ?: return "ERROR: No vocab for '$language'"
        val sb = StringBuilder()
        for (id in tokenIds) {

            // --- FIX: Filter out all special tokens based on Python script's IDs ---
            // We only filter <unk>, sos, and blank. EOS is not filtered but usually not present.
            if (id != unkId && id != sosId && id != blankId) {
                // --- End of Fix ---
                if (id >= 0 && id < vocab.size) {
                    sb.append(vocab[id])
                } else {
                    Log.w(TAG, "Decode: Invalid token ID $id for lang '$language' (vocab size ${vocab.size})")
                    sb.append("[?]") // Unknown token
                }
            }
        }
        // From HuggingFace processor: .replace(" ", " ").strip()
        return sb.toString().replace("\u2581", " ").trim()
    }
}
