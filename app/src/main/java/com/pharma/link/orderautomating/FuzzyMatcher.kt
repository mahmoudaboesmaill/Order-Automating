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

    // نسبة التشابه النصي من 0.0 إلى 1.0
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    // نسبة تطابق الكلمات (Token Overlap)
    private fun tokenOverlap(query: String, target: String): Double {
        val qTokens = query.split(" ").filter { it.length >= 2 }
        val tTokens = target.split(" ").filter { it.length >= 2 }
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0.0

        var matchedCount = 0
        for (q in qTokens) {
            if (tTokens.any { t -> t == q || similarity(q, t) >= 0.85 || t.startsWith(q) || q.startsWith(t) }) {
                matchedCount++
            }
        }
        return matchedCount.toDouble() / qTokens.size.toDouble()
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
        val strippedQuery   = ArabicNormalizer.stripDosageForms(query)
        val queryNums       = ArabicNormalizer.extractNumbers(query)

        var bestScore = 0.0
        var bestItem: PharmacyItem? = null

        for (candidate in candidates) {
            val nameAr = ArabicNormalizer.normalize(candidate.nameAr)
            val nameEn = ArabicNormalizer.normalize(candidate.nameEn)
            val strippedAr = ArabicNormalizer.stripDosageForms(candidate.nameAr)
            val strippedEn = ArabicNormalizer.stripDosageForms(candidate.nameEn)

            // تحقق حرج: الأرقام (التركيز/الحجم) يجب أن تكون متطابقة
            val candidateNumsAr = ArabicNormalizer.extractNumbers(candidate.nameAr)
            val candidateNumsEn = ArabicNormalizer.extractNumbers(candidate.nameEn)
            val allCandidateNums = (candidateNumsAr + candidateNumsEn).distinct()

            if (queryNums.isNotEmpty()) {
                val hasMatchingNumber = queryNums.any { it in allCandidateNums }
                if (!hasMatchingNumber && allCandidateNums.isNotEmpty()) {
                    continue // استبعاد الصنف إذا كان التركيز أو الرقم مختلفاً
                }
            }

            // 1. حساب التشابه الكامل
            val scoreFullAr = similarity(normalizedQuery, nameAr)
            val scoreFullEn = similarity(normalizedQuery, nameEn)
            
            // 2. حساب التشابه بعد تجريد الأشكال الصيدلانية
            val scoreStrippedAr = similarity(strippedQuery, strippedAr)
            val scoreStrippedEn = similarity(strippedQuery, strippedEn)

            // 3. حساب تطابق الكلمات
            val tokenScoreAr = tokenOverlap(normalizedQuery, nameAr)
            val tokenScoreEn = tokenOverlap(normalizedQuery, nameEn)

            val maxTextScore = maxOf(scoreFullAr, scoreFullEn, scoreStrippedAr, scoreStrippedEn)
            val maxTokenScore = maxOf(tokenScoreAr, tokenScoreEn)

            // دمج الدرجات بذكاء
            var combinedScore = (maxTextScore * 0.6) + (maxTokenScore * 0.4)

            // زيادة الدرجة إذا كان التركيز متطابقاً تماماً
            if (queryNums.isNotEmpty() && allCandidateNums.containsAll(queryNums)) {
                combinedScore = minOf(1.0, combinedScore + 0.05)
            }

            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestItem = candidate
            }
        }

        return when {
            bestItem == null      -> MatchResult.NoMatch
            bestScore >= 0.88     -> MatchResult.AutoMatch(bestItem, bestScore)
            bestScore >= 0.65     -> MatchResult.Suggestion(bestItem, bestScore)
            else                  -> MatchResult.NoMatch
        }
    }
}

