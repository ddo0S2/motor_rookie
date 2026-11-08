package com.hyundai.motor_rookie.intervention

// ─────────────────────────────────────────────
// 곁 - 1단계 대화 / 2단계 환경 개입 공통 모델
// ─────────────────────────────────────────────

/** 퀴즈 종류 (기획서 5.3: 단순 퀴즈 / 따라 말하기 / 간단한 연산) */
enum class QuizType(val label: String) {
    TRIVIA("상식 퀴즈"),
    REPEAT("따라 말하기"),
    MATH("간단한 연산")
}

/**
 * @param prompt     TTS로 읽어줄 문장
 * @param display    화면에 크게 보여줄 문장
 * @param answers    정답으로 인정할 표현들 (숫자, 한글 숫자 모두)
 * @param answerText 화면에 보여줄 정답 (예: "7일")
 */
data class Quiz(
    val type: QuizType,
    val prompt: String,
    val display: String,
    val answers: List<String>,
    val answerText: String = answers.first()
)

/** 개입 단계. 파워냅·재출발은 다른 팀원 파트 */
enum class Stage(val label: String) {
    OBSERVE("괜찮아요"),
    TALK("1단계 · 대화"),
    CLIMATE("2단계 · 환경 개입"),
    REST("3단계 · 휴식 권장")
}

data class QuizResult(
    val quiz: Quiz,
    val heard: String?,
    val correct: Boolean,
    val noAnswer: Boolean,
    val responseMs: Long
)

/** 다른 파트(피로 점수, 비상 모드)로 보내는 이벤트 */
sealed interface InterventionEvent {
    /** 퀴즈 3문제 종료. penaltyPoints = 피로 점수에 더할 점수, talkSignal = s_talk(0~1) */
    data class QuizFinished(
        val results: List<QuizResult>,
        val penaltyPoints: Int,
        val talkSignal: Float
    ) : InterventionEvent

    /** 무응답 3회 연속 → 비상 모드 파트로 넘김 */
    data object Emergency : InterventionEvent

    data class Snoozed(val minutes: Int) : InterventionEvent
}

/** 퀴즈 중 버튼/음성 명령 */
enum class QuizCommand { SNOOZE, SKIP, STOP }

enum class QuizPhase { INTRO, SPEAKING, LISTENING, FEEDBACK }

/** 피드백 화면 종류 (Stitch: 정답 = 초록 체크, 오답/무응답 = 주황 i, 안내 = 회색) */
enum class FeedbackKind { NONE, CORRECT, WRONG, NO_ANSWER, INFO }

data class QuizUi(
    val visible: Boolean = false,
    val index: Int = 0,
    val total: Int = 3,
    val type: QuizType? = null,
    val question: String = "",
    val expected: String = "",          // 시뮬레이션 버튼용 정답
    val phase: QuizPhase = QuizPhase.INTRO,
    val heard: String = "",
    val feedback: String = "",          // 큰 글씨 (예: "정답이에요! 7일")
    val feedbackSub: String = "",       // 작은 글씨 (예: "다음 문제로 넘어갈게요")
    val feedbackKind: FeedbackKind = FeedbackKind.NONE,
    val listenStartedAt: Long = 0L,
    val listenMs: Long = 8_000L,
    val manualInput: Boolean = false    // 시뮬레이션(버튼으로 대답) 모드인지
)

/** 배너 종류에 따라 아이콘·색·버튼이 바뀐다 */
enum class BannerKind {
    CLIMATE,   // 공조 변경 + [되돌리기]
    WINDOW,    // 창문 열림 + [닫기]
    RESTORED,  // 원래대로 / 경유지 추가 완료 (초록 체크)
    INFO,      // 안내 (회색)
    ERROR      // 실패 (빨강)
}

/** 3단계 쉼터 안내 카드 (Stitch 3_2) */
data class RestCardUi(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val current: RestOption? = null,
    val next: RestOption? = null,
    val message: String = "",          // 못 찾았을 때 등
    val sample: Boolean = false        // 예시 데이터 사용 중
)

data class Banner(
    val id: Long,
    val text: String,
    val sub: String,
    val kind: BannerKind,
    val undoable: Boolean
)

data class InterventionUi(
    val score: Int = 0,
    val stage: Stage = Stage.OBSERVE,
    val status: String = "지금은 괜찮아요",
    val quiz: QuizUi = QuizUi(),
    val banner: Banner? = null,
    val climateApplied: Boolean = false,
    val windowOpened: Boolean = false,
    val restCard: RestCardUi = RestCardUi(),
    val snoozedUntil: Long = 0L,
    val talkSignal: Float = 0f,
    val log: List<String> = emptyList()
)
