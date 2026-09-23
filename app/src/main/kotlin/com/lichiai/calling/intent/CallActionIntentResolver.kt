package com.lichiai.calling.intent

import com.lichiai.calling.action.StructuredCallAction
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import java.util.Locale

/**
 * Natural Language Call Action Intent Resolver.
 * Parses user speech and text in Hindi, English, and Hinglish into StructuredCallActions.
 */
class CallActionIntentResolver {

    // Regex patterns for Call Handling Controls
    private val ANSWER_SPEAKER_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(speaker|loudspeaker)\\s*pe\\s*(utha\\s*lo|uthao|answer|receive\\s*karo|receive\\s*kar\\s*lo|pick\\s*up|daal\\s*do)$"),
        Regex("(?i)^(lichi\\s+)?speaker\\s*pe\\s*(daal\\s*do|rakho|karo)$"),
        Regex("(?i)^speaker\\s*pe\\s*utha\\s*lo$"),
        Regex("(?i)^speaker\\s*pe\\s*daal\\s*do$")
    )

    private val ANSWER_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(phone|call)?\\s*(utha\\s*lo|uthao|pick\\s*up|answer|accept)(\\s*(the)?\\s*call)?$"),
        Regex("(?i)^(lichi\\s+)?(answer|accept|pick\\s*up)(\\s*the\\s*call|\\s*it)?$"),
        Regex("(?i)^(lichi\\s+)?(call|phone)?\\s*(receive\\s*karo|receive\\s*kar\\s*lo|receive|receive\\s*karna\\s*hai)$"),
        Regex("(?i)^(lichi\\s+)?(take\\s*the\\s*call|take\\s*call|take\\s*it)$"),
        Regex("(?i)^(lichi\\s+)?(haan\\s*utha\\s*lo|haan\\s*pick\\s*up|haan\\s*answer\\s*kar\\s*do)$"),
        Regex("(?i)^utha\\s*lo$"),
        Regex("(?i)^uthao$"),
        Regex("(?i)^phone\\s*utha\\s*lo$"),
        Regex("(?i)^call\\s*utha\\s*lo$"),
        Regex("(?i)^answer$"),
        Regex("(?i)^answer\\s*kar\\s*do$"),
        Regex("(?i)^pick\\s*up$"),
        Regex("(?i)^pick\\s*it\\s*up$"),
        Regex("(?i)^receive\\s*kar\\s*lo$"),
        Regex("(?i)^receive\\s*karo$"),
        Regex("(?i)^call\\s*receive\\s*karo$"),
        Regex("(?i)^call\\s*receive\\s*karna\\s*hai$"),
        Regex("(?i)^utha\\s*lijiye$"),
        Regex("(?i)^utha\\s*lo\\s*yaar$")
    )

    private val REJECT_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(call|phone)?\\s*(reject|decline|mat\\s*uthao|mat\\s*uthana|drop|ignore)(\\s*(kar\\s*do|karo|it|the\\s*call))?$"),
        Regex("(?i)^(lichi\\s+)?(reject|decline|drop|ignore)(\\s*the\\s*call|\\s*it)?$"),
        Regex("(?i)^(lichi\\s+)?(kaat\\s*do|kato|kat\\s*do|cut\\s*kar\\s*do|cut\\s*karo|phone\\s*kaat\\s*do|call\\s*kaat\\s*do)$"),
        Regex("(?i)^(lichi\\s+)?(nahi\\s*uthana|nahi\\s*uthana\\s*hai|don't\\s*answer|dont\\s*answer)$"),
        Regex("(?i)^reject\\s*kar\\s*do$"),
        Regex("(?i)^reject$"),
        Regex("(?i)^kaat\\s*do$"),
        Regex("(?i)^call\\s*kaat\\s*do$"),
        Regex("(?i)^phone\\s*kaat\\s*do$"),
        Regex("(?i)^cut\\s*kar\\s*do$"),
        Regex("(?i)^cut\\s*karo$"),
        Regex("(?i)^mat\\s*uthao$"),
        Regex("(?i)^mat\\s*uthana$"),
        Regex("(?i)^nahi\\s*uthana$"),
        Regex("(?i)^ignore\\s*kar\\s*do$"),
        Regex("(?i)^ignore$"),
        Regex("(?i)^decline$"),
        Regex("(?i)^decline\\s*it$"),
        Regex("(?i)^don't\\s*answer$"),
        Regex("(?i)^unknown\\s*number\\s*hai\\s*(to\\s*)?reject\\s*(kar\\s*do|karo)$")
    )

    private val AFFIRMATIVE_PATTERNS = listOf(
        Regex("(?i)^(haan|ha|yes|yeah|yep|sure|bilkul|ok|okay|kar\\s*lo|theek\\s*hai)$")
    )

    private val NEGATIVE_PATTERNS = listOf(
        Regex("(?i)^(nahi|nah|no|nope|na|mat\\s*karo)$")
    )

    private val CANCEL_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(cancel|chhodo|chodo|baad\\s*mein|nothing|never\\s*mind|ruk\\s*jao|rehne\\s*do)$")
    )

    private val QUERY_CALLER_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(kisko|kiska|kis\\s*ka|kaun|who\\s*is|who)\\s*(call|phone)?\\s*(aa\\s*raha\\s*hai|kar\\s*raha\\s*hai|calling|hai|is\\s*it)?\\??$"),
        Regex("(?i)^kaun\\s*hai\\??$"),
        Regex("(?i)^kiska\\s*call\\s*hai\\??$"),
        Regex("(?i)^who\\s*is\\s*calling\\??$")
    )

    private val QUERY_NUMBER_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(kya\\s*number\\s*hai|number\\s*kya\\s*hai|what\\s*is\\s*the\\s*number|phone\\s*number\\s*kya\\s*hai)\\??$"),
        Regex("(?i)^number\\s*kya\\s*hai\\??$"),
        Regex("(?i)^what\\s*is\\s*the\\s*number\\??$")
    )

    private val END_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(call|phone)?\\s*(kaat\\s*do|kato|kat\\s*do|disconnect|end|hang\\s*up|cancel)(\\s*(kar\\s*do|karo|the\\s*call|call))?$"),
        Regex("(?i)^(lichi\\s+)?(end\\s*call|hang\\s*up|disconnect|call\\s*end|phone\\s*kato)$"),
        Regex("(?i)^call\\s*(cancel\\s*karo|kaat\\s*do|kato|end\\s*karo)$")
    )

    private val SPEAKER_ON_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(speaker|loudspeaker)\\s*(on\\s*karo|on\\s*kar\\s*do|on|kholo|enable)$"),
        Regex("(?i)^(lichi\\s+)?enable\\s*speaker$"),
        Regex("(?i)^speaker\\s*on$")
    )

    private val SPEAKER_OFF_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(speaker|loudspeaker)\\s*(off\\s*karo|off\\s*kar\\s*do|off|band\\s*karo|band\\s*kar\\s*do|disable)$"),
        Regex("(?i)^(lichi\\s+)?disable\\s*speaker$"),
        Regex("(?i)^speaker\\s*off$")
    )

    private val MUTE_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(mic|microphone|call)?\\s*(mute\\s*kar\\s*do|mute\\s*karo|mute)$"),
        Regex("(?i)^mute$"),
        Regex("(?i)^mute\\s*kar\\s*do$")
    )

    private val UNMUTE_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(mic|microphone|call)?\\s*(unmute\\s*kar\\s*do|unmute\\s*karo|unmute|awaz\\s*on\\s*karo)$"),
        Regex("(?i)^unmute$"),
        Regex("(?i)^unmute\\s*kar\\s*do$")
    )

    private val HOLD_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(call|phone)?\\s*(hold\\s*pe\\s*daal\\s*do|hold\\s*pe\\s*rakho|hold\\s*karo|hold)$"),
        Regex("(?i)^hold\\s*call$"),
        Regex("(?i)^hold$")
    )

    private val RESUME_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(call|phone)?\\s*(wapas\\s*resume\\s*karo|resume\\s*call|resume|unhold)$"),
        Regex("(?i)^resume$"),
        Regex("(?i)^unhold$")
    )

    private val ASK_LICHI_PATTERNS = listOf(
        Regex("(?i)^(lichi\\s+)?(answer\\s*karun\\s*ya\\s*reject|kya\\s*karun|ask\\s*lichi|what\\s*should\\s*i\\s*do)\\??$"),
        Regex("(?i)^kya\\s*karun\\??$"),
        Regex("(?i)^ask\\s*lichi$")
    )

    private val DISAMBIGUATION_PATTERNS = listOf(
        Regex("(?i)^(pehla|first|1st|number\\s*one|1)(\\s*wala|\\s*one)?$"),
        Regex("(?i)^(dusra|doosra|second|2nd|number\\s*two|2)(\\s*wala|\\s*one)?$"),
        Regex("(?i)^(teesra|tisra|third|3rd|number\\s*three|3)(\\s*wala|\\s*one)?$")
    )

    fun resolve(rawInput: String, session: com.lichiai.calling.state.CallSessionInfo? = null): StructuredCallAction? {
        val text = rawInput.trim()
        if (text.isBlank()) return null

        val isDecisionContext = session?.callState?.isDecisionPending == true ||
                session?.isWaitingForDecision == true ||
                session?.callState?.isRinging == true

        // 1. Check Answer with Speaker
        if (ANSWER_SPEAKER_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.AnswerCall(enableSpeaker = true)
        }

        // 2. Check Standard Answer
        if (ANSWER_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.AnswerCall(enableSpeaker = false)
        }

        // 3. In Decision Context: Check Affirmative ("haan", "yes", "sure", etc.)
        if (isDecisionContext && AFFIRMATIVE_PATTERNS.any { it.matches(text) }) {
            val q = session?.decisionQuestion ?: ""
            val isClarifyingReject = session?.isClarifyingNegative == true || 
                ((q.contains("Reject kar du", ignoreCase = true) || q.contains("Reject kar doon", ignoreCase = true)) && !q.contains("utha", ignoreCase = true))
            return if (isClarifyingReject) {
                StructuredCallAction.RejectCall
            } else {
                StructuredCallAction.AnswerCall(enableSpeaker = false)
            }
        }

        // 4. Check explicit Reject ("kaat do", "reject kar do", "mat uthao", "cut karo")
        if (REJECT_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.RejectCall
        }

        // 5. In Decision Context: Check Negative ("nahi", "no", etc.)
        // Per User Requirement 11: If ambiguous ("Uthaun ya reject karun?"), do NOT automatically reject. Clarify first!
        if (isDecisionContext && NEGATIVE_PATTERNS.any { it.matches(text) }) {
            val q = session?.decisionQuestion ?: ""
            val isClarifyingReject = session?.isClarifyingNegative == true || 
                q.contains("Reject kar du", ignoreCase = true)
            return if (isClarifyingReject) {
                StructuredCallAction.CancelDecision
            } else {
                StructuredCallAction.ClarifyNegativeDecision
            }
        }

        // 6. In Decision Context: Check Cancel / Interrupt ("cancel", "chhodo", etc.)
        if (CANCEL_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.CancelDecision
        }

        // 7. Check Caller Query ("Kaun phone kar raha hai?", "Kaun hai?")
        if (QUERY_CALLER_PATTERNS.any { it.matches(text) }) {
            return if (isDecisionContext) StructuredCallAction.QueryCallerIdentity else StructuredCallAction.QueryCallState
        }

        // 8. Check Number Query ("Number kya hai?")
        if (QUERY_NUMBER_PATTERNS.any { it.matches(text) }) {
            return if (isDecisionContext) StructuredCallAction.QueryCallerNumber else StructuredCallAction.QueryCallState
        }

        // 9. Check End Call
        if (END_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.EndCall
        }

        // 10. Check Speaker On / Off
        if (SPEAKER_ON_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.EnableSpeaker
        }
        if (SPEAKER_OFF_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.DisableSpeaker
        }

        // 11. Check Mute / Unmute
        if (MUTE_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.MuteCall
        }
        if (UNMUTE_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.UnmuteCall
        }

        // 12. Check Hold / Resume
        if (HOLD_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.HoldCall
        }
        if (RESUME_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.ResumeCall
        }

        // 13. Check Ask Lichi Decision
        if (ASK_LICHI_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.AskLichiCallDecision
        }

        // 14. Check Disambiguation selection ("pehla wala", "second one")
        if (DISAMBIGUATION_PATTERNS.any { it.matches(text) }) {
            return StructuredCallAction.ResolveDisambiguation(text)
        }

        // 15. Check Outgoing Dialing via CallIntentResolver
        val callIntentResolver = CallIntentResolver()
        val legacyIntent = callIntentResolver.resolve(text)
        return when (legacyIntent.action) {
            CallAction.CALL_CONTACT -> {
                if (legacyIntent.targetText.isNotBlank()) {
                    StructuredCallAction.DialContact(legacyIntent.targetText, legacyIntent.simSlot)
                } else null
            }
            CallAction.CALL_NUMBER -> {
                val num = legacyIntent.phoneNumber?.takeIf { it.isNotBlank() } ?: legacyIntent.targetText
                if (num.isNotBlank()) {
                    StructuredCallAction.DialNumber(num, legacyIntent.simSlot)
                } else null
            }
            else -> null
        }
    }
}
