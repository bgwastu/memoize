package net.wastu.memoize.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource

@Composable
fun AppIcon(@DrawableRes icon: Int, description: String? = null) {
    Icon(painterResource(icon), description)
}

@Composable
fun IconAction(@DrawableRes icon: Int, description: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled) { AppIcon(icon, description) }
}
