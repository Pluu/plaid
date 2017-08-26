/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.plaidapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v4.content.LocalBroadcastManager
import android.support.v4.view.GravityCompat
import android.support.v4.widget.DrawerLayout
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.transition.TransitionManager
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import butterknife.ButterKnife
import io.plaidapp.R
import io.plaidapp.data.DataManager
import io.plaidapp.data.PlaidItem
import io.plaidapp.data.Source
import io.plaidapp.data.api.designernews.PostStoryService
import io.plaidapp.data.api.designernews.model.Story
import io.plaidapp.data.pocket.isPocketInstalled
import io.plaidapp.data.prefs.DesignerNewsPrefs
import io.plaidapp.data.prefs.DribbblePrefs
import io.plaidapp.data.prefs.SourceManager
import io.plaidapp.ui.recyclerview.FilterTouchHelperCallback
import io.plaidapp.ui.recyclerview.GridItemDividerDecoration
import io.plaidapp.ui.recyclerview.InfiniteScrollListener
import io.plaidapp.ui.transitions.FabTransform
import io.plaidapp.ui.transitions.MorphTransform
import io.plaidapp.util.*
import kotlinx.android.synthetic.main.activity_home.*
import java.security.InvalidParameterException
import java.util.*

class HomeActivity : Activity() {

    private var noConnection: ImageView? = null
    var fabPosting: ImageButton? = null
    private val columns: Int by lazy {
        getInteger(R.integer.num_columns)
    }
    private val layoutManager: GridLayoutManager by lazy {
        GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return adapter.getItemColumnSpan(position)
                }
            }
        }
    }
    internal var connected = true
    private var noFiltersEmptyText: TextView? = null
    private var monitoringConnectivity = false

    // data
    private val dataManager: DataManager by lazy {
        object : DataManager(this, filtersAdapter) {
            override fun onDataLoaded(data: List<PlaidItem>) {
                adapter.addAndResort(data)
                checkEmptyState()
            }
        }
    }
    private val adapter: FeedAdapter by lazy {
        FeedAdapter(this, dataManager, columns, isPocketInstalled(this))
    }
    private val filtersAdapter: FilterAdapter by lazy {
        FilterAdapter(this, SourceManager.getSources(this),
                FilterAdapter.FilterAuthoriser { sharedElement, forSource ->
                    val login = Intent(this, DribbbleLogin::class.java)
                    MorphTransform.addExtras(login,
                            ContextCompat.getColor(this, R.color.background_dark),
                            sharedElement.height / 2)
                    val options = ActivityOptions.makeSceneTransitionAnimation(this,
                            sharedElement, getString(R.string.transition_dribbble_login))
                    startActivityForResult(login,
                            getAuthSourceRequestCode(forSource), options.toBundle())
                })
    }
    private val designerNewsPrefs: DesignerNewsPrefs by lazy {
        DesignerNewsPrefs.get(this)
    }
    private val dribbblePrefs: DribbblePrefs by lazy {
        DribbblePrefs.get(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        ButterKnife.bind(this)

        drawer.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        setActionBar(toolbar)
        if (savedInstanceState == null) {
            animateToolbar()
        }
        setExitSharedElementCallback(FeedAdapter.createSharedElementReenterCallback(this))

        grid.run {
            adapter = this@HomeActivity.adapter
            layoutManager = this@HomeActivity.layoutManager
            addOnScrollListener(toolbarElevation)
            addOnScrollListener(object : InfiniteScrollListener(this@HomeActivity.layoutManager, dataManager) {
                override fun onLoadMore() {
                    dataManager.loadAllDataSources()
                }
            })
            setHasFixedSize(true)
            addItemDecoration(GridItemDividerDecoration(this@HomeActivity, R.dimen.divider_height,
                    R.color.divider))
            itemAnimator = HomeGridItemAnimator()
        }

        // drawer layout treats fitsSystemWindows specially so we have to handle insets ourselves
        drawer.setOnApplyWindowInsetsListener { _, insets ->
            // inset the toolbar down by the status bar height
            val lpToolbar = toolbar.layoutParams as ViewGroup.MarginLayoutParams
            lpToolbar.topMargin += insets.systemWindowInsetTop
            lpToolbar.leftMargin += insets.systemWindowInsetLeft
            lpToolbar.rightMargin += insets.systemWindowInsetRight
            toolbar.layoutParams = lpToolbar

            // inset the grid top by statusbar+toolbar & the bottom by the navbar (don't clip)
            grid.setPadding(
                    grid.paddingLeft + insets.systemWindowInsetLeft, // landscape
                    insets.systemWindowInsetTop + ViewUtils.getActionBarSize(this@HomeActivity),
                    grid.paddingRight + insets.systemWindowInsetRight, // landscape
                    grid.paddingBottom + insets.systemWindowInsetBottom)

            // inset the fab for the navbar
            fab.run {
                val lpFab = layoutParams as ViewGroup.MarginLayoutParams
                lpFab.bottomMargin += insets.systemWindowInsetBottom // portrait
                lpFab.rightMargin += insets.systemWindowInsetRight // landscape
                layoutParams = lpFab
                setOnClickListener { fabClick() }
            }

            stub_posting_progress.run {
                val lpPosting = layoutParams as ViewGroup.MarginLayoutParams
                lpPosting.bottomMargin += insets.systemWindowInsetBottom // portrait
                lpPosting.rightMargin += insets.systemWindowInsetRight // landscape
                layoutParams = lpPosting
            }

            // we place a background behind the status bar to combine with it's semi-transparent
            // color to get the desired appearance.  Set it's height to the status bar height
            status_bar_background.run {
                layoutParams.height = insets.systemWindowInsetTop
            }

            // inset the filters list for the status bar / navbar
            // need to set the padding end for landscape case
            val ltr = if (filters.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                insets.systemWindowInsetRight
            } else {
                0
            }
            filters.setPaddingRelative(filters.paddingStart,
                    filters.paddingTop + insets.systemWindowInsetTop,
                    filters.paddingEnd + ltr,
                    filters.paddingBottom + insets.systemWindowInsetBottom)

            // clear this listener so insets aren't re-applied
            drawer.setOnApplyWindowInsetsListener(null)

            insets.consumeSystemWindowInsets()
        }
        setupTaskDescription()

        filters.run {
            adapter = filtersAdapter
            itemAnimator = FilterAdapter.FilterAnimator()
        }

        filtersAdapter.registerFilterChangedCallback(filtersChangedCallbacks)
        dataManager.loadAllDataSources()
        val callback = FilterTouchHelperCallback(filtersAdapter)
        val itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(filters)
        checkEmptyState()
    }

    override fun onResume() {
        super.onResume()
        dribbblePrefs.addLoginStatusListener(filtersAdapter)
        checkConnectivity()
    }

    override fun onPause() {
        dribbblePrefs.removeLoginStatusListener(filtersAdapter)
        if (monitoringConnectivity) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(connectivityCallback)
            monitoringConnectivity = false
        }
        super.onPause()
    }

    override fun onActivityReenter(resultCode: Int, data: Intent?) {
        if (data == null || resultCode != Activity.RESULT_OK
                || !data.hasExtra(DribbbleShot.RESULT_EXTRA_SHOT_ID))
            return

        // When reentering, if the shared element is no longer on screen (e.g. after an
        // orientation change) then scroll it into view.
        val sharedShotId = data.getLongExtra(DribbbleShot.RESULT_EXTRA_SHOT_ID, -1L)
        if (sharedShotId != -1L                                             // returning from a shot

                && adapter.dataItemCount > 0                           // grid populated

                && grid.findViewHolderForItemId(sharedShotId) == null) {    // view not attached
            val position = adapter.getItemPosition(sharedShotId)
            if (position == RecyclerView.NO_POSITION) return

            // delay the transition until our shared element is on-screen i.e. has been laid out
            postponeEnterTransition()
            grid.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                            oL: Int, oT: Int, oR: Int, oB: Int) {
                    grid.removeOnLayoutChangeListener(this)
                    startPostponedEnterTransition()
                }
            })
            grid.scrollToPosition(position)
            toolbar.translationZ = -1f
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val dribbbleLogin = menu.findItem(R.id.menu_dribbble_login)
        dribbbleLogin?.setTitle(if (dribbblePrefs.isLoggedIn)
            R.string.dribbble_log_out
        else
            R.string.dribbble_login)
        val designerNewsLogin = menu.findItem(R.id.menu_designer_news_login)
        designerNewsLogin?.setTitle(if (designerNewsPrefs.isLoggedIn)
            R.string.designer_news_log_out
        else
            R.string.designer_news_login)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_filter -> {
                drawer.openDrawer(GravityCompat.END)
                return true
            }
            R.id.menu_search -> {
                val searchMenuView = toolbar.findViewById<View>(R.id.menu_search)
                val options = ActivityOptions.makeSceneTransitionAnimation(this, searchMenuView,
                        getString(R.string.transition_search_back)).toBundle()
                startActivityForResult(Intent(this, SearchActivity::class.java), RC_SEARCH, options)
                return true
            }
            R.id.menu_dribbble_login -> {
                if (!dribbblePrefs.isLoggedIn) {
                    dribbblePrefs.login(this)
                } else {
                    dribbblePrefs.logout()
                    // TODO something better than a toast!!
                    showToast(R.string.dribbble_logged_out)
                }
                return true
            }
            R.id.menu_designer_news_login -> {
                if (!designerNewsPrefs.isLoggedIn) {
                    startActivity(Intent(this, DesignerNewsLogin::class.java))
                } else {
                    designerNewsPrefs.logout(this)
                    // TODO something better than a toast!!
                    showToast( R.string.designer_news_logged_out)
                }
                return true
            }
            R.id.menu_about -> {
                startActivity(Intent(this, AboutActivity::class.java),
                        ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.END)) {
            drawer.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RC_SEARCH -> {
                // reset the search icon which we hid
                val searchMenuView = toolbar.findViewById<View>(R.id.menu_search)
                if (searchMenuView != null) {
                    searchMenuView.alpha = 1f
                }
                if (resultCode == SearchActivity.RESULT_CODE_SAVE) {
                    val query = data?.getStringExtra(SearchActivity.EXTRA_QUERY)
                    if (TextUtils.isEmpty(query)) return
                    var dribbbleSearch: Source? = null
                    var designerNewsSearch: Source? = null
                    var newSource = false
                    if (data?.getBooleanExtra(SearchActivity.EXTRA_SAVE_DRIBBBLE, false) == true) {
                        dribbbleSearch = Source.DribbbleSearchSource(query, true)
                        newSource = filtersAdapter.addFilter(dribbbleSearch)
                    }
                    if (data?.getBooleanExtra(SearchActivity.EXTRA_SAVE_DESIGNER_NEWS, false) == true) {
                        designerNewsSearch = Source.DesignerNewsSearchSource(query, true)
                        newSource = newSource or filtersAdapter.addFilter(designerNewsSearch)
                    }
                    if (newSource) {
                        highlightNewSources(dribbbleSearch, designerNewsSearch)
                    }
                }
            }
            RC_NEW_DESIGNER_NEWS_STORY -> when (resultCode) {
                PostNewDesignerNewsStory.RESULT_DRAG_DISMISSED -> {
                    // need to reshow the FAB as there's no shared element transition
                    showFab()
                    unregisterPostStoryResultListener()
                }
                PostNewDesignerNewsStory.RESULT_POSTING -> showPostingProgress()
                else -> unregisterPostStoryResultListener()
            }
            RC_NEW_DESIGNER_NEWS_LOGIN -> if (resultCode == Activity.RESULT_OK) {
                showFab()
            }
            RC_AUTH_DRIBBBLE_FOLLOWING -> if (resultCode == Activity.RESULT_OK) {
                filtersAdapter.enableFilterByKey(SourceManager.SOURCE_DRIBBBLE_FOLLOWING, this)
            }
            RC_AUTH_DRIBBBLE_USER_LIKES -> if (resultCode == Activity.RESULT_OK) {
                filtersAdapter.enableFilterByKey(
                        SourceManager.SOURCE_DRIBBBLE_USER_LIKES, this)
            }
            RC_AUTH_DRIBBBLE_USER_SHOTS -> if (resultCode == Activity.RESULT_OK) {
                filtersAdapter.enableFilterByKey(
                        SourceManager.SOURCE_DRIBBBLE_USER_SHOTS, this)
            }
        }
    }

    override fun onDestroy() {
        dataManager.cancelLoading()
        super.onDestroy()
    }

    // listener for notifying adapter when data sources are deactivated
    private val filtersChangedCallbacks = object : FilterAdapter.FiltersChangedCallbacks() {
        override fun onFiltersChanged(changedFilter: Source) {
            if (!changedFilter.active) {
                adapter.removeDataSource(changedFilter.key)
            }
            checkEmptyState()
        }

        override fun onFilterRemoved(removed: Source) {
            adapter.removeDataSource(removed.key)
            checkEmptyState()
        }
    }

    private val toolbarElevation = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView?, newState: Int) {
            // we want the grid to scroll over the top of the toolbar but for the toolbar items
            // to be clickable when visible. To achieve this we play games with elevation. The
            // toolbar is laid out in front of the grid but when we scroll, we lower it's elevation
            // to allow the content to pass in front (and reset when scrolled to top of the grid)
            if (newState == RecyclerView.SCROLL_STATE_IDLE
                    && layoutManager.findFirstVisibleItemPosition() == 0
                    && layoutManager.findViewByPosition(0).top == grid.paddingTop
                    && toolbar.translationZ != 0f) {
                // at top, reset elevation
                toolbar.translationZ = 0f
            } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING && toolbar.translationZ != -1f) {
                // grid scrolled, lower toolbar to allow content to pass in front
                toolbar.translationZ = -1f
            }
        }
    }

    fun fabClick() {
        if (designerNewsPrefs.isLoggedIn) {
            val intent = Intent(this, PostNewDesignerNewsStory::class.java).apply {
                FabTransform.addExtras(this,
                        ContextCompat.getColor(this@HomeActivity, R.color.accent), R.drawable.ic_add_dark)
                intent.putExtra(PostStoryService.EXTRA_BROADCAST_RESULT, true)
            }
            registerPostStoryResultListener()
            val options = ActivityOptions.makeSceneTransitionAnimation(this, fab,
                    getString(R.string.transition_new_designer_news_post))
            startActivityForResult(intent, RC_NEW_DESIGNER_NEWS_STORY, options.toBundle())
        } else {
            val intent = Intent(this, DesignerNewsLogin::class.java).apply {
                FabTransform.addExtras(this,
                        ContextCompat.getColor(this@HomeActivity, R.color.accent), R.drawable.ic_add_dark)
            }
            val options = ActivityOptions.makeSceneTransitionAnimation(this, fab,
                    getString(R.string.transition_designer_news_login))
            startActivityForResult(intent, RC_NEW_DESIGNER_NEWS_LOGIN, options.toBundle())
        }
    }

    private var postStoryResultReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val fabPosting = this@HomeActivity.fabPosting as ImageButton
            ensurePostingProgressInflated()
            when (intent.action) {
                PostStoryService.BROADCAST_ACTION_SUCCESS -> {
                    // success animation
                    val complete = getDrawable(R.drawable.avd_upload_complete) as AnimatedVectorDrawable?
                    complete?.run {
                        fabPosting.setImageDrawable(complete)
                        complete.start()
                        fabPosting.postDelayed({ fabPosting.visibility = View.GONE }, 2100)
                    }
                    // actually add the story to the grid
                    val newStory = intent.getParcelableExtra<Story>(PostStoryService.EXTRA_NEW_STORY)
                    adapter.addAndResort(listOf(newStory))
                }
                PostStoryService.BROADCAST_ACTION_FAILURE -> {
                    // failure animation
                    val failed = getDrawable(R.drawable.avd_upload_error) as AnimatedVectorDrawable?
                    failed?.run {
                        fabPosting.setImageDrawable(failed)
                        failed.start()
                    }
                    // remove the upload progress 'fab' and reshow the regular one
                    fabPosting.animate()
                            .alpha(0f)
                            .rotation(90f)
                            .setStartDelay(2000L) // leave error on screen briefly
                            .setDuration(300L)
                            .setInterpolator(AnimUtils.getFastOutSlowInInterpolator(this@HomeActivity))
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    fabPosting.visibility = View.GONE
                                    fabPosting.alpha = 1f
                                    fabPosting.rotation = 0f
                                }
                            })
                }
            }
            unregisterPostStoryResultListener()
        }
    }

    private fun registerPostStoryResultListener() {
        val intentFilter = IntentFilter().apply {
            addAction(PostStoryService.BROADCAST_ACTION_SUCCESS)
            addAction(PostStoryService.BROADCAST_ACTION_FAILURE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(postStoryResultReceiver, intentFilter)
    }

    internal fun unregisterPostStoryResultListener() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(postStoryResultReceiver)
    }

    internal fun revealPostingProgress() {
        val fabPosting = fabPosting as ImageButton

        ViewAnimationUtils.createCircularReveal(fabPosting,
                fabPosting.pivotX.toInt(),
                fabPosting.pivotY.toInt(),
                0f,
                (fabPosting.width / 2).toFloat())
                .setDuration(600L).apply {
            interpolator = AnimUtils.getFastOutLinearInInterpolator(this@HomeActivity)
        }.start()
        val uploading = getDrawable(R.drawable.avd_uploading) as AnimatedVectorDrawable?
        if (uploading != null) {
            fabPosting.setImageDrawable(uploading)
            uploading.start()
        }
    }

    internal fun ensurePostingProgressInflated() {
        fabPosting ?: return
        fabPosting = (findViewById<View>(R.id.stub_posting_progress) as ViewStub).inflate() as ImageButton
    }

    internal fun checkEmptyState() {
        if (adapter.dataItemCount == 0) {
            // if grid is empty check whether we're loading or if no filters are selected
            if (filtersAdapter.enabledSourcesCount > 0) {
                if (connected) {
                    empty.visibility = View.VISIBLE
                    setNoFiltersEmptyTextVisibility(View.GONE)
                }
            } else {
                empty.visibility = View.GONE
                setNoFiltersEmptyTextVisibility(View.VISIBLE)
            }
            toolbar.translationZ = 0f
        } else {
            empty.visibility = View.GONE
            setNoFiltersEmptyTextVisibility(View.GONE)
        }
    }

    @Throws(InvalidParameterException::class)
    private fun getAuthSourceRequestCode(filter: Source): Int {
        when (filter.key) {
            SourceManager.SOURCE_DRIBBBLE_FOLLOWING -> return RC_AUTH_DRIBBBLE_FOLLOWING
            SourceManager.SOURCE_DRIBBBLE_USER_LIKES -> return RC_AUTH_DRIBBBLE_USER_LIKES
            SourceManager.SOURCE_DRIBBBLE_USER_SHOTS -> return RC_AUTH_DRIBBBLE_USER_SHOTS
        }
        throw InvalidParameterException()
    }

    private fun showPostingProgress() {
        ensurePostingProgressInflated()

        val fabPosting = fabPosting as ImageButton
        fabPosting.visibility = View.VISIBLE
        // if stub has just been inflated then it will not have been laid out yet
        if (fabPosting.isLaidOut) {
            revealPostingProgress()
        } else {
            fabPosting.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                            oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                    fabPosting.removeOnLayoutChangeListener(this)
                    revealPostingProgress()
                }
            })
        }
    }

    private fun setNoFiltersEmptyTextVisibility(visibility: Int) {
        if (visibility == View.VISIBLE) {
            if (noFiltersEmptyText == null) {
                // create the no filters empty text
                val stub = findViewById<View>(R.id.stub_no_filters) as ViewStub
                noFiltersEmptyText = stub.inflate() as TextView
                noFiltersEmptyText?.run {
                    val emptyText = getString(R.string.no_filters_selected)
                    val filterPlaceholderStart = emptyText.indexOf('\u08B4')
                    val altMethodStart = filterPlaceholderStart + 3
                    val ssb = SpannableStringBuilder(emptyText)
                    // show an image of the filter icon
                    ssb.setSpan(ImageSpan(this@HomeActivity, R.drawable.ic_filter_small,
                            ImageSpan.ALIGN_BASELINE),
                            filterPlaceholderStart,
                            filterPlaceholderStart + 1,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // make the alt method (swipe from right) less prominent and italic
                    ssb.setSpan(ForegroundColorSpan(
                            ContextCompat.getColor(this@HomeActivity, R.color.text_secondary_light)),
                            altMethodStart,
                            emptyText.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StyleSpan(Typeface.ITALIC),
                            altMethodStart,
                            emptyText.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = ssb
                    setOnClickListener { drawer.openDrawer(GravityCompat.END) }
                }
            }
            noFiltersEmptyText?.visibility = visibility
        } else if (noFiltersEmptyText != null) {
            noFiltersEmptyText?.visibility = visibility
        }
    }

    private fun setupTaskDescription() {
        val overviewIcon = DrawableUtils.drawableToBitmap(this, applicationInfo.icon)
        setTaskDescription(ActivityManager.TaskDescription(getString(R.string.app_name),
                overviewIcon,
                ContextCompat.getColor(this, R.color.primary)))
        overviewIcon.recycle()
    }

    private fun animateToolbar() {
        // this is gross but toolbar doesn't expose it's children to animate them :(
        val t = toolbar.getChildAt(0)
        if (t != null && t is TextView) {

            // fade in and space out the title.  Animating the letterSpacing performs horribly so
            // fake it by setting the desired letterSpacing then animating the scaleX ¯\_(ツ)_/¯
            t.alpha = 0f
            t.scaleX = 0.8f

            t.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .setStartDelay(300)
                    .setDuration(900).interpolator = AnimUtils.getFastOutSlowInInterpolator(this)
        }
    }

    private fun showFab() {
        fab.alpha = 0f
        fab.scaleX = 0f
        fab.scaleY = 0f
        fab.translationY = (fab.height / 2).toFloat()
        fab.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(AnimUtils.getLinearOutSlowInInterpolator(this))
                .start()
    }

    /**
     * Highlight the new source(s) by:
     * 1. opening the drawer
     * 2. scrolling new source(s) into view
     * 3. flashing new source(s) background
     * 4. closing the drawer (if user hasn't interacted with it)
     */
    private fun highlightNewSources(vararg sources: Source?) {
        val closeDrawerRunnable = Runnable { drawer.closeDrawer(GravityCompat.END) }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {

            // if the user interacts with the filters while it's open then don't auto-close
            private val filtersTouch = View.OnTouchListener { _, _ ->
                drawer.removeCallbacks(closeDrawerRunnable)
                false
            }

            override fun onDrawerOpened(drawerView: View?) {
                // scroll to the new item(s) and highlight them
                val filterPositions = ArrayList<Int>(sources.size)
                sources.mapTo(filterPositions) { filtersAdapter.getFilterPosition(it) }
                val scrollTo = Collections.max(filterPositions)
                filters.smoothScrollToPosition(scrollTo)
                for (position in filterPositions) {
                    filtersAdapter.highlightFilter(position)
                }
                filters.setOnTouchListener(filtersTouch)
            }

            override fun onDrawerClosed(drawerView: View?) {
                // reset
                filters.setOnTouchListener(null)
                drawer.removeDrawerListener(this)
            }

            override fun onDrawerStateChanged(newState: Int) {
                // if the user interacts with the drawer manually then don't auto-close
                if (newState == DrawerLayout.STATE_DRAGGING) {
                    drawer.removeCallbacks(closeDrawerRunnable)
                }
            }
        })
        drawer.openDrawer(GravityCompat.END)
        drawer.postDelayed(closeDrawerRunnable, 2000L)
    }

    private fun checkConnectivity() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        connected = activeNetworkInfo != null && activeNetworkInfo.isConnected
        if (!connected) {
            empty.visibility = View.GONE
            if (noConnection == null) {
                val stub = findViewById<View>(R.id.stub_no_connection) as ViewStub
                noConnection = stub.inflate() as ImageView
            }
            val avd = getDrawable(R.drawable.avd_no_connection) as AnimatedVectorDrawable?
            if (noConnection != null && avd != null) {
                noConnection?.setImageDrawable(avd)
                avd.start()
            }

            connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    connectivityCallback)
            monitoringConnectivity = true
        }
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connected = true
            if (adapter.dataItemCount != 0) return
            runOnUiThread {
                TransitionManager.beginDelayedTransition(drawer)
                noConnection?.visibility = View.GONE
                empty.visibility = View.VISIBLE
                dataManager.loadAllDataSources()
            }
        }

        override fun onLost(network: Network) {
            connected = false
        }
    }

    companion object {

        private val RC_SEARCH = 0
        private val RC_AUTH_DRIBBBLE_FOLLOWING = 1
        private val RC_AUTH_DRIBBBLE_USER_LIKES = 2
        private val RC_AUTH_DRIBBBLE_USER_SHOTS = 3
        private val RC_NEW_DESIGNER_NEWS_STORY = 4
        private val RC_NEW_DESIGNER_NEWS_LOGIN = 5
    }
}
