package com.hyundai.motor_rookie.intervention

import kotlin.math.max
import kotlin.math.min

/**
 * 퀴즈 15문항 (유형별 5개). 기획서 5.3: 랜덤으로 3문제 출제.
 * 운전 중이라 답이 짧은 문제만 넣었다 (숫자, 한 단어, 짧은 문장).
 */
object QuizBank {

    val all: List<Quiz> = listOf(
        // ── 단순 퀴즈 ──
        Quiz(QuizType.TRIVIA, "일주일은 며칠일까요?", "일주일은 며칠일까요?",
            listOf("7", "칠", "칠일", "7일", "일곱"), answerText = "7일"),
        Quiz(QuizType.TRIVIA, "신호등에서 멈추라는 뜻의 색은 무엇일까요?", "신호등에서 '멈춤' 색은?",
            listOf("빨강", "빨간색", "빨간", "적색"), answerText = "빨간색"),
        Quiz(QuizType.TRIVIA, "대한민국의 수도는 어디일까요?", "대한민국의 수도는?",
            listOf("서울")),
        Quiz(QuizType.TRIVIA, "하루는 몇 시간일까요?", "하루는 몇 시간?",
            listOf("24", "이십사", "스물네", "스물넷"), answerText = "24시간"),
        Quiz(QuizType.TRIVIA, "무지개는 모두 몇 가지 색일까요?", "무지개는 몇 가지 색?",
            listOf("7", "칠", "일곱"), answerText = "7가지"),

        // ── 따라 말하기 ──
        Quiz(QuizType.REPEAT, "제가 하는 말을 따라 해보세요. 오늘도 안전 운전", "“오늘도 안전 운전”",
            listOf("오늘도 안전 운전")),
        Quiz(QuizType.REPEAT, "따라 해보세요. 졸리면 쉬어 가요", "“졸리면 쉬어 가요”",
            listOf("졸리면 쉬어 가요")),
        Quiz(QuizType.REPEAT, "따라 해보세요. 오늘 하루도 활기차게", "“오늘 하루도 활기차게”",
            listOf("오늘 하루도 활기차게")),
        Quiz(QuizType.REPEAT, "따라 해보세요. 곁이 함께 달려요", "“곁이 함께 달려요”",
            listOf("곁이 함께 달려요")),
        Quiz(QuizType.REPEAT, "따라 해보세요. 천천히 가도 늦지 않아요", "“천천히 가도 늦지 않아요”",
            listOf("천천히 가도 늦지 않아요")),

        // ── 간단한 연산 ──
        Quiz(QuizType.MATH, "3 더하기 4는 얼마일까요?", "3 + 4 = ?",
            listOf("7", "칠", "일곱")),
        Quiz(QuizType.MATH, "10 빼기 2는 얼마일까요?", "10 − 2 = ?",
            listOf("8", "팔", "여덟")),
        Quiz(QuizType.MATH, "5 곱하기 2는 얼마일까요?", "5 × 2 = ?",
            listOf("10", "십", "열")),
        Quiz(QuizType.MATH, "6 더하기 7은 얼마일까요?", "6 + 7 = ?",
            listOf("13", "십삼", "열셋")),
        Quiz(QuizType.MATH, "20 나누기 4는 얼마일까요?", "20 ÷ 4 = ?",
            listOf("5", "오", "다섯"))
    )

    /** 설정에서 고른 종류 중에서 무작위 n문제 (중복 없음) */
    fun pick(n: Int, types: Set<QuizType>, exclude: Collection<Quiz> = emptyList()): List<Quiz> {
        val pool = all.filter { it.type in types && it !in exclude }
            .ifEmpty { all.filter { it !in exclude } }
        return pool.shuffled().take(n)
    }

    fun pickOne(types: Set<QuizType>, exclude: Collection<Quiz>): Quiz? =
        pick(1, types, exclude).firstOrNull()

    // ── 정답 판정 ──

    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    fun isCorrect(quiz: Quiz, heard: String): Boolean {
        val h = normalize(heard)
        if (h.isEmpty()) return false
        val tokens = heard.split(" ", ",", ".", "?", "!").map(::normalize).filter { it.isNotEmpty() }

        return when (quiz.type) {
            // 따라 말하기: 70% 이상 비슷하면 정답 (음성 인식 오차 허용)
            QuizType.REPEAT -> quiz.answers.any { similarity(normalize(it), h) >= 0.7 }

            // 퀴즈/연산: 정답 단어가 포함되면 정답. 한 글자 답(칠, 팔, 오…)은 단어가 정확히 같아야 함
            else -> quiz.answers.any { ans ->
                val a = normalize(ans)
                if (a.length <= 1) tokens.any { it == a || it == a + "이요" || it == a + "요" }
                else h.contains(a)
            }
        }
    }

    /** 퀴즈 중 음성 명령: "괜찮아" / "다른 질문" / "그만" */
    fun command(heard: String): QuizCommand? {
        val h = normalize(heard)
        return when {
            h.contains("그만") || h.contains("종료") -> QuizCommand.STOP
            h.contains("다른질문") || h.contains("다른문제") || h.contains("넘어가") -> QuizCommand.SKIP
            h.contains("괜찮아") || h.contains("안졸려") || h.contains("나중에") -> QuizCommand.SNOOZE
            else -> null
        }
    }

    /** 레벤슈타인 거리 기반 유사도 (0~1) */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
        }
        return 1.0 - dp[a.length][b.length].toDouble() / max(a.length, b.length)
    }
}
