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

    // --- This will hold the *integer indices* (e.g., "gu" -> [0, 4, 256, ...]) ---
    private val languageMasks: Map<String, List<Int>>

    // --- Special token IDs (unchanged) ---
    val unkId: Int = 0    // <unk>
    val sosId: Int = 256  // <s> (Start of Sentence)
    val eosId: Int = 2    // </s> (End of Sentence - assuming default, as Python doesn't use it)
    val blankId: Int = 256 // CTC blank token / RNNT blank token

    companion object {
        private const val TAG = "IndicVocab"
    }

    init {
        // Load Vocab (Unchanged)
        val vocabPath = "indic_model/vocab.json"
        vocabMap = try {
            val jsonStream = context.assets.open(vocabPath)
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            Gson().fromJson(InputStreamReader(jsonStream), type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab.json", e)
            emptyMap() // Failed to load
        }

        // --- START OF FIX: Load Boolean masks and convert to Int indices ---
        val maskPath = "indic_model/language_masks.json"
        languageMasks = try {
            Log.d(TAG, "Loading language_masks.json...")
            val jsonStream = context.assets.open(maskPath)

            // 1. Define the correct type for the JSON file (String -> List<Boolean>)
            val booleanMapType = object : TypeToken<Map<String, List<Boolean>>>() {}.type

            // 2. Parse the JSON into a map of boolean lists
            val booleanMap: Map<String, List<Boolean>> = Gson().fromJson(InputStreamReader(jsonStream), booleanMapType)
            Log.d(TAG, "Loaded boolean masks for: ${booleanMap.keys.joinToString()}")

            // 3. Convert the boolean map (e.g., [true, false, true])
            //    to an integer index map (e.g., [0, 2])
            //    This mimics the Python script's `np.where(mask)[0]`
            booleanMap.mapValues { (lang, boolList) ->
                // Find all indices where the boolean is true
                val intIndices = mutableListOf<Int>()
                boolList.forEachIndexed { index, isTrue ->
                    if (isTrue) {
                        intIndices.add(index)
                    }
                }
                Log.d(TAG, "Language '$lang': Found ${intIndices.size} valid token indices from mask.")
                intIndices // The new value for this key
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load or parse language_masks.json", e)
            emptyMap() // Failed to load, return an empty map
        }
        // --- END OF FIX ---
    }

    fun getVocabFor(language: String): List<String>? {
        return vocabMap[language]
    }

    /**
     * Returns the pre-calculated list of integer token indices for the given language.
     */
    fun getMaskFor(language: String): List<Int>? {
        val mask = languageMasks[language] // This will now search the converted int map
        if (mask == null) {
            // This error will now correctly trigger if "gu" isn't in the JSON file
            Log.e(TAG, "getMaskFor: No integer index mask found for language '$language'. Map contains: ${languageMasks.keys.joinToString()}")
        }
        return mask
    }

    /**
     * Decodes a list of token IDs into a string for a specific language.
     * These token IDs are assumed to be indices into the language-specific vocab.
     */
    fun decode(tokenIds: List<Int>, language: String): String {
        val vocab = getVocabFor(language) ?: return "ERROR: No vocab for '$language'"
        val sb = StringBuilder()
        for (id in tokenIds) {
            // Filter out special tokens
            if (id != unkId && id != sosId && id != blankId) {
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