package app.blue.sms.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The thin outline icons from the design (24×24, 1.7 stroke, round caps). */
object BlueIcons {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Home = icon("home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
    val Review = icon("review", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", "m8 12 3 3 5-6")
    val Entries = icon("entries", "M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01")
    val Insights = icon("insights", "M4 20V10M10 20V4M16 20v-7M22 20H2")
    val Spam = icon("spam", "M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z")
    /** A cog (Lucide "settings", ISC licence). */
    val Settings = icon(
        "settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
    )
    val Back = icon("back", "M19 12H5M11 6l-6 6 6 6")
    val ChevronRight = icon("chevron_right", "m9 6 6 6-6 6")
    val ChevronLeft = icon("chevron_left", "m15 6-6 6 6 6")
    val ChevronDown = icon("chevron_down", "m6 9 6 6 6-6")
    val ChevronUp = icon("chevron_up", "m6 15 6-6 6 6")
    val Check = icon("check", "m5 12 5 5 9-10")
    val Star = icon("star", "m12 3 2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z")
    val StarFilled = icon("star_filled", "m12 3 2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z", filled = true)
    val Refresh = icon("refresh", "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7")
}
