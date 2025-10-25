package com.example.allrecorder

object CharVocabulary {
    // This list now contains characters for English, Hindi (Devanagari), and Gujarati.
    // The order MUST match the vocabulary your Vakyansh model was trained with.
    // This is a comprehensive list based on common Vakyansh models.
    private val charList = listOf(
        "<blank>", // Blank token (Index 0)
        "'", " ", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
        "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
        // Hindi (Devanagari) Characters
        'अ', 'आ', 'इ', 'ई', 'उ', 'ऊ', 'ऋ', 'ए', 'ऐ', 'ओ', 'औ',
        'क', 'ख', 'ग', 'घ', 'ङ',
        'च', 'छ', 'ज', 'झ', 'ञ',
        'ट', 'ठ', 'ड', 'ढ', 'ण',
        'त', 'थ', 'द', 'ध', 'न',
        'प', 'फ', 'ब', 'भ', 'म',
        'य', 'र', 'ल', 'व', 'श', 'ष', 'स', 'ह',
        'ा', 'ि', 'ी', 'ु', 'ू', 'ृ', 'े', 'ै', 'ो', 'ौ', '्', 'ं', 'ः', 'ँ',
        // Gujarati Characters
        'અ', 'આ', 'ઇ', 'ઈ', 'ઉ', 'ઊ', 'ઋ', 'એ', 'ઐ', 'ઓ', 'ઔ',
        'ક', 'ખ', 'ગ', 'ઘ', 'ઙ',
        'ચ', 'છ', 'જ', 'ઝ', 'ઞ',
        'ટ', 'ઠ', 'ડ', 'ઢ', 'ણ',
        'ત', 'થ', 'દ', 'ધ', 'ન',
        'પ', 'ફ', 'બ', 'ભ', 'મ',
        'ય', 'ર', 'લ', 'વ', 'શ', 'ષ', 'સ', 'હ', 'ળ',
        'ા', 'િ', 'ી', 'ુ', 'ૂ', 'ૃ', 'ે', 'ૈ', 'ો', 'ૌ', '્', 'ં', 'ઃ', 'ઁ'
    ).map { it.toString() } // Convert all to String for consistency

    private val charMap: Map<Int, String> = charList.mapIndexed { index, char ->
        index to char
    }.toMap()

    val blankTokenId = 0

    fun getChar(tokenId: Int): String? {
        return charMap[tokenId]
    }
}