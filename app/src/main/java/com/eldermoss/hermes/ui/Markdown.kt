package com.eldermoss.hermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small markdown subset renderer: headings, **bold**, *italic*, `code`,
 * fenced code blocks, bullets. No external markdown library — the payload is
 * agent prose, not documents. Unknown syntax degrades to plain text.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { splitBlocks(markdown) }
    Column(modifier) {
        for (block in blocks) {
            when (block) {
                is Block.Code -> CodeBlock(block.lines)
                is Block.Heading -> Text(
                    inline(block.text),
                    fontSize = when (block.level) {
                        1 -> 20.sp; 2 -> 18.sp; else -> 16.sp
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                is Block.Bullet -> Text(
                    inline("•  " + block.text),
                    modifier = Modifier.padding(start = 8.dp, bottom = 1.dp),
                )
                is Block.Paragraph -> Text(
                    inline(block.text),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        for (line in lines) {
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Code(val lines: List<String>) : Block
}

private fun splitBlocks(md: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = md.lines()
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) {
            out.add(Block.Paragraph(para.toString().trim()))
            para.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flushPara()
                i++
                val code = ArrayList<String>()
                while (i < lines.size && !lines[i].startsWith("```")) { code.add(lines[i]); i++ }
                i++ // closing fence
                out.add(Block.Code(code))
            }
            line.startsWith("#") -> {
                flushPara()
                val level = line.takeWhile { it == '#' }.length.coerceAtMost(3)
                out.add(Block.Heading(level, line.dropWhile { it == '#' }.trim()))
                i++
            }
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> {
                flushPara()
                out.add(Block.Bullet(line.substring(2).trim()))
                i++
            }
            line.isBlank() -> { flushPara(); i++ }
            else -> { para.append(line).append('\n'); i++ }
        }
    }
    flushPara()
    return out
}

/** Inline styles: **bold**, *italic*, `code`. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x20808080))) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '*' && i + 1 < text.length && text[i + 1] != '*' && text[i + 1] != ' ' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
