package com.stepcast.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Stepcast's title signature: extra-bold text with a signal-colored
 * full stop. ("Library." / "Up Next." / "Discover.")
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                append(".")
            }
        },
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier
    )
}

/**
 * The step mark — Stepcast's unifying glyph: three rising bars, a miniature
 * of the launcher's staircase. Placed before every section label.
 */
@Composable
fun StepMark(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(7.dp)
                .background(color, RoundedCornerShape(1.dp))
        )
        Box(
            Modifier
                .width(4.dp)
                .height(12.dp)
                .background(color, RoundedCornerShape(1.dp))
        )
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .background(color, RoundedCornerShape(1.dp))
        )
    }
}

/**
 * Podcast artwork that degrades to a folder glyph for local-folder feeds
 * with no art anywhere (no embedded pictures in any file). Pass the shape/
 * size through [modifier] exactly as you would to AsyncImage.
 */
@Composable
fun ArtworkOrFolder(
    imageUrl: String?,
    isLocalFolder: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    if (imageUrl == null && isLocalFolder) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.5f)
            )
        }
    } else {
        coil.compose.AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier
        )
    }
}

/** Friendly centered empty state with a signal-tinted icon. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier = Modifier.size(56.dp)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        StepMark(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}
