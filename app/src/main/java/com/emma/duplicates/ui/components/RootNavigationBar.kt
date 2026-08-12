package com.emma.duplicates.ui.components

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.core.designsystem.SecondaryText
import com.emma.duplicates.core.designsystem.SurfaceContainer

enum class RootDestination {
    HOME,
    RESULTS,
    EXCLUSIONS,
}

private data class RootNavigationItem(
    val destination: RootDestination,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
)

private val rootNavigationItems =
    listOf(
        RootNavigationItem(RootDestination.HOME, R.string.home, R.drawable.ic_nav_home),
        RootNavigationItem(RootDestination.RESULTS, R.string.results, R.drawable.ic_nav_results),
        RootNavigationItem(RootDestination.EXCLUSIONS, R.string.exclusions, R.drawable.ic_nav_exclusions),
    )

@Composable
fun RootNavigationBar(
    selectedDestination: RootDestination,
    onDestinationSelected: (RootDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val fontScale = LocalConfiguration.current.fontScale
        val horizontalPadding =
            when {
                maxWidth >= 600.dp -> 32.dp
                fontScale >= 1.5f -> 0.dp
                else -> 24.dp
            }
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 8.dp)
                    .navigationBarsPadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = SurfaceContainer,
            tonalElevation = 1.dp,
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                rootNavigationItems.forEach { item ->
                    val selected = item.destination == selectedDestination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onDestinationSelected(item.destination) },
                        icon = {
                            Icon(
                                painter = painterResource(item.iconRes),
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(item.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        alwaysShowLabel = true,
                        colors =
                            NavigationBarItemDefaults.colors(
                                selectedIconColor = Primary,
                                selectedTextColor = Primary,
                                unselectedIconColor = SecondaryText,
                                unselectedTextColor = SecondaryText,
                                indicatorColor = Color.Transparent,
                            ),
                    )
                }
            }
        }
    }
}
