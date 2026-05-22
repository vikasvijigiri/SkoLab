package com.open.entropy.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import com.open.entropy.ui.theme.*
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
import android.util.TypedValue
import android.view.View
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import androidx.compose.ui.graphics.toArgb
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import org.commonmark.node.StrongEmphasis
import android.text.style.ForegroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit

@Composable
fun ScientificCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderWidth: Dp = 0.5.dp,
    borderColor: Color = GlassBorder,
    glowColor: Color = Color.Transparent,
    accentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "scale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onClick()
                    }
                ) else Modifier
            )
    ) {
        // Subtle Ambient Glow
        if (glowColor != Color.Transparent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(glowColor.copy(alpha = 0.05f), Color.Transparent),
                                center = center,
                                radius = size.maxDimension
                            ),
                            radius = size.maxDimension
                        )
                    }
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = GlassSurface,
            shape = RoundedCornerShape(8.dp),
            border = if (accentColor != null) {
                androidx.compose.foundation.BorderStroke(
                    borderWidth,
                    Brush.horizontalGradient(listOf(accentColor.copy(alpha = 0.5f), borderColor))
                )
            } else {
                androidx.compose.foundation.BorderStroke(borderWidth, borderColor)
            }
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                content = content
            )
        }
    }
}

@Composable
fun ScientificBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = Typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun ScientificTag(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = "#$text",
        modifier = modifier,
        style = Typography.labelSmall,
        color = ResQitTextSecondary,
        fontFamily = MonoFontFamily
    )
}

@Composable
fun MetricHighlight(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = label.uppercase(),
            style = Typography.labelSmall,
            color = ResQitTextSecondary,
            letterSpacing = 1.sp
        )
        Text(
            text = value,
            style = Typography.titleLarge,
            color = color,
            fontFamily = MonoFontFamily
        )
    }
}

@Composable
fun AIInsightBullet(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp, end = 8.dp)
                .size(4.dp)
                .background(ResQitAiInsight, RoundedCornerShape(50))
        )
        MarkdownText(
            markdown = text,
            color = ResQitTextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = ResQitTextPrimary,
    fontSize: TextUnit = 13.sp
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }

    val markwon = remember(context, fontSizePx, color) {
        val colorInt = color.toArgb()
        val accentInt = AccentTeal.toArgb()
        Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(fontSizePx) { builder ->
                builder.inlinesEnabled(true)
                builder.theme().textColor(colorInt)
            })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder.setFactory(StrongEmphasis::class.java) { _, _ ->
                        arrayOf<CharacterStyle>(
                            StyleSpan(Typeface.BOLD),
                            ForegroundColorSpan(accentInt)
                        )
                    }
                }
            })
            .build()
    }

    AndroidView(
        factory = { ctx -> TextView(ctx).apply { setLayerType(View.LAYER_TYPE_SOFTWARE, null) } },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            
            // 1. Convert standard bracket math delimiters to dollar delimiters
            var processed = markdown
                .replace("\\[", "$$")
                .replace("\\]", "$$")
                .replace("\\(", "$")
                .replace("\\)", "$")
            
            // 2. Double all backslashes so CommonMark parser doesn't eat LaTeX command prefixes
            processed = processed.replace("\\", "\\\\")
            
            markwon.setMarkdown(textView, processed)
        },
        modifier = modifier
    )
}
