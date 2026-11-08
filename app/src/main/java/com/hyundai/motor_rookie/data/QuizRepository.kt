package com.hyundai.motor_rookie.data

/**
 * @param display 화면에 크게 보여줄 문제 ("3 + 4 = ?")
 * @param spoken  TTS로 읽어줄 문장 ("3 더하기 4는 얼마일까요?")
 * @param answers 정답으로 인정할 표현들. STT가 숫자를 "7" / "칠" / "일곱"처럼
 *                다르게 돌려줄 수 있으니 가능한 표현을 모두 넣어 두세요.
 * @param choices 터치로 답할 수 있는 보기 (디자인의 5 / 7 / 9 버튼). 비우면 음성으로만 답합니다.
 */
data class Quiz(
    val display: String,
    val spoken: String,
    val answers: List<String>,
    val choices: List<String> = emptyList(),
)

/**
 * 퀴즈 문제를 가져오는 곳.
 * 퀴즈 문제가 담긴 파트(서버, JSON 파일, DB 등)가 준비되면
 * 이 인터페이스를 구현한 클래스로 LocalQuizRepository 를 교체하세요.
 */
interface QuizRepository {
    /** 이번 라운드에 출제할 퀴즈를 출제 순서대로 반환합니다. */
    suspend fun nextRound(count: Int): List<Quiz>
}

/** 임시 퀴즈 모음. 라운드마다 다음 문제로 넘어가 같은 문제가 반복되지 않게 합니다. */
class LocalQuizRepository : QuizRepository {

    private val pool = listOf(
        Quiz("3 + 4 = ?", "3 더하기 4는 얼마일까요?", listOf("7", "칠", "일곱"), listOf("5", "7", "9")),
        Quiz("10 − 2 = ?", "10 빼기 2는 얼마일까요?", listOf("8", "팔", "여덟"), listOf("6", "8", "12")),
        Quiz("5 × 2 = ?", "5 곱하기 2는 얼마일까요?", listOf("10", "십", "열"), listOf("7", "10", "25")),
        Quiz("대한민국의 수도는?", "대한민국의 수도는 어디일까요?", listOf("서울"), listOf("부산", "서울", "대전")),
        Quiz("토요일 다음 날은?", "토요일 다음 날은 무슨 요일일까요?", listOf("일요일"), listOf("금요일", "일요일", "월요일")),
        Quiz("9 − 4 = ?", "9 빼기 4는 얼마일까요?", listOf("5", "다섯"), listOf("3", "5", "6")),
    )
    private var offset = 0

    override suspend fun nextRound(count: Int): List<Quiz> {
        val round = List(count) { pool[(offset + it) % pool.size] }
        offset = (offset + count) % pool.size
        return round
    }
}

object AnswerMatcher {

    private val ignorable = Regex("[\\s\\p{Punct}]")

    private fun normalize(text: String) = text.lowercase().replace(ignorable, "")

    /** 인식된 문장 안에 정답 표현이 들어 있으면 정답으로 봅니다. ("정답은 서울이에요" → 정답) */
    fun isCorrect(heard: String?, quiz: Quiz): Boolean {
        if (heard.isNullOrBlank()) return false
        val normalized = normalize(heard)
        return quiz.answers.any { normalized.contains(normalize(it)) }
    }
}
