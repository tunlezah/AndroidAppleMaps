package com.mapsdroid.directions

import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.ManeuverType
import kotlin.math.abs

/**
 * Apple's Directions API returns localized instruction text but no maneuver-type enum. We infer a
 * type from two signals: keywords in the (English) instruction text, and, when the geometry allows,
 * the turn angle between the incoming and outgoing path headings. Text wins when it is decisive
 * (e.g. "U-turn"); geometry disambiguates "turn" vs "slight" vs "sharp".
 *
 * This is intentionally best-effort: the type only drives the maneuver icon and how early we
 * announce. The spoken instruction is always Apple's own text, so a misclassified icon never
 * produces a wrong spoken direction.
 */
object ManeuverParser {

    private val roadNameRegex = Regex("""\bonto\s+(.+?)(?:\s+toward\b.*)?$""", RegexOption.IGNORE_CASE)

    fun parse(
        instruction: String,
        incomingHeading: Double?,
        outgoingHeading: Double?,
        isFirst: Boolean,
        isLast: Boolean,
    ): ManeuverType {
        val text = instruction.lowercase()
        return when {
            isLast || text.contains("arrive") || text.contains("destination") -> ManeuverType.ARRIVE
            isFirst || text.startsWith("head") || text.startsWith("depart") || text.startsWith("start") -> ManeuverType.DEPART
            text.contains("u-turn") || text.contains("make a u") -> ManeuverType.UTURN
            text.contains("roundabout") || text.contains("rotary") -> ManeuverType.ROUNDABOUT
            text.contains("merge") -> ManeuverType.MERGE
            text.contains("keep left") -> ManeuverType.KEEP_LEFT
            text.contains("keep right") -> ManeuverType.KEEP_RIGHT
            text.contains("fork") && text.contains("left") -> ManeuverType.FORK_LEFT
            text.contains("fork") && text.contains("right") -> ManeuverType.FORK_RIGHT
            text.contains("slight") && text.contains("left") -> ManeuverType.SLIGHT_LEFT
            text.contains("slight") && text.contains("right") -> ManeuverType.SLIGHT_RIGHT
            text.contains("sharp") && text.contains("left") -> ManeuverType.SHARP_LEFT
            text.contains("sharp") && text.contains("right") -> ManeuverType.SHARP_RIGHT
            text.contains("left") -> refineByAngle(incomingHeading, outgoingHeading, left = true)
            text.contains("right") -> refineByAngle(incomingHeading, outgoingHeading, left = false)
            text.contains("continue") || text.contains("straight") -> ManeuverType.CONTINUE
            else -> classifyByAngleOnly(incomingHeading, outgoingHeading)
        }
    }

    fun roadName(instruction: String): String? =
        roadNameRegex.find(instruction.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Signed turn angle in degrees: positive = right, negative = left. Null if unknown. */
    fun turnAngle(incoming: Double?, outgoing: Double?): Double? {
        if (incoming == null || outgoing == null) return null
        var delta = outgoing - incoming
        while (delta > 180) delta -= 360
        while (delta < -180) delta += 360
        return delta
    }

    fun headingOf(path: List<GeoPoint>, atStart: Boolean): Double? {
        if (path.size < 2) return null
        return if (atStart) path[0].bearingTo(path[1]) else path[path.size - 2].bearingTo(path.last())
    }

    private fun refineByAngle(incoming: Double?, outgoing: Double?, left: Boolean): ManeuverType {
        val angle = turnAngle(incoming, outgoing) ?: return if (left) ManeuverType.TURN_LEFT else ManeuverType.TURN_RIGHT
        val mag = abs(angle)
        return when {
            mag < 25 -> if (left) ManeuverType.SLIGHT_LEFT else ManeuverType.SLIGHT_RIGHT
            mag > 115 -> if (left) ManeuverType.SHARP_LEFT else ManeuverType.SHARP_RIGHT
            else -> if (left) ManeuverType.TURN_LEFT else ManeuverType.TURN_RIGHT
        }
    }

    private fun classifyByAngleOnly(incoming: Double?, outgoing: Double?): ManeuverType {
        val angle = turnAngle(incoming, outgoing) ?: return ManeuverType.UNKNOWN
        val mag = abs(angle)
        if (mag < 20) return ManeuverType.CONTINUE
        val left = angle < 0
        return refineByAngle(incoming, outgoing, left)
    }
}
