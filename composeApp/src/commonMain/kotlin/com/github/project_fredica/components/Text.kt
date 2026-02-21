package com.github.project_fredica.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em

@Composable
fun TextSm(
    text: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Text(
        text = text,
        fontSize = 1.em,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        fontWeight = fontWeight
    )
}

@Composable
fun TextXsGray(
    text: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Text(
        text = text,
        fontSize = 0.75.em,
        modifier = modifier,
        color = Color.Gray,
        overflow = overflow,
        maxLines = maxLines,
        fontWeight = fontWeight,
    )
}