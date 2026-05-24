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
            
            // 0. Globally clean up MathML tags and convert them into clean standard LaTeX
            val cleaned = cleanScientificText(markdown)
            
            // 1. Convert standard bracket math delimiters and single dollar delimiters to double dollars
            var processed = cleaned
                .replace("\\[", "$$")
                .replace("\\]", "$$")
                .replace("\\(", "$$")
                .replace("\\)", "$$")
                .replace(Regex("(?<!\\$)\\$(?!\\$)"), "\\$\\$")
            
            // 2. Double all backslashes so CommonMark parser doesn't eat LaTeX command prefixes
            // processed = processed.replace("\\", "\\\\")
            
            markwon.setMarkdown(textView, processed)
        },
        modifier = modifier
    )
}

/**
 * Robust MathML-to-LaTeX converter.
 * Converts raw XML tags (like <mml:msub>... </mml:msub>) into elegant LaTeX formulas ($...$)
 * that render cleanly inside the Android MarkdownText component.
 */
sealed class MathMLToken {
    data class StartTag(val name: String, val rawTag: String) : MathMLToken()
    data class EndTag(val name: String, val rawTag: String) : MathMLToken()
    data class TextToken(val content: String) : MathMLToken()
}

class MathMLNode(
    val name: String,
    val rawStart: String = "",
    var rawEnd: String = "",
    val textContent: String = "",
    val children: MutableList<MathMLNode> = mutableListOf()
)

fun tokenize(input: String): List<MathMLToken> {
    val tokens = mutableListOf<MathMLToken>()
    var i = 0
    val n = input.length
    while (i < n) {
        val openBracket = input.indexOf('<', i)
        if (openBracket == -1) {
            val text = input.substring(i)
            if (text.isNotEmpty()) {
                tokens.add(MathMLToken.TextToken(text))
            }
            break
        }
        if (openBracket > i) {
            val text = input.substring(i, openBracket)
            if (text.isNotEmpty()) {
                tokens.add(MathMLToken.TextToken(text))
            }
        }
        val closeBracket = input.indexOf('>', openBracket)
        if (closeBracket == -1) {
            tokens.add(MathMLToken.TextToken(input.substring(openBracket)))
            break
        }
        val rawTag = input.substring(openBracket, closeBracket + 1)
        val tagContent = input.substring(openBracket + 1, closeBracket).trim()
        if (tagContent.startsWith("/")) {
            val rawName = tagContent.substring(1).substringBefore(" ").trim().lowercase()
            val name = if (rawName.contains(":")) rawName.substringAfter(":") else rawName
            tokens.add(MathMLToken.EndTag(name, rawTag))
        } else if (tagContent.endsWith("/")) {
            val rawName = tagContent.substring(0, tagContent.length - 1).substringBefore(" ").trim().lowercase()
            val name = if (rawName.contains(":")) rawName.substringAfter(":") else rawName
            tokens.add(MathMLToken.StartTag(name, rawTag))
            tokens.add(MathMLToken.EndTag(name, rawTag))
        } else {
            val rawName = tagContent.substringBefore(" ").trim().lowercase()
            val name = if (rawName.contains(":")) rawName.substringAfter(":") else rawName
            tokens.add(MathMLToken.StartTag(name, rawTag))
        }
        i = closeBracket + 1
    }
    return tokens
}

fun parseTokens(tokens: List<MathMLToken>): List<MathMLNode> {
    val roots = mutableListOf<MathMLNode>()
    val stack = mutableListOf<MathMLNode>()
    for (token in tokens) {
        when (token) {
            is MathMLToken.TextToken -> {
                val node = MathMLNode("text", textContent = token.content)
                if (stack.isEmpty()) {
                    roots.add(node)
                } else {
                    stack.last().children.add(node)
                }
            }
            is MathMLToken.StartTag -> {
                val node = MathMLNode(token.name, rawStart = token.rawTag)
                if (stack.isEmpty()) {
                    roots.add(node)
                } else {
                    stack.last().children.add(node)
                }
                stack.add(node)
            }
            is MathMLToken.EndTag -> {
                val index = stack.indexOfLast { it.name == token.name }
                if (index != -1) {
                    stack[index].rawEnd = token.rawTag
                    while (stack.size > index) {
                        stack.removeAt(stack.size - 1)
                    }
                }
            }
        }
    }
    return roots
}

fun nodeToLatex(node: MathMLNode): String {
    if (node.name == "text") {
        return node.textContent
    }
    val childLatex = node.children.map { nodeToLatex(it) }
    
    return when (node.name) {
        "mi", "mn", "mo" -> {
            val content = childLatex.joinToString("").trim()
            val greekLetters = setOf(
                "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", 
                "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi", "rho", 
                "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega",
                "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta",
                "Iota", "Kappa", "Lambda", "Mu", "Nu", "Xi", "Omicron", "Pi", "Rho",
                "Sigma", "Tau", "Upsilon", "Phi", "Chi", "Psi", "Omega"
            )
            if (greekLetters.contains(content)) {
                "\\$content"
            } else if (content == "±") {
                "\\pm"
            } else if (content == "×") {
                "\\times"
            } else if (content == "÷") {
                "\\div"
            } else {
                content
            }
        }
        "mtext" -> {
            val content = childLatex.joinToString("")
            "\\text{$content}"
        }
        "mrow" -> {
            childLatex.joinToString("")
        }
        "msub" -> {
            if (node.children.size >= 2) {
                val base = node.children.dropLast(1).map { nodeToLatex(it) }.joinToString("")
                val sub = nodeToLatex(node.children.last())
                "${base}_{${sub}}"
            } else {
                val content = childLatex.joinToString("")
                val regex = Regex("^([a-zA-Z]+)([0-9]+)$")
                val match = regex.matchEntire(content)
                if (match != null) {
                    val base = match.groupValues[1]
                    val sub = match.groupValues[2]
                    "${base}_{${sub}}"
                } else {
                    content
                }
            }
        }
        "msup" -> {
            if (node.children.size >= 2) {
                val base = node.children.dropLast(1).map { nodeToLatex(it) }.joinToString("")
                val sup = nodeToLatex(node.children.last())
                "${base}^{${sup}}"
            } else {
                val content = childLatex.joinToString("")
                val regex = Regex("^([a-zA-Z]+)([0-9]+)$")
                val match = regex.matchEntire(content)
                if (match != null) {
                    val base = match.groupValues[1]
                    val sup = match.groupValues[2]
                    "${base}^{${sup}}"
                } else {
                    content
                }
            }
        }
        "msubsup" -> {
            if (node.children.size >= 3) {
                val base = node.children.dropLast(2).map { nodeToLatex(it) }.joinToString("")
                val sub = nodeToLatex(node.children[node.children.size - 2])
                val sup = nodeToLatex(node.children.last())
                "${base}_{${sub}}^{${sup}}"
            } else if (node.children.size == 2) {
                val base = nodeToLatex(node.children[0])
                val sub = nodeToLatex(node.children[1])
                "${base}_{${sub}}"
            } else {
                childLatex.joinToString("")
            }
        }
        "mfrac" -> {
            if (node.children.size >= 2) {
                val num = nodeToLatex(node.children[0])
                val den = nodeToLatex(node.children[1])
                "\\frac{${num}}{${den}}"
            } else {
                childLatex.joinToString("")
            }
        }
        "msqrt" -> {
            "\\sqrt{${childLatex.joinToString("")}}"
        }
        "mroot" -> {
            if (node.children.size >= 2) {
                val base = node_to_latex_wrapper(node.children[0])
                val index = node_to_latex_wrapper(node.children[1])
                "\\sqrt[${index}]{${base}}"
            } else {
                "\\sqrt{${childLatex.joinToString("")}}"
            }
        }
        "mover" -> {
            if (node.children.size >= 2) {
                val base = nodeToLatex(node.children[0])
                val over = nodeToLatex(node.children[1])
                when (over.trim()) {
                    "¯", "bar" -> "\\bar{${base}}"
                    "→", "vec" -> "\\vec{${base}}"
                    "~", "tilde" -> "\\tilde{${base}}"
                    "^", "hat" -> "\\hat{${base}}"
                    "." -> "\\dot{${base}}"
                    ".." -> "\\ddot{${base}}"
                    else -> "\\overset{${over}}{${base}}"
                }
            } else {
                childLatex.joinToString("")
            }
        }
        "munder" -> {
            if (node.children.size >= 2) {
                val base = nodeToLatex(node.children[0])
                val under = nodeToLatex(node.children[1])
                "\\underset{${under}}{${base}}"
            } else {
                childLatex.joinToString("")
            }
        }
        "munderover" -> {
            if (node.children.size >= 3) {
                val base = nodeToLatex(node.children[0])
                val under = nodeToLatex(node.children[1])
                val over = nodeToLatex(node.children[2])
                "\\munderover{${under}}{${over}}{${base}}"
            } else {
                childLatex.joinToString("")
            }
        }
        "math" -> {
            childLatex.joinToString("")
        }
        else -> {
            childLatex.joinToString("")
        }
    }
}

fun node_to_latex_wrapper(node: MathMLNode): String {
    return nodeToLatex(node)
}

fun reconstructNonMathNode(node: MathMLNode): String {
    if (node.name == "text") {
        return node.textContent
    }
    val childrenContent = node.children.joinToString("") { reconstructNonMathNode(it) }
    val start = if (node.rawStart.isNotEmpty()) node.rawStart else "<${node.name}>"
    val end = if (node.rawEnd.isNotEmpty()) node.rawEnd else "</${node.name}>"
    return "$start$childrenContent$end"
}

fun cleanScientificText(text: String): String {
    var cleaned = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        
    if (!cleaned.contains("<")) {
        return cleaned
    }
    val tokens = tokenize(cleaned)
    val roots = parseTokens(tokens)
    
    val mathTags = setOf(
        "math", "mrow", "mi", "mn", "mo", "mtext", "msub", "msup", "msubsup",
        "mfrac", "msqrt", "mroot", "mover", "munder", "munderover"
    )
    
    val resultParts = mutableListOf<String>()
    for (root in roots) {
        if (root.name == "text") {
            resultParts.add(root.textContent)
        } else if (mathTags.contains(root.name)) {
            val latex = nodeToLatex(root).trim().replace("$", "")
            if (latex.isNotEmpty()) {
                resultParts.add("$$" + latex + "$$")
            }
        } else {
            resultParts.add(reconstructNonMathNode(root))
        }
    }
    return resultParts.joinToString("")
}
