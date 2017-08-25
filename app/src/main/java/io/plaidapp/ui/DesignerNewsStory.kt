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

import `in`.uncod.android.bypass.Bypass
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.SharedElementCallback
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.customtabs.CustomTabsIntent
import android.support.customtabs.CustomTabsSession
import android.support.design.widget.TextInputLayout
import android.support.v4.app.ShareCompat
import android.support.v4.content.ContextCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.TextAppearanceSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.widget.*
import butterknife.BindDimen
import butterknife.BindInt
import butterknife.BindView
import butterknife.ButterKnife
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import io.plaidapp.R
import io.plaidapp.data.api.designernews.UpvoteStoryService
import io.plaidapp.data.api.designernews.model.Comment
import io.plaidapp.data.api.designernews.model.Story
import io.plaidapp.data.prefs.DesignerNewsPrefs
import io.plaidapp.ui.drawable.ThreadedCommentDrawable
import io.plaidapp.ui.recyclerview.SlideInItemAnimator
import io.plaidapp.ui.transitions.GravityArcMotion
import io.plaidapp.ui.transitions.MorphTransform
import io.plaidapp.ui.transitions.ReflowText
import io.plaidapp.ui.widget.AuthorTextView
import io.plaidapp.ui.widget.ElasticDragDismissFrameLayout
import io.plaidapp.util.*
import io.plaidapp.util.AnimUtils.*
import io.plaidapp.util.customtabs.CustomTabActivityHelper
import io.plaidapp.util.glide.CircleTransform
import io.plaidapp.util.glide.ImageSpanTarget
import kotlinx.android.synthetic.main.activity_designer_news_story.*
import kotlinx.android.synthetic.main.designer_news_story_description.*
import kotlinx.android.synthetic.main.designer_news_story_fab.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.NumberFormat
import java.util.*

class DesignerNewsStory : Activity() {

    private val header: View by lazy {
        layoutInflater.inflate(R.layout.designer_news_story_description, comments_list, false)
    }
    private val layoutManager: LinearLayoutManager by lazy {
        LinearLayoutManager(this)
    }
    private lateinit var commentsAdapter: DesignerNewsCommentsAdapter
    private val chromeFader: ElasticDragDismissFrameLayout.SystemChromeFader by lazy {
        ElasticDragDismissFrameLayout.SystemChromeFader(this)
    }

    private lateinit var enterComment: EditText
    private lateinit var postComment: ImageButton

    @BindInt(R.integer.fab_expand_duration)
    var fabExpandDuration: Int = 0
    @BindDimen(R.dimen.comment_thread_width)
    var threadWidth: Int = 0
    @BindDimen(R.dimen.comment_thread_gap)
    var threadGap: Int = 0

    private val story: Story by lazy {
        intent.getParcelableExtra<Story>(EXTRA_STORY)
    }
    private val designerNewsPrefs: DesignerNewsPrefs by lazy {
        DesignerNewsPrefs.get(this)
    }
    private val markdown: Bypass by lazy {
        Bypass(this, Bypass.Options()
                .setBlockQuoteLineColor(
                        ContextCompat.getColor(this, R.color.designer_news_quote_line))
                .setBlockQuoteLineWidth(2) // dps
                .setBlockQuoteLineIndent(8) // dps
                .setPreImageLinebreakHeight(4) //dps
                .setBlockQuoteIndentSize(TypedValue.COMPLEX_UNIT_DIP, 2f)
                .setBlockQuoteTextColor(ContextCompat.getColor(this, R.color.designer_news_quote)))
    }
    private val customTab: CustomTabActivityHelper by lazy {
        CustomTabActivityHelper()
    }
    private val circleTransform: CircleTransform by lazy {
        CircleTransform(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_designer_news_story)
        ButterKnife.bind(this)

        fab.setOnClickListener(fabClick)

        comments_list.run {
            layoutManager = layoutManager
            itemAnimator = CommentAnimator(
                    resources.getInteger(R.integer.comment_expand_collapse_duration).toLong())
        }
        bindDescription()

        // setup title/toolbar
        if (backdrop_toolbar != null) {
            backdrop_toolbar.addOnLayoutChangeListener(titlebarLayout)
            backdrop_toolbar.setTitle(story.title)
            val toolbar = findViewById<View>(R.id.story_toolbar) as Toolbar
            toolbar.setNavigationOnClickListener(backClick)
            comments_list.addOnScrollListener(headerScrollListener)

            setEnterSharedElementCallback(object : SharedElementCallback() {
                override fun onSharedElementStart(sharedElementNames: List<String>, sharedElements: List<View>, sharedElementSnapshots: List<View>) {
                    ReflowText.setupReflow(intent, backdrop_toolbar)
                }

                override fun onSharedElementEnd(sharedElementNames: List<String>, sharedElements: List<View>, sharedElementSnapshots: List<View>) {
                    ReflowText.setupReflow(backdrop_toolbar)
                }
            })
        } else {
            story_title.text = story.title
            back.setOnClickListener(backClick)
        }

        val enterCommentView = setupCommentField()
        commentsAdapter = if (story.comment_count > 0) {
            // flatten the comments from a nested structure {@see Comment#comments} to a
            // list appropriate for our adapter (using the depth attribute).
            val flattened = ArrayList<Comment>(story.comment_count)
            unnestComments(story.comments, flattened)
            DesignerNewsCommentsAdapter(header, flattened, enterCommentView)
        } else {
            DesignerNewsCommentsAdapter(header, ArrayList(0), enterCommentView)
        }
        comments_list.adapter = commentsAdapter
        customTab.setConnectionCallback(customTabConnect)
    }

    override fun onStart() {
        super.onStart()
        customTab.bindCustomTabsService(this)
    }

    override fun onResume() {
        super.onResume()
        // clean up after any fab expansion
        fab.alpha = 1f
        fab_expand.visibility = View.INVISIBLE
        comments_container.addListener(chromeFader)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        when (requestCode) {
            RC_LOGIN_UPVOTE -> if (resultCode == Activity.RESULT_OK) {
                upvoteStory()
            }
        }
    }

    override fun onPause() {
        comments_container.removeListener(chromeFader)
        super.onPause()
    }

    override fun onStop() {
        customTab.unbindCustomTabsService(this)
        super.onStop()
    }

    override fun onDestroy() {
        customTab.setConnectionCallback(null)
        super.onDestroy()
    }

    @TargetApi(Build.VERSION_CODES.M)
    override fun onProvideAssistContent(outContent: AssistContent) {
        outContent.webUri = Uri.parse(story.url)
    }

    private val customTabConnect = object : CustomTabActivityHelper.ConnectionCallback {

        override fun onCustomTabsConnected() {
            customTab.mayLaunchUrl(Uri.parse(story.url), null, null)
        }

        override fun onCustomTabsDisconnected() {}
    }

    private val headerScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
            updateScrollDependentUi()
        }
    }

    private val backClick = View.OnClickListener { finishAfterTransition() }

    private fun updateScrollDependentUi() {
        // feed scroll events to the header
        backdrop_toolbar?.run {
            val headerScroll = header.top - comments_list.paddingTop
            backdrop_toolbar.setScrollPixelOffset(-headerScroll)
            story_title_background.setOffset(headerScroll)
        }
        updateFabVisibility()
    }

    private var fabIsVisible = true
    private fun updateFabVisibility() {
        // the FAB position can interfere with the enter comment field. Hide the FAB if:
        // - The comment field is scrolled onto screen
        // - The comment field is focused (i.e. stories with no/few comments might not push the
        //   enter comment field off-screen so need to make sure the button is accessible
        // - A comment reply field is focused
        val enterCommentFocused = enterComment.isFocused
        val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
        val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()
        val footerPosition = commentsAdapter.itemCount - 1
        val footerVisible = lastVisibleItemPosition == footerPosition
        val replyCommentFocused = commentsAdapter.isReplyToCommentFocused

        val fabShouldBeVisible = (firstVisibleItemPosition == 0 && !enterCommentFocused || !footerVisible) && !replyCommentFocused

        if (!fabShouldBeVisible && fabIsVisible) {
            fabIsVisible = false
            fab.animate()
                    .scaleX(0f)
                    .scaleY(0f)
                    .alpha(0.6f)
                    .setDuration(200L)
                    .setInterpolator(getFastOutLinearInInterpolator(this))
                    .withLayer()
                    .setListener(postHideFab)
                    .start()
        } else if (fabShouldBeVisible && !fabIsVisible) {
            fabIsVisible = true
            fab.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(200L)
                    .setInterpolator(getLinearOutSlowInInterpolator(this))
                    .withLayer()
                    .setListener(preShowFab)
                    .start()
            ImeUtils.hideIme(enterComment)
        }
    }

    private val preShowFab = object : AnimatorListenerAdapter() {
        override fun onAnimationStart(animation: Animator) {
            fab.visibility = View.VISIBLE
        }
    }

    private val postHideFab = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            fab.visibility = View.GONE
        }
    }

    // title can expand up to a max number of lines. If it does then adjust UI to reflect
    private val titlebarLayout = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
            if (bottom - top != oldBottom - oldTop) {
                comments_list.setPaddingRelative(comments_list.paddingStart,
                        backdrop_toolbar!!.height,
                        comments_list.paddingEnd,
                        comments_list.paddingBottom)
                comments_list.scrollToPosition(0)
            }
            backdrop_toolbar!!.removeOnLayoutChangeListener(this)
        }
    }

    private val fabClick = View.OnClickListener {
        doFabExpand()
        CustomTabActivityHelper.openCustomTab(
                this@DesignerNewsStory,
                getCustomTabIntent(this@DesignerNewsStory, story,
                        customTab.session)
                        .setStartAnimations(applicationContext,
                                R.anim.chrome_custom_tab_enter,
                                R.anim.fade_out_rapidly)
                        .build(),
                Uri.parse(story.url))
    }

    private fun doFabExpand() {
        // translate the chrome placeholder ui so that it is centered on the FAB
        val fabCenterX = (fab.left + fab.right) / 2
        val fabCenterY = (fab.top + fab.bottom) / 2 - fab_expand.top
        val translateX = fabCenterX - fab_expand.width / 2
        val translateY = fabCenterY - fab_expand.height / 2

        fab_expand.run {
            translationX = translateX.toFloat()
            translationY = translateY.toFloat()
            visibility = View.VISIBLE
        }

        val reveal = ViewAnimationUtils.createCircularReveal(
                fab_expand,
                fab_expand.width / 2,
                fab_expand.height / 2,
                (fab.width / 2).toFloat(),
                Math.hypot((fab_expand.width / 2).toDouble(), (fab_expand.height / 2).toDouble()).toInt().toFloat())
                .setDuration(fabExpandDuration.toLong())

        // translate the placeholder ui back into position along an arc
        val arcMotion = GravityArcMotion()
        arcMotion.minimumVerticalAngle = 70f
        val motionPath = arcMotion.getPath(translateX.toFloat(), translateY.toFloat(), 0f, 0f)
        val position = ObjectAnimator.ofFloat(fab_expand, View.TRANSLATION_X, View
                .TRANSLATION_Y, motionPath)
                .setDuration(fabExpandDuration.toLong())

        // animate from the FAB colour to the placeholder background color
        val background = ObjectAnimator.ofArgb(fab_expand,
                ViewUtils.BACKGROUND_COLOR,
                ContextCompat.getColor(this, R.color.designer_news),
                ContextCompat.getColor(this, R.color.background_light))
                .setDuration(fabExpandDuration.toLong())

        // fade out the fab (rapidly)
        val fadeOutFab = ObjectAnimator.ofFloat(fab, View.ALPHA, 0f)
                .setDuration(60)

        // play 'em all together with the material interpolator
        AnimatorSet().apply {
            interpolator = getFastOutSlowInInterpolator(this@DesignerNewsStory)
            playTogether(reveal, background, position, fadeOutFab)
        }.start()
    }

    private fun bindDescription() {
        val storyComment = header.findViewById<View>(R.id.story_comment) as TextView
        if (!story.comment.isNullOrEmpty()) {
            HtmlUtils.parseMarkdownAndSetText(storyComment, story.comment, markdown) { src, loadingSpan ->
                Glide.with(this@DesignerNewsStory)
                        .load(src)
                        .asBitmap()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(ImageSpanTarget(storyComment, loadingSpan))
            }
        } else {
            storyComment.visibility = View.GONE
        }

        story_vote_action.text = resources.getQuantityString(R.plurals.upvotes, story.vote_count,
                NumberFormat.getInstance().format(story.vote_count.toLong()))
        story_vote_action.setOnClickListener { upvoteStory() }

        val share = header.findViewById<View>(R.id.story_share_action) as TextView
        share.setOnClickListener {
            (share.compoundDrawables[1] as AnimatedVectorDrawable).start()
            startActivity(ShareCompat.IntentBuilder.from(this@DesignerNewsStory)
                    .setText(story.url)
                    .setType("text/plain")
                    .setSubject(story.title)
                    .intent)
        }

        val storyPosterTime = header.findViewById<View>(R.id.story_poster_time) as TextView
        val poster = SpannableString(story.user_display_name.toLowerCase()).apply {
            setSpan(TextAppearanceSpan(this@DesignerNewsStory, R.style.TextAppearance_CommentAuthor),
                    0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val job = if (!story.user_job.isNullOrEmpty()) "\n" + story.user_job.toLowerCase() else ""
        val timeAgo = DateUtils.getRelativeTimeSpanString(story.created_at.time,
                System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS)
                .toString().toLowerCase()
        storyPosterTime.text = TextUtils.concat(poster, job, "\n", timeAgo)
        val avatar = header.findViewById<View>(R.id.story_poster_avatar) as ImageView
        if (!story.user_portrait_url.isNullOrEmpty()) {
            Glide.with(this)
                    .load(story.user_portrait_url)
                    .placeholder(R.drawable.avatar_placeholder)
                    .transform(circleTransform)
                    .into(avatar)
        } else {
            avatar.visibility = View.GONE
        }
    }

    private fun setupCommentField(): View {
        val enterCommentView = layoutInflater
                .inflate(R.layout.designer_news_enter_comment, comments_list, false)
        enterComment = enterCommentView.findViewById<View>(R.id.comment) as EditText
        postComment = enterCommentView.findViewById<View>(R.id.post_comment) as ImageButton
        postComment.setOnClickListener(View.OnClickListener {
            if (designerNewsPrefs.isLoggedIn) {
                if (TextUtils.isEmpty(enterComment.text)) return@OnClickListener
                enterComment.isEnabled = false
                postComment.isEnabled = false
                val comment = designerNewsPrefs.api
                        .comment(story.id, enterComment.text.toString())
                comment.enqueue(object : Callback<Comment> {
                    override fun onResponse(call: Call<Comment>, response: Response<Comment>) {
                        enterComment.text.clear()
                        enterComment.isEnabled = true
                        postComment.isEnabled = true
                        commentsAdapter.addComment(response.body()!!)
                    }

                    override fun onFailure(call: Call<Comment>, t: Throwable) {
                        showToast("Failed to post comment :(")
                        enterComment.isEnabled = true
                        postComment.isEnabled = true
                    }
                })
            } else {
                needsLogin(postComment, 0)
            }
            enterComment.clearFocus()
        })
        enterComment.onFocusChangeListener = enterCommentFocus
        return enterCommentView
    }

    private fun upvoteStory() {
        if (designerNewsPrefs.isLoggedIn) {
            if (!story_vote_action.isActivated) {
                story_vote_action.isActivated = true
                val upvoteStory = designerNewsPrefs.api.upvoteStory(story.id)
                upvoteStory.enqueue(object : Callback<Story> {
                    override fun onResponse(call: Call<Story>, response: Response<Story>) {
                        val newUpvoteCount = response.body()!!.vote_count
                        this@DesignerNewsStory.story_vote_action.text = resources.getQuantityString(
                                R.plurals.upvotes, newUpvoteCount,
                                NumberFormat.getInstance().format(newUpvoteCount.toLong()))
                    }

                    override fun onFailure(call: Call<Story>, t: Throwable) {}
                })
            } else {
                story_vote_action.isActivated = false
                // TODO delete upvote. Not available in v1 API.
            }
        } else {
            needsLogin(story_vote_action, RC_LOGIN_UPVOTE)
        }
    }

    private fun needsLogin(triggeringView: View?, requestCode: Int) {
        val login = Intent(this@DesignerNewsStory, DesignerNewsLogin::class.java)
        MorphTransform.addExtras(login, ContextCompat.getColor(this, R.color.background_light),
                triggeringView!!.height / 2)
        val options = ActivityOptions.makeSceneTransitionAnimation(
                this@DesignerNewsStory,
                triggeringView, getString(R.string.transition_designer_news_login))
        startActivityForResult(login, requestCode, options.toBundle())
    }

    private fun unnestComments(nested: List<Comment>, flat: MutableList<Comment>) {
        for (comment in nested) {
            flat.add(comment)
            if (comment.comments?.isNotEmpty() == true) {
                unnestComments(comment.comments, flat)
            }
        }
    }

    private val enterCommentFocus = View.OnFocusChangeListener { _, hasFocus ->
        // kick off an anim (via animated state list) on the post button. see
        // @drawable/ic_add_comment_state
        postComment.isActivated = hasFocus
        updateFabVisibility()
    }

    private fun isOP(userId: Long?): Boolean =
            userId == story.user_id

    /* package */
    internal inner class DesignerNewsCommentsAdapter(
            private val header: View,
            private val comments: MutableList<Comment>,
            private val footer: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_NO_COMMENTS = 1
        private val TYPE_COMMENT = 2
        private val TYPE_COMMENT_REPLY = 3
        private val TYPE_FOOTER = 4

        private var expandedCommentPosition = RecyclerView.NO_POSITION
        var isReplyToCommentFocused = false
            private set

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return TYPE_HEADER
            if (isCommentReplyExpanded && position == expandedCommentPosition + 1)
                return TYPE_COMMENT_REPLY
            var footerPosition = if (hasComments())
                1 + comments.size // header + comments
            else
                2 // header + no comments view
            if (isCommentReplyExpanded) footerPosition++
            if (position == footerPosition) return TYPE_FOOTER
            return if (hasComments()) TYPE_COMMENT else TYPE_NO_COMMENTS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder? {
            when (viewType) {
                TYPE_HEADER -> return HeaderHolder(header)
                TYPE_COMMENT -> return createCommentHolder(parent)
                TYPE_COMMENT_REPLY -> return createCommentReplyHolder(parent)
                TYPE_NO_COMMENTS -> return NoCommentsHolder(
                        layoutInflater.inflate(
                                R.layout.designer_news_no_comments, parent, false))
                TYPE_FOOTER -> return FooterHolder(footer)
            }
            return null
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (getItemViewType(position)) {
                TYPE_COMMENT -> bindComment(holder as CommentHolder, null)
                TYPE_COMMENT_REPLY -> bindCommentReply(holder as CommentReplyHolder)
            } // nothing to bind for header / no comment / footer views
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder,
                                      position: Int,
                                      partialChangePayloads: List<Any>?) {
            when (getItemViewType(position)) {
                TYPE_COMMENT -> bindComment(holder as CommentHolder, partialChangePayloads)
                else -> onBindViewHolder(holder, position)
            }
        }

        override fun getItemCount(): Int {
            var itemCount = 2 // header + footer
            if (hasComments()) {
                itemCount += comments.size
            } else {
                itemCount++ // no comments view
            }
            if (isCommentReplyExpanded) itemCount++
            return itemCount
        }

        fun addComment(newComment: Comment) {
            if (!hasComments()) {
                notifyItemRemoved(1) // remove the no comments view
            }
            comments.add(newComment)
            notifyItemInserted(commentIndexToAdapterPosition(comments.size - 1))
        }

        /**
         * Add a new comment and return the adapter position that it was inserted at.
         */
        private fun addCommentReply(newComment: Comment, inReplyToAdapterPosition: Int): Int {
            // when replying to a comment, we want to insert it after any existing replies
            // i.e. after any following comments with the same or greater depth
            var commentIndex = adapterPositionToCommentIndex(inReplyToAdapterPosition)
            do {
                commentIndex++
            } while (commentIndex < comments.size && comments[commentIndex].depth >= newComment.depth)
            comments.add(commentIndex, newComment)
            val adapterPosition = commentIndexToAdapterPosition(commentIndex)
            notifyItemInserted(adapterPosition)
            return adapterPosition
        }

        private fun hasComments(): Boolean {
            return !comments.isEmpty()
        }

        private val isCommentReplyExpanded: Boolean
            get() = expandedCommentPosition != RecyclerView.NO_POSITION

        private fun getComment(adapterPosition: Int): Comment {
            return comments[adapterPositionToCommentIndex(adapterPosition)]
        }

        private fun adapterPositionToCommentIndex(adapterPosition: Int): Int {
            var index = adapterPosition - 1 // less header
            if (isCommentReplyExpanded && adapterPosition > expandedCommentPosition)
                index--
            return index
        }

        private fun commentIndexToAdapterPosition(index: Int): Int {
            var adapterPosition = index + 1 // header
            if (isCommentReplyExpanded) {
                val expandedCommentIndex = adapterPositionToCommentIndex(expandedCommentPosition)
                if (index > expandedCommentIndex) adapterPosition++
            }
            return adapterPosition
        }

        private fun createCommentHolder(parent: ViewGroup): CommentHolder {
            val holder = CommentHolder(
                    layoutInflater.inflate(R.layout.designer_news_comment, parent, false))
            holder.itemView.setOnClickListener(View.OnClickListener {
                val collapsingSelf = expandedCommentPosition == holder.adapterPosition
                collapseExpandedComment()
                if (collapsingSelf) return@OnClickListener

                // show reply below this
                expandedCommentPosition = holder.adapterPosition
                notifyItemInserted(expandedCommentPosition + 1)
                notifyItemChanged(expandedCommentPosition, CommentAnimator.EXPAND_COMMENT)
            })
            holder.threadDepth!!.setImageDrawable(
                    ThreadedCommentDrawable(threadWidth, threadGap))

            return holder
        }

        private fun collapseExpandedComment() {
            if (!isCommentReplyExpanded) return
            notifyItemChanged(expandedCommentPosition, CommentAnimator.COLLAPSE_COMMENT)
            notifyItemRemoved(expandedCommentPosition + 1)
            isReplyToCommentFocused = false
            expandedCommentPosition = RecyclerView.NO_POSITION
            updateFabVisibility()
        }

        private fun bindComment(holder: CommentHolder, partialChanges: List<Any>?) {
            // Check if this is a partial update for expanding/collapsing a comment. If it is we
            // can do a partial bind as the bound data has not changed.
            if (partialChanges == null || partialChanges.isEmpty() ||
                    !(partialChanges.contains(CommentAnimator.COLLAPSE_COMMENT) || partialChanges.contains(CommentAnimator.EXPAND_COMMENT))) {

                val comment = getComment(holder.adapterPosition)
                HtmlUtils.parseMarkdownAndSetText(holder.comment, comment.body, markdown) { src, loadingSpan ->
                    Glide.with(this@DesignerNewsStory)
                            .load(src)
                            .asBitmap()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(ImageSpanTarget(holder.comment, loadingSpan))
                }
                if (comment.user_display_name != null) {
                    holder.author!!.text = comment.user_display_name.toLowerCase()
                }
                holder.author!!.isOriginalPoster = isOP(comment.user_id)
                if (comment.created_at != null) {
                    holder.timeAgo!!.text = DateUtils.getRelativeTimeSpanString(comment.created_at.time,
                            System.currentTimeMillis(),
                            DateUtils.SECOND_IN_MILLIS)
                            .toString().toLowerCase()
                }
                // FIXME updating drawable doesn't seem to be working, just create a new one
                //((ThreadedCommentDrawable) holder.threadDepth.getDrawable())
                //     .setDepth(comment.depth);

                holder.threadDepth!!.setImageDrawable(
                        ThreadedCommentDrawable(threadWidth, threadGap, comment.depth))
            }

            // set/clear expanded comment state
            holder.itemView.isActivated = holder.adapterPosition == expandedCommentPosition
            if (holder.adapterPosition == expandedCommentPosition) {
                val threadDepthWidth = holder.threadDepth!!.drawable.intrinsicWidth
                val leftShift = (-(threadDepthWidth + (holder.threadDepth!!.layoutParams as ViewGroup.MarginLayoutParams).marginEnd)).toFloat()
                holder.author!!.translationX = leftShift
                holder.comment!!.translationX = leftShift
                holder.threadDepth!!.translationX = (-(threadDepthWidth + (holder.threadDepth!!.layoutParams as ViewGroup.MarginLayoutParams).marginStart)).toFloat()
            } else {
                holder.threadDepth!!.translationX = 0f
                holder.author!!.translationX = 0f
                holder.comment!!.translationX = 0f
            }
        }

        private fun createCommentReplyHolder(parent: ViewGroup): CommentReplyHolder {
            val holder = CommentReplyHolder(layoutInflater
                    .inflate(R.layout.designer_news_comment_actions, parent, false))

            holder.commentVotes!!.setOnClickListener {
                if (designerNewsPrefs.isLoggedIn) {
                    val comment = getComment(holder.adapterPosition)
                    if (!holder.commentVotes!!.isActivated) {
                        val upvoteComment = designerNewsPrefs.api.upvoteComment(comment.id)
                        upvoteComment.enqueue(object : Callback<Comment> {
                            override fun onResponse(call: Call<Comment>,
                                                    response: Response<Comment>) {
                            }

                            override fun onFailure(call: Call<Comment>, t: Throwable) {}
                        })
                        comment.upvoted = true
                        comment.vote_count = comment.vote_count + 1
                        holder.commentVotes!!.text = comment.vote_count.toString()
                        holder.commentVotes!!.isActivated = true
                    } else {
                        comment.upvoted = false
                        comment.vote_count = comment.vote_count - 1
                        holder.commentVotes!!.text = comment.vote_count.toString()
                        holder.commentVotes!!.isActivated = false
                        // TODO actually delete upvote
                    }
                } else {
                    needsLogin(holder.commentVotes, 0)
                }
                holder.commentReply!!.clearFocus()
            }

            holder.postReply!!.setOnClickListener(View.OnClickListener {
                if (designerNewsPrefs.isLoggedIn) {
                    if (TextUtils.isEmpty(holder.commentReply!!.text)) return@OnClickListener
                    val inReplyToCommentPosition = holder.adapterPosition - 1
                    val replyingTo = getComment(inReplyToCommentPosition)
                    collapseExpandedComment()

                    // insert a locally created comment before actually
                    // hitting the API for immediate response
                    val replyDepth = replyingTo.depth + 1
                    val newReplyPosition = commentsAdapter.addCommentReply(
                            Comment.Builder()
                                    .setBody(holder.commentReply!!.text.toString())
                                    .setCreatedAt(Date())
                                    .setDepth(replyDepth)
                                    .setUserId(designerNewsPrefs.userId)
                                    .setUserDisplayName(designerNewsPrefs.userName)
                                    .setUserPortraitUrl(designerNewsPrefs.userAvatar)
                                    .build(),
                            inReplyToCommentPosition)
                    val replyToComment = designerNewsPrefs.api
                            .replyToComment(replyingTo.id,
                                    holder.commentReply!!.text.toString())
                    replyToComment.enqueue(object : Callback<Comment> {
                        override fun onResponse(call: Call<Comment>, response: Response<Comment>) {

                        }

                        override fun onFailure(call: Call<Comment>, t: Throwable) {
                            showToast("Failed to post comment :(")
                        }
                    })
                    holder.commentReply!!.text.clear()
                    ImeUtils.hideIme(holder.commentReply!!)
                    comments_list.scrollToPosition(newReplyPosition)
                } else {
                    needsLogin(holder.postReply, 0)
                }
                holder.commentReply!!.clearFocus()
            })

            holder.commentReply!!.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                isReplyToCommentFocused = hasFocus
                val interp = getFastOutSlowInInterpolator(holder.itemView.context)
                if (hasFocus) {
                    holder.commentVotes!!.animate()
                            .translationX((-holder.commentVotes!!.width).toFloat())
                            .alpha(0f)
                            .setDuration(200L).interpolator = interp
                    holder.replyLabel!!.animate()
                            .translationX((-holder.commentVotes!!.width).toFloat())
                            .setDuration(200L).interpolator = interp
                    holder.postReply!!.visibility = View.VISIBLE
                    holder.postReply!!.alpha = 0f
                    holder.postReply!!.animate()
                            .alpha(1f)
                            .setDuration(200L)
                            .setInterpolator(interp)
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationStart(animation: Animator) {
                                    holder.itemView.setHasTransientState(true)
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    holder.itemView.setHasTransientState(false)
                                }
                            })
                    updateFabVisibility()
                } else {
                    holder.commentVotes!!.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(200L).interpolator = interp
                    holder.replyLabel!!.animate()
                            .translationX(0f)
                            .setDuration(200L).interpolator = interp
                    holder.postReply!!.animate()
                            .alpha(0f)
                            .setDuration(200L)
                            .setInterpolator(interp)
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationStart(animation: Animator) {
                                    holder.itemView.setHasTransientState(true)
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    holder.postReply!!.visibility = View.INVISIBLE
                                    holder.itemView.setHasTransientState(true)
                                }
                            })
                    updateFabVisibility()
                }
                holder.postReply!!.isActivated = hasFocus
            }

            return holder
        }

        private fun bindCommentReply(holder: CommentReplyHolder) {
            val comment = getComment(holder.adapterPosition - 1)
            holder.commentVotes!!.text = comment.vote_count.toString()
            holder.commentVotes!!.isActivated = comment.upvoted != null && comment.upvoted!!
        }
    }

    /* package */
    internal class CommentHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        @BindView(R.id.depth)
        var threadDepth: ImageView? = null
        @BindView(R.id.comment_author)
        var author: AuthorTextView? = null
        @BindView(R.id.comment_time_ago)
        var timeAgo: TextView? = null
        @BindView(R.id.comment_text)
        var comment: TextView? = null

        init {
            ButterKnife.bind(this, itemView)
        }
    }

    /* package */
    internal class CommentReplyHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        @BindView(R.id.comment_votes)
        var commentVotes: Button? = null
        @BindView(R.id.comment_reply_label)
        var replyLabel: TextInputLayout? = null
        @BindView(R.id.comment_reply)
        var commentReply: EditText? = null
        @BindView(R.id.post_reply)
        var postReply: ImageButton? = null

        init {
            ButterKnife.bind(this, itemView)
        }
    }

    /* package */
    internal class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    /* package */
    internal class NoCommentsHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    /* package */
    internal class FooterHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private class CommentAnimator
    internal constructor(addRemoveDuration: Long) : SlideInItemAnimator() {

        init {
            addDuration = addRemoveDuration
            removeDuration = addRemoveDuration
        }

        override fun canReuseUpdatedViewHolder(viewHolder: RecyclerView.ViewHolder): Boolean {
            return true
        }

        override fun recordPreLayoutInformation(state: RecyclerView.State,
                                                viewHolder: RecyclerView.ViewHolder,
                                                changeFlags: Int,
                                                payloads: List<Any>): RecyclerView.ItemAnimator.ItemHolderInfo {
            val info = super.recordPreLayoutInformation(state, viewHolder, changeFlags, payloads) as CommentItemHolderInfo
            info.doExpand = payloads.contains(EXPAND_COMMENT)
            info.doCollapse = payloads.contains(COLLAPSE_COMMENT)
            return info
        }

        override fun animateChange(oldHolder: RecyclerView.ViewHolder,
                                   newHolder: RecyclerView.ViewHolder,
                                   preInfo: RecyclerView.ItemAnimator.ItemHolderInfo,
                                   postInfo: RecyclerView.ItemAnimator.ItemHolderInfo): Boolean {
            if (newHolder is CommentHolder && preInfo is CommentItemHolderInfo) {
                val expandedThreadOffset = (-(newHolder.threadDepth!!.width + (newHolder.threadDepth!!.layoutParams as ViewGroup.MarginLayoutParams)
                        .marginStart)).toFloat()
                val expandedAuthorCommentOffset = (-(newHolder.threadDepth!!.width + (newHolder.threadDepth!!.layoutParams as ViewGroup.MarginLayoutParams)
                        .marginEnd)).toFloat()

                if (preInfo.doExpand) {
                    val moveInterpolator = getFastOutSlowInInterpolator(newHolder
                            .itemView.context)
                    newHolder.threadDepth!!.translationX = 0f
                    newHolder.threadDepth!!.animate()
                            .translationX(expandedThreadOffset)
                            .setDuration(160L).interpolator = moveInterpolator
                    newHolder.author!!.translationX = 0f
                    newHolder.author!!.animate()
                            .translationX(expandedAuthorCommentOffset)
                            .setDuration(320L).interpolator = moveInterpolator
                    newHolder.comment!!.translationX = 0f
                    newHolder.comment!!.animate()
                            .translationX(expandedAuthorCommentOffset)
                            .setDuration(320L)
                            .setInterpolator(moveInterpolator)
                            .setListener(object : AnimatorListenerAdapter() {

                                override fun onAnimationStart(animation: Animator) {
                                    dispatchChangeStarting(newHolder, false)
                                    newHolder.itemView.setHasTransientState(true)
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    newHolder.itemView.setHasTransientState(false)
                                    dispatchChangeFinished(newHolder, false)
                                }
                            })
                } else if (preInfo.doCollapse) {
                    val enterInterpolator = getLinearOutSlowInInterpolator(newHolder.itemView
                            .context)
                    val moveInterpolator = getFastOutSlowInInterpolator(newHolder
                            .itemView
                            .context)

                    // return the thread depth indicator into place
                    newHolder.threadDepth!!.translationX = expandedThreadOffset
                    newHolder.threadDepth!!.animate()
                            .translationX(0f)
                            .setDuration(200L)
                            .setInterpolator(enterInterpolator)
                            .setListener(object : AnimatorListenerAdapter() {

                                override fun onAnimationStart(animation: Animator) {
                                    dispatchChangeStarting(newHolder, false)
                                    newHolder.itemView.setHasTransientState(true)
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    newHolder.itemView.setHasTransientState(false)
                                    dispatchChangeFinished(newHolder, false)
                                }
                            })

                    // return the text into place
                    newHolder.author!!.translationX = expandedAuthorCommentOffset
                    newHolder.author!!.animate()
                            .translationX(0f)
                            .setDuration(200L).interpolator = moveInterpolator
                    newHolder.comment!!.translationX = expandedAuthorCommentOffset
                    newHolder.comment!!.animate()
                            .translationX(0f)
                            .setDuration(200L).interpolator = moveInterpolator
                }
            }
            return super.animateChange(oldHolder, newHolder, preInfo, postInfo)
        }

        override fun obtainHolderInfo(): RecyclerView.ItemAnimator.ItemHolderInfo {
            return CommentItemHolderInfo()
        }

        /* package */
        internal class CommentItemHolderInfo : RecyclerView.ItemAnimator.ItemHolderInfo() {
            var doExpand: Boolean = false
            var doCollapse: Boolean = false
        }

        companion object {
            val EXPAND_COMMENT = 1
            val COLLAPSE_COMMENT = 2
        }
    }

    companion object {

        val EXTRA_STORY = "story"
        private val RC_LOGIN_UPVOTE = 7

        fun getCustomTabIntent(context: Context,
                               story: Story,
                               session: CustomTabsSession?): CustomTabsIntent.Builder {
            val upvoteStory = Intent(context, UpvoteStoryService::class.java).apply {
                action = UpvoteStoryService.ACTION_UPVOTE
                putExtra(UpvoteStoryService.EXTRA_STORY_ID, story.id)
            }
            val pendingIntent = PendingIntent.getService(context, 0, upvoteStory, 0)
            return CustomTabsIntent.Builder(session)
                    .setToolbarColor(ContextCompat.getColor(context, R.color.designer_news))
                    .setActionButton(DrawableUtils.drawableToBitmap(context,
                            R.drawable.ic_upvote_filled_24dp_white),
                            context.getString(R.string.upvote_story),
                            pendingIntent,
                            false)
                    .setShowTitle(true)
                    .enableUrlBarHiding()
                    .addDefaultShareMenuItem()
        }
    }
}
