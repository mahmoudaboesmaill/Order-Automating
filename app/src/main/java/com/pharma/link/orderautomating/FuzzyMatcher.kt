package com.pharma.link.orderautomating

object FuzzyMatcher {

    // حساب Levenshtein Distance بين نصين
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1]
                       else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
        }
        return dp[m][n]
    }

    // نسبة التشابه من 0.0 إلى 1.0
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    // نتيجة المطابقة
    sealed class MatchResult {
        data class AutoMatch(val item: PharmacyItem, val score: Double) : MatchResult()
        data class Suggestion(val item: PharmacyItem, val score: Double) : MatchResult()
        object NoMatch : MatchResult()
    }

    fun findBestMatch(
        query: String,
        candidates: List<PharmacyItem>
    ): MatchResult {
        val normalizedQuery = ArabicNormalizer.normalize(query)
        var bestScore = 0.0
        var bestItem: PharmacyItem? = null

        for (candidate in candidates) {
            val nameAr = ArabicNormalizer.normalize(candidate.nameAr)
            val nameEn = candidate.nameEn.lowercase()

            // تحقق حرج: الأرقام لازم تكون متطابقة
            val queryNums = ArabicNormalizer.extractNumbers(query)
            val candidateNums = ArabicNormalizer.extractNumbers(candidate.nameAr)
            if (queryNums.isNotEmpty() && queryNums != candidateNums) continue

            val score = maxOf(
                similarity(normalizedQuery, nameAr),
                similarity(normalizedQuery, nameEn)
            )

            if (score > bestScore) {
                bestScore = score
                bestItem = candidate
            }
        }

        return when {
            bestItem == null      -> MatchResult.NoMatch
            bestScore >= 0.90     -> MatchResult.AutoMatch(bestItem, bestScore)
            bestScore >= 0.70     -> MatchResult.Suggestion(bestItem, bestScore)
            else                  -> MatchResult.NoMatch
        }
    }
}
