package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBar
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBarColors
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBarItem
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import com.google.android.material.R as materialR

/**
 * A SlidingBottomNavigationView whose visible face is rendered with Jetpack Compose as a
 * floating, pill-shaped toolbar (inspired by the Tomato app's HorizontalFloatingToolbar).
 *
 * It still owns the underlying NavigationBarView menu, so MainNavigationDelegate keeps
 * driving it through the standard menu / selectedItemId / listener APIs. The inherited
 * NavigationBarMenuView is hidden, and a ComposeView sibling renders the floating bar on top.
 */
class FloatingBottomNavigationView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : SlidingBottomNavigationView(context, attrs) {

	private val composeItemsState = MutableStateFlow<List<FloatingNavBarItem>>(emptyList())
	private val selectedIdState = MutableStateFlow(0)
	private val labeledState = MutableStateFlow(true)
	private val navColorsState = MutableStateFlow(readNavColors())
	private val continueVisibleState = MutableStateFlow(false)
	private var continueClickListener: (() -> Unit)? = null
	private var continueLongClickListener: (() -> Unit)? = null
	private val sourceItems = mutableListOf<NavItem>()
	private val hiddenIds = mutableSetOf<Int>()
	private val badgeCounts = mutableMapOf<Int, Int>()
	private val legacyBackground: Drawable = ColorDrawable(context.getThemeColor(materialR.attr.colorSurfaceContainer))
	private val legacyElevation = elevation
	// The legacy bar's items sit too close to its top edge. Nudging them down with translationY
	// (rather than padding) keeps the bar and the items themselves exactly the same size.
	private val legacyItemOffset = LEGACY_ITEM_OFFSET_DP * resources.displayMetrics.density
	private var useLegacyNavigation = false

	val isFloating: Boolean
		get() = !useLegacyNavigation

	private val composeView: ComposeView = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			// DropSauceTheme bakes in both the gflex variable font typography and the
			// activity's color scheme — gives the nav bar the same look as the rest of the app.
			org.koitharu.kotatsu.settings.compose.DropSauceTheme {
				val items by composeItemsState.collectAsState()
				val selectedId by selectedIdState.collectAsState()
				val labeled by labeledState.collectAsState()
				val navColors by navColorsState.collectAsState()
				val showContinue by continueVisibleState.collectAsState()
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 12.dp, vertical = 8.dp),
					contentAlignment = Alignment.Center,
				) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
						CouiNativeNavigationBar(
							items = items,
							selectedId = selectedId,
							showContinue = showContinue,
							showLabels = labeled,
							onItemSelected = { id -> this@FloatingBottomNavigationView.selectedItemId = id },
							onItemReselected = { id ->
								val menuItem = menu.findItem(id) ?: return@CouiNativeNavigationBar
								reselectedListener?.invoke(menuItem)
							},
							onContinueClick = { continueClickListener?.invoke() },
						)
					} else {
						FloatingNavBar(
							items = items,
							selectedId = selectedId,
							showLabels = labeled,
							colors = navColors,
							onItemSelected = { id -> this@FloatingBottomNavigationView.selectedItemId = id },
							onItemReselected = { id ->
								val menuItem = menu.findItem(id) ?: return@FloatingNavBar
								reselectedListener?.invoke(menuItem)
							},
							modifier = Modifier.wrapContentWidth(),
							showContinue = showContinue,
							onContinueClick = { continueClickListener?.invoke() },
							onContinueLongClick = { continueLongClickListener?.invoke() },
						)
					}
				}
			}
		}
	}

	private var reselectedListener: ((android.view.MenuItem) -> Unit)? = null

	init {
		// The parent NavigationBarView paints a solid surface — clear it so the Compose pill floats.
		background = null
		elevation = 0f
		clipChildren = false
		clipToPadding = false
		composeView.clipChildren = false
		composeView.clipToPadding = false
		// Hide everything the parent NavigationBarView added (the native menu view) BEFORE
		// our composeView gets added — we can't reference BottomNavigationMenuView directly
		// because it's @RestrictTo(LIBRARY_GROUP), so we identify it by exclusion instead.
		hideNativeChildren()
		addView(
			composeView,
			ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			),
		)
	}

	override val maxItemCountOverride: Int
		get() = MAX_RENDERED_ITEMS

	override fun setSelectedItemId(@IdRes itemId: Int) {
		super.setSelectedItemId(itemId)
		selectedIdState.value = selectedItemId
	}

	override fun setOnItemReselectedListener(listener: OnItemReselectedListener?) {
		super.setOnItemReselectedListener(listener)
		reselectedListener = listener?.let { l -> { item -> l.onNavigationItemReselected(item) } }
	}

	/**
	 * Maximum number of items the floating bar will render. Extra items are dropped so the
	 * pill bar stays comfortable on phones — the rest remain accessible via overflow / settings.
	 */
	val maxRenderedItems: Int = MAX_RENDERED_ITEMS

	/**
	 * Set the list of nav items currently configured in settings. The bar renders up to
	 * [maxRenderedItems] of them, skipping any temporarily hidden via [setComposeItemVisibility].
	 */
	fun setComposeItems(items: List<NavItem>) {
		sourceItems.clear()
		sourceItems.addAll(items)
		rebuildComposeItems()
	}

	fun setComposeLabeled(value: Boolean) {
		labeledState.value = value
	}

	/** Toggle the standalone circular "continue reading" button rendered next to the floating bar. */
	fun setContinueVisible(value: Boolean) {
		continueVisibleState.value = value
	}

	fun setOnContinueClickListener(listener: (() -> Unit)?) {
		continueClickListener = listener
	}

	fun setOnContinueLongClickListener(listener: (() -> Unit)?) {
		continueLongClickListener = listener
	}

	fun setUseLegacyNavigation(value: Boolean) {
		if (useLegacyNavigation == value) return
		useLegacyNavigation = value
		updateNavigationMode()
	}

	fun setComposeBadge(@IdRes itemId: Int, count: Int) {
		if (count == 0) badgeCounts.remove(itemId) else badgeCounts[itemId] = count
		rebuildComposeItems()
	}

	fun setComposeItemVisibility(@IdRes itemId: Int, isVisible: Boolean) {
		if (isVisible) hiddenIds.remove(itemId) else hiddenIds.add(itemId)
		rebuildComposeItems()
	}

	private fun rebuildComposeItems() {
		val out = ArrayList<FloatingNavBarItem>(sourceItems.size.coerceAtMost(maxRenderedItems))
		for (item in sourceItems) {
			if (item.id in hiddenIds) continue
			out += FloatingNavBarItem(
				id = item.id,
				titleRes = item.title,
				icon = item.icon,
				badgeCount = badgeCounts[item.id] ?: 0,
			)
			if (out.size >= maxRenderedItems) break
		}
		composeItemsState.value = out
		selectedIdState.value = selectedItemId
	}

	private fun hideNativeChildren() {
		// composeView isn't in the tree yet when this is called from init, so every
		// existing child is from the NavigationBarView superclass and should be hidden.
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (child !== composeView) {
				child.visibility = if (useLegacyNavigation) View.VISIBLE else View.GONE
				child.translationY = if (useLegacyNavigation) legacyItemOffset else 0f
			}
		}
	}

	private fun updateNavigationMode() {
		navColorsState.value = readNavColors()
		composeView.visibility = if (useLegacyNavigation) View.GONE else View.VISIBLE
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (child !== composeView) {
				child.visibility = if (useLegacyNavigation) View.VISIBLE else View.GONE
				child.translationY = if (useLegacyNavigation) legacyItemOffset else 0f
			}
		}
		background = if (useLegacyNavigation) legacyBackground else null
		elevation = if (useLegacyNavigation) legacyElevation else 0f
	}

	private fun readNavColors(): FloatingNavBarColors {
		val unselectedState = intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked)
		val fallbackContainer = context.getThemeColor(materialR.attr.colorSurfaceContainer)
		val fallbackUnselectedContent = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)
		// The theme's own active indicator (colorSecondaryContainer) is nearly the same tone as the
		// bar itself, so the selected item barely reads as selected. The primary container pair is
		// the strongest on-theme option and carries its own guaranteed-contrast content colour.
		return FloatingNavBarColors(
			container = backgroundTintList?.defaultColor ?: fallbackContainer,
			selectedContainer = context.getThemeColor(materialR.attr.colorPrimaryContainer),
			selectedContent = context.getThemeColor(materialR.attr.colorOnPrimaryContainer),
			unselectedContent = itemIconTintList?.getColorForState(unselectedState, fallbackUnselectedContent)
				?: itemTextColor?.getColorForState(unselectedState, fallbackUnselectedContent)
				?: fallbackUnselectedContent,
		)
	}

	companion object {
		const val MAX_RENDERED_ITEMS = 6
		private const val LEGACY_ITEM_OFFSET_DP = 8f
	}
}


@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
private fun CouiNativeNavigationBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showContinue: Boolean,
	showLabels: Boolean,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	onContinueClick: () -> Unit,
) {
	if (items.isEmpty()) return
	val backdrop = rememberCouiBackdrop()
	val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
	val couiItems = items.map { item ->
		val title = stringResource(item.titleRes)
		val accessibleTitle = when {
			item.badgeCount < 0 -> "$title •"
			item.badgeCount > 0 -> "$title ${item.badgeCount}"
			else -> title
		}
		val (normalIcon, selectedIcon) = item.iconResources()
		CouiNavigationItem(
			title = if (showLabels) accessibleTitle else "",
			icon = ImageVector.vectorResource(normalIcon),
			selectedIcon = ImageVector.vectorResource(selectedIcon),
		)
	}
	Box(
		modifier = Modifier.fillMaxWidth(),
		contentAlignment = Alignment.Center,
	) {
		Box(
			modifier = Modifier
				.matchParentSize()
				.couiBackdrop(backdrop)
				.background(MaterialTheme.colorScheme.surface),
		)
		NavigationBar(
			modifier = Modifier.wrapContentWidth(),
			backdrop = backdrop,
			items = couiItems,
			selectedIndex = selectedIndex,
			rightIcon = ImageVector.vectorResource(org.koitharu.kotatsu.R.drawable.ic_read),
			rightDescription = stringResource(org.koitharu.kotatsu.R.string._continue),
			rightVisible = showContinue,
			onRightClick = onContinueClick,
			onSelection = { index ->
				val itemId = items[index].id
				if (itemId == selectedId) onItemReselected(itemId) else onItemSelected(itemId)
			},
		)
	}
}

private fun FloatingNavBarItem.iconResources(): Pair<Int, Int> = when (id) {
	org.koitharu.kotatsu.R.id.nav_history ->
		org.koitharu.kotatsu.R.drawable.ic_history to org.koitharu.kotatsu.R.drawable.ic_history_checked
	org.koitharu.kotatsu.R.id.nav_favorites ->
		org.koitharu.kotatsu.R.drawable.ic_heart_outline to org.koitharu.kotatsu.R.drawable.ic_heart
	org.koitharu.kotatsu.R.id.nav_local ->
		org.koitharu.kotatsu.R.drawable.ic_storage to org.koitharu.kotatsu.R.drawable.ic_storage_checked
	org.koitharu.kotatsu.R.id.nav_explore ->
		org.koitharu.kotatsu.R.drawable.ic_explore_normal to org.koitharu.kotatsu.R.drawable.ic_explore_checked
	org.koitharu.kotatsu.R.id.nav_suggestions ->
		org.koitharu.kotatsu.R.drawable.ic_suggestion to org.koitharu.kotatsu.R.drawable.ic_suggestion_checked
	org.koitharu.kotatsu.R.id.nav_feed ->
		org.koitharu.kotatsu.R.drawable.ic_feed to org.koitharu.kotatsu.R.drawable.ic_feed_checked
	org.koitharu.kotatsu.R.id.nav_updated ->
		org.koitharu.kotatsu.R.drawable.ic_updated to org.koitharu.kotatsu.R.drawable.ic_updated_checked
	org.koitharu.kotatsu.R.id.nav_bookmarks ->
		org.koitharu.kotatsu.R.drawable.ic_bookmark to org.koitharu.kotatsu.R.drawable.ic_bookmark_checked
	else -> org.koitharu.kotatsu.R.drawable.ic_history to org.koitharu.kotatsu.R.drawable.ic_history_checked
}
