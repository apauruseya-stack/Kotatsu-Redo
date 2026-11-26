package org.koitharu.kotatsu.main.ui.nav

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.koitharu.kotatsu.R

data class FloatingNavBarItem(
	@IdRes val id: Int,
	@StringRes val titleRes: Int,
	@DrawableRes val icon: Int,
	val badgeCount: Int = 0,
)

data class FloatingNavBarColors(
	val container: Int,
	val selectedContainer: Int,
	val selectedContent: Int,
	val unselectedContent: Int,
)

// The M3 Expressive default spatial spring. Snappier than Compose's own default, and shared by the
// icon tint, the label expand and the sibling resize so they all move on one beat.
private val SpringFloat = spring<Float>(dampingRatio = 0.9f, stiffness = 380f)
private val SpringColor = spring<Color>(dampingRatio = 0.9f, stiffness = 380f)
private val SpringSize = spring<IntSize>(dampingRatio = 0.9f, stiffness = 380f)

// Liquid Glass active indicator lens spring (damping ratio 0.6f, stiffness 220f)
private val SpringLensDp = spring<Dp>(dampingRatio = 0.6f, stiffness = 220f)

@Composable
fun FloatingNavBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	colors: FloatingNavBarColors,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	showContinue: Boolean = false,
	isLiquidGlass: Boolean = false,
	onContinueClick: () -> Unit = {},
) {
	if (items.isEmpty()) return

	BoxWithConstraints(
		modifier = modifier.fillMaxWidth(),
		contentAlignment = Alignment.Center,
	) {
		// 48dp per destination is the minimum accessible touch target.
		val iconOnlyBarWidth = 16.dp +
			(54.dp * items.size) +
			(2.dp * (items.size - 1).coerceAtLeast(0))
		val continueWidth = 48.dp + 8.dp
		val stackContinue = showContinue && (iconOnlyBarWidth + continueWidth > this@BoxWithConstraints.maxWidth)
		val showSelectedLabel = showLabels

		if (stackContinue) {
			Column(
				horizontalAlignment = Alignment.End,
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				FloatingContinueVisibility(
					visible = true,
					colors = colors,
					isLiquidGlass = isLiquidGlass,
					onContinueClick = onContinueClick,
				)
				FloatingNavItemsSurface(
					items = items,
					selectedId = selectedId,
					showSelectedLabel = showSelectedLabel,
					colors = colors,
					isLiquidGlass = isLiquidGlass,
					onItemSelected = onItemSelected,
					onItemReselected = onItemReselected,
				)
			}
		} else {
			Row(
				modifier = Modifier.wrapContentWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				FloatingNavItemsSurface(
					items = items,
					selectedId = selectedId,
					showSelectedLabel = showSelectedLabel,
					colors = colors,
					isLiquidGlass = isLiquidGlass,
					onItemSelected = onItemSelected,
					onItemReselected = onItemReselected,
				)
				FloatingContinueVisibility(
					visible = showContinue,
					colors = colors,
					isLiquidGlass = isLiquidGlass,
					onContinueClick = onContinueClick,
				)
			}
		}
	}
}

@Composable
private fun FloatingNavItemsSurface(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showSelectedLabel: Boolean,
	colors: FloatingNavBarColors,
	isLiquidGlass: Boolean,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
) {
	val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
	val glassBorder = if (isLiquidGlass) {
		BorderStroke(
			1.dp,
			Brush.verticalGradient(
				colors = if (isDark) {
					listOf(
						Color.White.copy(alpha = 0.75f),
						Color.White.copy(alpha = 0.22f),
					)
				} else {
					listOf(
						Color.White.copy(alpha = 0.95f),
						Color(0xFFE0D8F0).copy(alpha = 0.60f),
					)
				},
			),
		)
	} else {
		val borderColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f)
		BorderStroke(1.dp, borderColor)
	}

	val pillInteractionSource = remember { MutableInteractionSource() }
	val containerModifier = (if (isLiquidGlass) {
		Modifier
			.shadow(
				elevation = 18.dp,
				shape = CircleShape,
				spotColor = if (isDark) Color(0xFF5B2BD8).copy(alpha = 0.55f) else Color(0xFF7C4DFF).copy(alpha = 0.25f),
				ambientColor = if (isDark) Color.Black.copy(alpha = 0.45f) else Color(0xFF221A38).copy(alpha = 0.15f),
			)
			.border(glassBorder, CircleShape)
			.background(
				brush = Brush.verticalGradient(
					colors = if (isDark) {
						listOf(
							Color(0xFF221A38).copy(alpha = 0.82f),
							Color(0xFF100E1E).copy(alpha = 0.88f),
						)
					} else {
						listOf(
							Color(0xFFFFFFFF).copy(alpha = 0.90f),
							Color(0xFFF3F0FA).copy(alpha = 0.94f),
						)
					},
				),
				shape = CircleShape,
			)
			.wrapContentWidth()
	} else {
		Modifier
			.shadow(
				elevation = 12.dp,
				shape = CircleShape,
				spotColor = Color.Black.copy(alpha = 0.55f),
				ambientColor = Color.Black.copy(alpha = 0.25f),
			)
			.border(glassBorder, CircleShape)
			.wrapContentWidth()
	}).clickable(interactionSource = pillInteractionSource, indication = null) {}

	Surface(
		modifier = containerModifier,
		shape = CircleShape,
		color = if (isLiquidGlass) Color.Transparent else Color(colors.container).copy(alpha = if (isDark) 0.88f else 0.94f),
		contentColor = MaterialTheme.colorScheme.onSurface,
	) {
		FloatingNavDockLayout(
			items = items,
			selectedId = selectedId,
			showLabels = showSelectedLabel,
			colors = colors,
			isLiquidGlass = isLiquidGlass,
			onItemSelected = onItemSelected,
			onItemReselected = onItemReselected,
		)
	}
}

@Composable
private fun FloatingNavDockLayout(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	colors: FloatingNavBarColors,
	isLiquidGlass: Boolean,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
) {
	val density = LocalDensity.current
	val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
	val itemBoundsMap = remember { mutableStateMapOf<Int, Pair<Dp, Dp>>() }

	val activeBounds = itemBoundsMap[selectedId]
	val targetX = activeBounds?.first ?: 0.dp
	val targetW = activeBounds?.second ?: 58.dp

	val animatedX by animateDpAsState(
		targetValue = targetX,
		animationSpec = SpringLensDp,
		label = "lensX",
	)
	val animatedW by animateDpAsState(
		targetValue = targetW,
		animationSpec = SpringLensDp,
		label = "lensW",
	)

	val barHeight = if (showLabels) 58.dp else 48.dp

	Box(
		modifier = Modifier
			.height(barHeight)
			.padding(horizontal = 6.dp, vertical = 4.dp),
	) {
		// Active indicator pill (around the icon)
		if (activeBounds != null) {
			val indicatorWidth = (animatedW - 6.dp).coerceIn(46.dp, 56.dp)
			val indicatorHeight = if (showLabels) 30.dp else 38.dp
			val indicatorX = animatedX + (animatedW - indicatorWidth) / 2
			val indicatorY = if (showLabels) 2.dp else 1.dp

			val indicatorModifier = Modifier
				.offset(x = indicatorX, y = indicatorY)
				.width(indicatorWidth)
				.height(indicatorHeight)

			if (isLiquidGlass) {
				Box(
					modifier = indicatorModifier
						.shadow(
							elevation = 4.dp,
							shape = CircleShape,
							spotColor = if (isDark) Color(0xFF2B4470).copy(alpha = 0.50f) else Color(0xFF7C4DFF).copy(alpha = 0.25f),
							ambientColor = if (isDark) Color.Black.copy(alpha = 0.40f) else Color(0xFF221A38).copy(alpha = 0.12f),
						)
						.background(
							brush = Brush.verticalGradient(
								colors = if (isDark) {
									listOf(
										Color(0xFF2B4470).copy(alpha = 0.88f),
										Color(0xFF1B2E52).copy(alpha = 0.92f),
									)
								} else {
									listOf(
										Color(0xFFD6E4FF),
										Color(0xFFB8D3FF),
									)
								},
							),
							shape = CircleShape,
						)
						.border(
							BorderStroke(
								1.dp,
								Brush.verticalGradient(
									colors = if (isDark) {
										listOf(
											Color(0xFF6B92FF).copy(alpha = 0.55f),
											Color(0xFF3B62C2).copy(alpha = 0.30f),
										)
									} else {
										listOf(
											Color.White,
											Color(0xFF90B4F8).copy(alpha = 0.70f),
										)
									},
								),
							),
							CircleShape,
						),
				)
			} else {
				Box(
					modifier = indicatorModifier
						.background(
							color = Color(colors.selectedContainer),
							shape = CircleShape,
						),
				)
			}
		}

		Row(
			modifier = Modifier
				.fillMaxHeight()
				.wrapContentWidth(),
			horizontalArrangement = Arrangement.spacedBy(2.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			items.forEach { item ->
				val isSelected = item.id == selectedId
				Box(
					modifier = Modifier
						.fillMaxHeight()
						.onGloballyPositioned { coords ->
							val posInParent = coords.positionInParent()
							val xDp = with(density) { posInParent.x.toDp() }
							val wDp = with(density) { coords.size.width.toDp() }
							itemBoundsMap[item.id] = Pair(xDp, wDp)
						},
				) {
					CompactNavItem(
						item = item,
						selected = isSelected,
						showLabel = showLabels,
						colors = colors,
						isLiquidGlass = isLiquidGlass,
						onClick = {
							if (isSelected) {
								onItemReselected(item.id)
							} else {
								onItemSelected(item.id)
							}
						},
					)
				}
			}
		}
	}
}

@Composable
private fun FloatingContinueVisibility(
	visible: Boolean,
	colors: FloatingNavBarColors,
	isLiquidGlass: Boolean,
	onContinueClick: () -> Unit,
) {
	AnimatedVisibility(
		visible = visible,
		enter = fadeIn(animationSpec = SpringFloat) +
			expandHorizontally(animationSpec = SpringSize, expandFrom = Alignment.Start),
		exit = fadeOut(animationSpec = SpringFloat) +
			shrinkHorizontally(animationSpec = SpringSize, shrinkTowards = Alignment.Start),
	) {
		FloatingContinueButton(colors = colors, isLiquidGlass = isLiquidGlass, onClick = onContinueClick)
	}
}

@Composable
private fun FloatingContinueButton(
	colors: FloatingNavBarColors,
	isLiquidGlass: Boolean,
	onClick: () -> Unit,
) {
	val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
	val glassBorderColor = if (isLiquidGlass) {
		if (isDark) Color.White.copy(alpha = 0.28f) else Color(0xFF7C4DFF).copy(alpha = 0.35f)
	} else if (isDark) {
		Color.White.copy(alpha = 0.20f)
	} else {
		Color.Black.copy(alpha = 0.10f)
	}
	val container by animateColorAsState(
		targetValue = if (isLiquidGlass) {
			if (isDark) Color(0xFF6B42E8).copy(alpha = 0.88f) else Color(0xFF7C4DFF).copy(alpha = 0.92f)
		} else {
			Color(colors.selectedContainer).copy(alpha = if (isDark) 0.88f else 0.94f)
		},
		animationSpec = SpringColor,
		label = "continueContainer",
	)
	val content by animateColorAsState(
		targetValue = if (isLiquidGlass) Color.White else Color(colors.selectedContent),
		animationSpec = SpringColor,
		label = "continueContent",
	)
	val label = stringResource(R.string._continue)
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = container,
		contentColor = content,
		modifier = Modifier
			.size(48.dp)
			.shadow(
				elevation = 8.dp,
				shape = CircleShape,
				spotColor = Color.Black.copy(alpha = 0.35f),
				ambientColor = Color.Black.copy(alpha = 0.15f),
			)
			.border(BorderStroke(1.dp, glassBorderColor), CircleShape)
			.semantics { contentDescription = label },
	) {
		Box(contentAlignment = Alignment.Center) {
			Icon(
				painter = painterResource(R.drawable.ic_read),
				contentDescription = null,
				tint = content,
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

@Composable
private fun CompactNavItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	showLabel: Boolean,
	colors: FloatingNavBarColors,
	isLiquidGlass: Boolean,
	onClick: () -> Unit,
) {
	val context = LocalContext.current
	val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
	val activeTint = remember(context.theme, isLiquidGlass) {
		if (isLiquidGlass) {
			val typedValue = android.util.TypedValue()
			if (context.theme.resolveAttribute(R.attr.glassAccent, typedValue, true)) {
				Color(typedValue.data)
			} else {
				Color(0xFF7FA7F8)
			}
		} else {
			Color(colors.selectedContent)
		}
	}
	val unselectedColor = if (isLiquidGlass) {
		if (isDark) Color.White.copy(alpha = 0.70f) else Color(0xFF49454F).copy(alpha = 0.85f)
	} else {
		Color(colors.unselectedContent)
	}

	val contentColor by animateColorAsState(
		targetValue = if (selected) activeTint else unselectedColor,
		animationSpec = SpringColor,
		label = "compactContentColor",
	)
	val iconTint by animateColorAsState(
		targetValue = if (selected) {
			if (isDark && isLiquidGlass) Color.White else activeTint
		} else {
			unselectedColor
		},
		animationSpec = SpringColor,
		label = "compactIconTint",
	)
	val title = stringResource(item.titleRes)
	val interactionSource = remember { MutableInteractionSource() }

	val badgeDescription = if (item.badgeCount > 0) {
		context.resources.getQuantityString(R.plurals.nav_badge_new_items, item.badgeCount, item.badgeCount)
	} else {
		null
	}

	Box(
		modifier = Modifier
			.fillMaxHeight()
			.widthIn(min = 54.dp, max = 68.dp)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.semantics {
				this.selected = selected
				role = Role.Tab
				contentDescription = if (badgeDescription != null) "$title, $badgeDescription" else title
			},
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.fillMaxHeight()
				.padding(horizontal = 4.dp, vertical = 2.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center,
		) {
			Box(
				modifier = Modifier
					.height(if (showLabel) 30.dp else 38.dp)
					.fillMaxWidth(),
				contentAlignment = Alignment.Center,
			) {
				BadgedBox(
					badge = {
						if (item.badgeCount > 0) {
							Badge(
								containerColor = Color(0xFFE8382F),
								contentColor = Color.White,
								modifier = Modifier.offset(x = 4.dp, y = (-2).dp),
							) {
								Text(
									text = if (item.badgeCount > 99) "99+" else item.badgeCount.toString(),
									style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
								)
							}
						} else if (item.badgeCount < 0) {
							Badge(
								containerColor = Color(0xFFE8382F),
								modifier = Modifier.offset(x = 4.dp, y = (-2).dp),
							)
						}
					},
				) {
					NavIcon(
						resId = item.icon,
						selected = selected,
						tint = iconTint,
						modifier = Modifier.size(20.dp),
					)
				}
			}
			if (showLabel) {
				Text(
					text = title,
					color = contentColor,
					style = MaterialTheme.typography.labelSmall.copy(
						fontSize = 10.5.sp,
						fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
					),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 1.dp),
				)
			}
		}
	}
}

private val StateChecked = intArrayOf(android.R.attr.state_checked)
private val StateUnchecked = intArrayOf(-android.R.attr.state_checked)

/**
 * Hosts a real [ImageView] so the nav icons' `<animated-selector>` transitions actually run.
 * Painting an AnimatedVectorDrawable through a Compose Painter does not reliably drive the platform
 * animator; an ImageView does, because that is the path the native BottomNavigationView uses.
 * [selected] drives the drawable state, which is what triggers the morph between the two items.
 */
@Composable
private fun NavIcon(
	@DrawableRes resId: Int,
	selected: Boolean,
	tint: Color,
	modifier: Modifier = Modifier,
) {
	AndroidView(
		modifier = modifier,
		factory = { ctx ->
			ImageView(ctx).apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setImageResource(resId)
				// Prime the state without a transition, so the morph does not fire on first paint.
				setImageState(IntArray(0), false)
				jumpDrawablesToCurrentState()
			}
		},
		update = { imageView ->
			val targetResId = when {
				resId == R.drawable.ic_explore_selector && selected -> R.drawable.ic_explore_checked
				resId == R.drawable.ic_explore_selector -> R.drawable.ic_explore_normal
				else -> resId
			}
			val resourceChanged = imageView.tag != targetResId
			if (resourceChanged) {
				imageView.setImageResource(targetResId)
				imageView.tag = targetResId
			}
			if (resourceChanged || imageView.isSelected != selected) {
				imageView.isSelected = selected
				imageView.isActivated = selected
				imageView.setImageState(if (selected) StateChecked else StateUnchecked, false)
			}
			val tintColor = tint.toArgb()
			imageView.imageTintList = ColorStateList.valueOf(tintColor)
			imageView.drawable?.mutate()?.setTint(tintColor)
			imageView.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
		},
	)
}
