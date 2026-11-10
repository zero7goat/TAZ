package com.zero7goat.taz.features.notes.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.notes.data.NoteImageStore

// Supported syntax (stored as plain text in the note body):
//   **bold**   *italic*   ==highlight==   [text](https://address)
//   > quote    - [ ] task    - [x] done task
//   ![image](note-image:<generated name>)   on a line of its own: an image stored in TAZ
// This is a small, deliberate subset, not a full Markdown implementation.

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Quote(val text: String) : Block
    data class Task(val lineIndex: Int, val checked: Boolean, val text: String) : Block
    data class Image(val name: String) : Block
    data object Blank : Block
}

private val TaskPattern = Regex("^\\s*[-*] \\[([ xX])\\] ?(.*)$")
private val QuotePattern = Regex("^>\\s?(.*)$")
private val SafeUrlPattern = Regex("^https?://[^\\s/?#]+\\.[^\\s/?#]+\\S*$", RegexOption.IGNORE_CASE)

private fun parseBlocks(source: String): List<Block> =
    source.split('\n').mapIndexed { index, rawLine ->
        val line = rawLine.trimEnd('\r')
        val task = TaskPattern.matchEntire(line)
        val quote = QuotePattern.matchEntire(line)
        val imageName = NoteImageStore.imageNameInLine(line)
        when {
            line.isBlank() -> Block.Blank
            imageName != null -> Block.Image(imageName)
            task != null -> Block.Task(
                lineIndex = index,
                checked = task.groupValues[1].isNotBlank(),
                text = task.groupValues[2]
            )
            quote != null -> Block.Quote(quote.groupValues[1])
            else -> Block.Paragraph(line)
        }
    }

/**
 * Renders [source] in read-only form. Tapping a task's box calls [onSourceChange] with the
 * updated text; tapping a link opens it in the browser.
 */
@Composable
fun MarkdownText(
    source: String,
    onSourceChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
            // No app can open it; ignore rather than crash.
        }
    }
    val highlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)
    val blocks = remember(source) { parseBlocks(source) }

    fun toggleTask(block: Block.Task) {
        val lines = source.split('\n').toMutableList()
        val line = lines[block.lineIndex]
        lines[block.lineIndex] = if (block.checked) {
            line.replaceFirst(Regex("\\[[xX]\\]"), "[ ]")
        } else {
            line.replaceFirst("[ ]", "[x]")
        }
        onSourceChange(lines.joinToString("\n"))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                Block.Blank -> Spacer(Modifier.height(8.dp))

                is Block.Image -> NoteImage(name = block.name, modifier = Modifier.padding(vertical = 4.dp))

                is Block.Paragraph -> Text(
                    text = remember(block.text, highlightColor) {
                        inlineText(block.text, highlightColor, openLink)
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )

                is Block.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = remember(block.text, highlightColor) {
                            inlineText(block.text, highlightColor, openLink)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                is Block.Task -> Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (block.checked) TazIcons.CheckboxChecked else TazIcons.Checkbox,
                        contentDescription = if (block.checked) "Done" else "Not done",
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(24.dp)
                            .clickable { toggleTask(block) },
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = remember(block.text, highlightColor) {
                            inlineText(block.text, highlightColor, openLink)
                        },
                        color = if (block.checked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

private fun inlineText(
    text: String,
    highlightColor: Color,
    onOpenLink: (String) -> Unit
): AnnotatedString = buildAnnotatedString { appendInline(text, highlightColor, onOpenLink, depth = 0) }

private const val MaxNesting = 3

private fun AnnotatedString.Builder.appendInline(
    text: String,
    highlightColor: Color,
    onOpenLink: (String) -> Unit,
    depth: Int
) {
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.clear()
        }
    }

    var i = 0
    while (i < text.length) {
        if (depth < MaxNesting) {
            // **bold**
            if (text.startsWith("**", i)) {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(text.substring(i + 2, close), highlightColor, onOpenLink, depth + 1)
                    }
                    i = close + 2
                    continue
                }
            }
            // ==highlight==
            if (text.startsWith("==", i)) {
                val close = text.indexOf("==", i + 2)
                if (close > i + 2) {
                    flush()
                    withStyle(SpanStyle(background = highlightColor)) {
                        appendInline(text.substring(i + 2, close), highlightColor, onOpenLink, depth + 1)
                    }
                    i = close + 2
                    continue
                }
            }
            // *italic*  (the opening star must be followed, and the closing one preceded, by text)
            if (text[i] == '*' && i + 1 < text.length && !text[i + 1].isWhitespace() && text[i + 1] != '*') {
                val close = findItalicClose(text, i + 1)
                if (close > i + 1) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(text.substring(i + 1, close), highlightColor, onOpenLink, depth + 1)
                    }
                    i = close + 1
                    continue
                }
            }
            // [label](https://address)
            if (text[i] == '[') {
                val middle = text.indexOf("](", i + 1)
                if (middle > i + 1) {
                    val end = text.indexOf(')', middle + 2)
                    if (end > middle + 2) {
                        val label = text.substring(i + 1, middle)
                        val url = text.substring(middle + 2, end).trim()
                        if (SafeUrlPattern.matches(url)) {
                            flush()
                            val link = LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(textDecoration = TextDecoration.Underline)
                                ),
                                linkInteractionListener = LinkInteractionListener { onOpenLink(url) }
                            )
                            withLink(link) { append(label) }
                            i = end + 1
                            continue
                        }
                    }
                }
            }
        }
        plain.append(text[i])
        i++
    }
    flush()
}

/** Finds the single `*` that closes an italic run starting at [from], skipping `**` pairs. */
private fun findItalicClose(text: String, from: Int): Int {
    var j = from
    while (j < text.length) {
        if (text[j] == '*') {
            val partOfPair = (j + 1 < text.length && text[j + 1] == '*') || (j > 0 && text[j - 1] == '*')
            if (!partOfPair && !text[j - 1].isWhitespace()) return j
        }
        j++
    }
    return -1
}
